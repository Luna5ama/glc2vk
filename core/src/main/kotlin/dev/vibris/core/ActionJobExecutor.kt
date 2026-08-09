package dev.vibris.core

import dev.vibris.api.CaptureResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.RuntimeAction
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v1.AbComparisonResult
import dev.vibris.protocol.v1.ActionResult
import dev.vibris.protocol.v1.ErrorCode
import dev.vibris.protocol.v1.JobResult
import dev.vibris.protocol.v1.JobResultKind
import dev.vibris.protocol.v1.JobStage
import java.io.IOException
import java.util.function.Consumer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class ActionJobExecutor(
    private val runtime: VibrisRuntimeAdapter,
    private val probe: CoreProbe,
    private val captures: CaptureJobExecutor,
    private val owner: RuntimeJobExecutor,
) {
    @Throws(RuntimeJobExecutor.Failure::class)
    fun execute(
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        isolation: BenchmarkCaseIsolation? = null,
    ): JobResult {
        var reload = ReloadResult.success(emptyList())
        val action = captures.prepareActions(job, runtime.getResourceCatalog(), emptyList())
        val prepared = action.prepared
        val diagnostics = ArrayList<ReloadResult.Diagnostic>()
        val results = ArrayList<CaptureResult>()
        val completedCapturePlans = ArrayList<dev.vibris.api.CapturePlan>()
        val actionResults = ArrayList<ActionResult>()
        var comparison: AbComparisonResult? = null
        var currentCase: dev.vibris.protocol.v1.LoadShader? = null
        var caseFailed = false
        try {
            fun executeSteps() {
                for (step in action.program.steps) {
                    if (step.type == CaptureProgramBuilder.ActionType.LOAD) {
                        currentCase = step.loadShader!!
                        caseFailed = false
                    } else if (caseFailed) {
                        continue
                    }
                    try {
                        when (step.type) {
                            CaptureProgramBuilder.ActionType.LOAD -> {
                                val load = step.loadShader!!
                                val loaded = load(job, load, progress, deadline, isolation)
                                reload = loaded.reload
                                diagnostics.addAll(reload.diagnostics)
                                prepared?.addDiagnostics(reload.diagnostics)
                                val inspection = inspectShader(job, deadline)
                                isolation?.shaderGenerationConfirmed(load.sourceUuid, inspection)
                                actionResults.add(
                                    actionResult(
                                        step.actionIndex,
                                        dev.vibris.protocol.v1.JobActionKind.JOB_ACTION_KIND_LOAD_SHADER,
                                        load,
                                        reload,
                                        inspection,
                                        BenchmarkProvenance.create(job, load, loaded, inspection),
                                        null,
                                    ),
                                )
                            }
                            CaptureProgramBuilder.ActionType.ACTIVATE -> {
                                reload = activate(job, step.sourceUuid!!, progress, deadline)
                                diagnostics.addAll(reload.diagnostics)
                                prepared?.addDiagnostics(reload.diagnostics)
                            }
                            CaptureProgramBuilder.ActionType.RESET -> owner.reset(job, progress, deadline)
                            CaptureProgramBuilder.ActionType.WAIT ->
                                owner.waitFrames(job, progress, deadline, step.frames, isolation)
                            CaptureProgramBuilder.ActionType.CAPTURE -> {
                                if (prepared == null) throw captureUnavailable()
                                results.add(owner.capture(job, progress, deadline, prepared, step.capture!!))
                                completedCapturePlans.add(step.capture)
                            }
                            CaptureProgramBuilder.ActionType.PATCHED_SHADERS -> {
                                if (prepared == null) throw captureUnavailable()
                                val placeholder = step.capture!!
                                val captured = owner.capturePatchedShaders(
                                    job,
                                    progress,
                                    deadline,
                                    prepared,
                                    placeholder.targets.single().artifactName,
                                )
                                results.add(captured)
                                completedCapturePlans.add(CapturePlanBuilder.realizePatchedShaders(placeholder, captured))
                            }
                            CaptureProgramBuilder.ActionType.COMPARE -> {
                                if (prepared == null) throw captureUnavailable()
                                progress.accept(JobStage.JOB_STAGE_COMPARING)
                                probe.event(job.requestId, "COMPARING")
                                comparison = captures.compare(prepared, step.comparison!!)
                            }
                            CaptureProgramBuilder.ActionType.RUNTIME -> {
                                val runtimeAction = step.runtimeAction!!
                                if (runtimeAction.hasGetGpuMetrics()) {
                                    isolation?.sampleStarted()
                                    progress.accept(JobStage.JOB_STAGE_SAMPLING)
                                }
                                val json = owner.await(
                                    runtime.executeAction(RuntimeActionProtocol.toApi(runtimeAction)),
                                    job,
                                    deadline,
                                )
                                if (runtimeAction.hasGetGpuMetrics()) isolation?.sampleCompleted()
                                actionResults.add(
                                    ActionResult.newBuilder()
                                        .setActionIndex(step.actionIndex)
                                        .setKind(RuntimeActionProtocol.kind(runtimeAction))
                                        .setJson(json)
                                        .setCaseId(currentCase?.caseId.orEmpty())
                                        .build(),
                                )
                            }
                        }
                    } catch (failure: RuntimeJobExecutor.Failure) {
                        val load = currentCase
                        if (load == null || !load.continueOnFailure) throw failure
                        val input = job.submission.actions.getActions(step.actionIndex)
                        actionResults.add(
                            actionResult(
                                step.actionIndex,
                                RuntimeActionProtocol.kind(input),
                                load,
                                null,
                                null,
                                null,
                                failure,
                            ),
                        )
                        caseFailed = true
                    } catch (exception: IllegalArgumentException) {
                        val load = currentCase
                        if (load == null || !load.continueOnFailure) throw exception
                        val input = job.submission.actions.getActions(step.actionIndex)
                        val failure = RuntimeJobExecutor.Failure(ErrorCode.INTERNAL_ERROR, exception.message)
                        actionResults.add(
                            actionResult(
                                step.actionIndex,
                                RuntimeActionProtocol.kind(input),
                                load,
                                null,
                                null,
                                null,
                                failure,
                            ),
                        )
                        caseFailed = true
                    }
                }
            }
            if (prepared == null) {
                executeSteps()
                owner.restoreBenchmarkCase(job, isolation, progress)
                isolation?.requireComplete()
                val result = JobResult.newBuilder().setKind(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE)
                CaptureProtocolArtifacts.addDiagnostics(result, diagnostics, "")
                result.addAllActionResults(actionResults)
                return result.build()
            }
            prepared.use {
                executeSteps()
                owner.restoreBenchmarkCase(job, isolation, progress)
                isolation?.requireComplete()
                progress.accept(JobStage.JOB_STAGE_WRITING_ARTIFACTS)
                probe.event(job.requestId, "WRITING_ARTIFACTS")
                progress.accept(JobStage.JOB_STAGE_FINALIZING)
                probe.event(job.requestId, "FINALIZING")
                val resultArtifacts = ProfileResultArtifacts.write(
                    job.submission,
                    prepared.transaction,
                    actionResults,
                    isolation?.receipts().orEmpty(),
                )
                return captures.commit(
                    job,
                    prepared,
                    completedCapturePlans,
                    results,
                    comparison,
                    resultArtifacts,
                ).toBuilder()
                    .addAllActionResults(actionResults)
                    .build()
            }
        } catch (exception: IOException) {
            throw CaptureJobExecutor.failure(exception)
        }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    private fun activate(
        job: CoreJob,
        uuid: String,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): ReloadResult {
        val source = job.sources.firstOrNull { it.uuid().equals(uuid, ignoreCase = true) }
            ?: throw RuntimeJobExecutor.Failure(
                ErrorCode.INVALID_SOURCE_UUID,
                "Action references an unprepared source.",
            )
        val reload = owner.activateSource(job, source, progress, deadline)
        owner.applyContext(job, progress, deadline)
        return reload
    }

    private fun load(
        job: CoreJob,
        load: dev.vibris.protocol.v1.LoadShader,
        progress: Consumer<JobStage>,
        deadline: Long,
        isolation: BenchmarkCaseIsolation?,
    ): RuntimeJobExecutor.LoadResult {
        val source = job.sources.firstOrNull { it.uuid().equals(load.sourceUuid, ignoreCase = true) }
            ?: throw RuntimeJobExecutor.Failure(
                ErrorCode.INVALID_SOURCE_UUID,
                "Load action references an unprepared source.",
            )
        return owner.loadShader(job, source, load.configId, progress, deadline, isolation)
    }

    private fun actionResult(
        actionIndex: Int,
        kind: dev.vibris.protocol.v1.JobActionKind,
        load: dev.vibris.protocol.v1.LoadShader,
        reload: ReloadResult?,
        inspection: JsonObject?,
        provenance: JsonObject?,
        failure: RuntimeJobExecutor.Failure?,
    ): ActionResult {
        val reloadDiagnostics = reload?.diagnostics ?: failure?.diagnostics.orEmpty()
        val payload = buildJsonObject {
            put("success", failure == null)
            put("case_id", load.caseId)
            put("source", load.sourceId)
            put("config", load.configId)
            inspection?.forEach { (key, value) -> put(key, value) }
            provenance?.let { put("provenance", it) }
            put("diagnostics", buildJsonArray {
                reloadDiagnostics.forEach { diagnostic ->
                    add(buildJsonObject {
                        put("severity", diagnostic.severity.name.lowercase())
                        put("source", diagnostic.source)
                        put("line", diagnostic.line)
                        put("message", diagnostic.message)
                    })
                }
            })
            if (failure != null) {
                put("error_code", failure.code.name.removePrefix("ERROR_CODE_"))
                put("message", failure.message ?: "Action failed.")
                failure.artifacts.firstOrNull()?.path?.takeIf { it.isNotBlank() }?.let { put("log_path", it) }
            }
        }
        return ActionResult.newBuilder()
            .setActionIndex(actionIndex)
            .setKind(kind)
            .setJson(payload.toString())
            .setCaseId(load.caseId)
            .build()
    }

    private fun inspectShader(job: CoreJob, deadline: Long): JsonObject =
        Json.parseToJsonElement(
            owner.await(runtime.executeAction(RuntimeAction.InspectShader), job, deadline),
        ).jsonObject

    private fun captureUnavailable() = RuntimeJobExecutor.Failure(
        ErrorCode.CAPTURE_FAILED,
        "Capture storage is unavailable.",
    )
}