package dev.vibris.core

import dev.vibris.api.CaptureResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v1.AbComparisonResult
import dev.vibris.protocol.v1.ActionResult
import dev.vibris.protocol.v1.ErrorCode
import dev.vibris.protocol.v1.JobResult
import dev.vibris.protocol.v1.JobResultKind
import dev.vibris.protocol.v1.JobStage
import java.io.IOException
import java.util.function.Consumer

internal class ActionJobExecutor(
    private val runtime: VibrisRuntimeAdapter,
    private val probe: CoreProbe,
    private val captures: CaptureJobExecutor,
    private val owner: RuntimeJobExecutor,
) {
    @Throws(RuntimeJobExecutor.Failure::class)
    fun execute(job: CoreJob, progress: Consumer<JobStage>, deadline: Long): JobResult {
        val first = job.submission.actions.actionsList.firstOrNull()
        var reload = if (first != null && first.hasActivateSource()) {
            activate(job, first.activateSource.sourceUuid, progress, deadline)
        } else {
            ReloadResult.success(emptyList())
        }
        val action = captures.prepareActions(job, runtime.getResourceCatalog(), reload.diagnostics)
        val prepared = action.prepared
        val diagnostics = ArrayList(reload.diagnostics)
        val results = ArrayList<CaptureResult>()
        val actionResults = ArrayList<ActionResult>()
        var comparison: AbComparisonResult? = null
        var firstActivation = true
        try {
            fun executeSteps() {
                for (step in action.program.steps) {
                    when (step.type) {
                        CaptureProgramBuilder.ActionType.ACTIVATE -> {
                            if (firstActivation) {
                                firstActivation = false
                            } else {
                                reload = activate(job, step.sourceUuid!!, progress, deadline)
                                diagnostics.addAll(reload.diagnostics)
                                prepared?.addDiagnostics(reload.diagnostics)
                            }
                        }
                        CaptureProgramBuilder.ActionType.RESET -> owner.reset(job, progress, deadline)
                        CaptureProgramBuilder.ActionType.WAIT -> owner.waitFrames(job, progress, deadline, step.frames)
                        CaptureProgramBuilder.ActionType.CAPTURE -> {
                            if (prepared == null) throw captureUnavailable()
                            results.add(owner.capture(job, progress, deadline, prepared, step.capture!!))
                        }
                        CaptureProgramBuilder.ActionType.COMPARE -> {
                            if (prepared == null) throw captureUnavailable()
                            progress.accept(JobStage.JOB_STAGE_COMPARING)
                            probe.event(job.requestId, "COMPARING")
                            comparison = captures.compare(prepared, step.comparison!!)
                        }
                        CaptureProgramBuilder.ActionType.RUNTIME -> {
                            val action = step.runtimeAction!!
                            val json = owner.await(
                                runtime.executeAction(RuntimeActionProtocol.toApi(action)),
                                job,
                                deadline,
                            )
                            actionResults.add(
                                ActionResult.newBuilder()
                                    .setActionIndex(step.actionIndex)
                                    .setKind(RuntimeActionProtocol.kind(action))
                                    .setJson(json)
                                    .build(),
                            )
                        }
                    }
                }
            }
            if (prepared == null) {
                executeSteps()
                val result = JobResult.newBuilder().setKind(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE)
                CaptureProtocolArtifacts.addDiagnostics(result, diagnostics, "")
                result.addAllActionResults(actionResults)
                return result.build()
            }
            prepared.use {
                executeSteps()
                progress.accept(JobStage.JOB_STAGE_WRITING_ARTIFACTS)
                probe.event(job.requestId, "WRITING_ARTIFACTS")
                progress.accept(JobStage.JOB_STAGE_FINALIZING)
                probe.event(job.requestId, "FINALIZING")
                return captures.commit(job, prepared, results, comparison).toBuilder()
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

    private fun captureUnavailable() = RuntimeJobExecutor.Failure(
        ErrorCode.CAPTURE_FAILED,
        "Capture storage is unavailable.",
    )
}
