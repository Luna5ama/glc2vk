package dev.vibris.core

import dev.vibris.api.CaptureResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.RuntimeAction
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.ActionKind
import dev.vibris.protocol.v2.ActionReceipt
import dev.vibris.protocol.v2.CompareReceipt
import dev.vibris.protocol.v2.EmptyReceipt
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobResult
import dev.vibris.protocol.v2.JobStage
import dev.vibris.protocol.v2.ReceiptStatus
import dev.vibris.protocol.v2.RuntimeMutationReceipt
import dev.vibris.protocol.v2.WaitFramesReceipt
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
        var reload = ReloadResult.success(emptyList())
        val action = captures.prepareActions(job, runtime.getResourceCatalog(), emptyList())
        val prepared = action.prepared
        val diagnostics = ArrayList<ReloadResult.Diagnostic>()
        val captured = ArrayList<CaptureResult>()
        val completedCapturePlans = ArrayList<dev.vibris.api.CapturePlan>()
        val receipts = ArrayList<ActionReceipt>()
        var comparison: CompareReceipt? = null
        try {
            fun executeSteps() {
                for (step in action.program.steps) {
                    when (step.type) {
                        CaptureProgramBuilder.ActionType.LOAD -> {
                            val load = step.loadShader!!
                            val loaded = load(job, load, progress, deadline)
                            reload = loaded.reload
                            diagnostics.addAll(reload.diagnostics)
                            prepared?.addDiagnostics(reload.diagnostics)
                            owner.await(runtime.executeAction(RuntimeAction.InspectShader), job, deadline)
                            receipts.add(
                                success(step.actionIndex, ActionKind.ACTION_KIND_LOAD_SHADER)
                                    .setRuntimeMutation(mutation(load.sourceUuid))
                                    .build(),
                            )
                        }
                        CaptureProgramBuilder.ActionType.ACTIVATE -> {
                            reload = activate(job, step.sourceUuid!!, progress, deadline)
                            diagnostics.addAll(reload.diagnostics)
                            prepared?.addDiagnostics(reload.diagnostics)
                            receipts.add(
                                success(step.actionIndex, ActionKind.ACTION_KIND_ACTIVATE_SOURCE)
                                    .setRuntimeMutation(mutation(step.sourceUuid))
                                    .build(),
                            )
                        }
                        CaptureProgramBuilder.ActionType.RESET -> {
                            owner.reset(job, progress, deadline)
                            receipts.add(emptySuccess(step.actionIndex, ActionKind.ACTION_KIND_RESET_TEMPORAL_STATE))
                        }
                        CaptureProgramBuilder.ActionType.WAIT -> {
                            owner.waitFrames(job, progress, deadline, step.frames)
                            receipts.add(
                                success(step.actionIndex, ActionKind.ACTION_KIND_WAIT_FRAMES)
                                    .setWaitFrames(
                                        WaitFramesReceipt.newBuilder()
                                            .setRequestedFrames(step.frames)
                                            .setCompletedFrames(step.frames),
                                    )
                                    .build(),
                            )
                        }
                        CaptureProgramBuilder.ActionType.CAPTURE -> {
                            if (prepared == null) throw captureUnavailable()
                            captured.add(owner.capture(job, progress, deadline, prepared, step.capture!!))
                            completedCapturePlans.add(step.capture)
                            receipts.add(emptySuccess(step.actionIndex, captureKind(job, step.actionIndex)))
                        }
                        CaptureProgramBuilder.ActionType.PATCHED_SHADERS -> {
                            if (prepared == null) throw captureUnavailable()
                            val placeholder = step.capture!!
                            val result = owner.capturePatchedShaders(
                                job,
                                progress,
                                deadline,
                                prepared,
                                placeholder.targets.single().artifactName,
                            )
                            captured.add(result)
                            completedCapturePlans.add(CapturePlanBuilder.realizePatchedShaders(placeholder, result))
                            receipts.add(emptySuccess(step.actionIndex, ActionKind.ACTION_KIND_GET_PATCHED_SHADERS))
                        }
                        CaptureProgramBuilder.ActionType.COMPARE -> {
                            if (prepared == null) throw captureUnavailable()
                            progress.accept(JobStage.JOB_STAGE_COMPARING)
                            probe.event(job.requestId, "COMPARING")
                            comparison = captures.compare(prepared, step.comparison!!)
                            receipts.add(
                                success(step.actionIndex, ActionKind.ACTION_KIND_COMPARE_CAPTURES)
                                    .setComparison(comparison)
                                    .build(),
                            )
                        }
                        CaptureProgramBuilder.ActionType.RUNTIME -> {
                            owner.await(
                                runtime.executeAction(RuntimeActionProtocol.toApi(step.runtimeAction!!)),
                                job,
                                deadline,
                            )
                            receipts.add(
                                emptySuccess(step.actionIndex, RuntimeActionProtocol.kind(step.runtimeAction)),
                            )
                        }
                    }
                }
            }

            if (prepared == null) {
                executeSteps()
                return JobResult.newBuilder().addAllActionReceipts(receipts).build()
            }
            prepared.use {
                executeSteps()
                progress.accept(JobStage.JOB_STAGE_WRITING_ARTIFACTS)
                probe.event(job.requestId, "WRITING_ARTIFACTS")
                val resultArtifacts = ProfileResultArtifacts.write(job.submission, prepared.transaction, receipts)
                progress.accept(JobStage.JOB_STAGE_FINALIZING)
                probe.event(job.requestId, "FINALIZING")
                return captures.commit(
                    job,
                    prepared,
                    completedCapturePlans,
                    captured,
                    comparison,
                    resultArtifacts,
                ).toBuilder()
                    .addAllActionReceipts(receipts)
                    .build()
            }
        } catch (exception: IOException) {
            throw CaptureJobExecutor.failure(exception)
        }
    }

    private fun activate(
        job: CoreJob,
        uuid: String,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): ReloadResult {
        val source = job.sources.firstOrNull { it.uuid().equals(uuid, ignoreCase = true) }
            ?: throw RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_INVALID_SOURCE,
                "Action references an unprepared source.",
            )
        val reload = owner.activateSource(job, source, progress, deadline)
        owner.applyContext(job, progress, deadline)
        return reload
    }

    private fun load(
        job: CoreJob,
        load: dev.vibris.protocol.v2.LoadShader,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): RuntimeJobExecutor.LoadResult {
        val source = job.sources.firstOrNull { it.uuid().equals(load.sourceUuid, ignoreCase = true) }
            ?: throw RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_INVALID_SOURCE,
                "Load action references an unprepared source.",
            )
        return owner.loadShader(job, source, load.config, progress, deadline)
    }

    private fun captureKind(job: CoreJob, actionIndex: Int): ActionKind =
        RuntimeActionProtocol.kind(job.submission.actionSequence.getActions(actionIndex))

    private fun mutation(sourceUuid: String): RuntimeMutationReceipt = RuntimeMutationReceipt.newBuilder()
        .setSourceUuid(sourceUuid)
        .setCompletedAtUnixMs(System.currentTimeMillis())
        .build()

    private fun success(index: Int, kind: ActionKind): ActionReceipt.Builder = ActionReceipt.newBuilder()
        .setActionIndex(index)
        .setKind(kind)
        .setStatus(ReceiptStatus.RECEIPT_STATUS_OK)

    private fun emptySuccess(index: Int, kind: ActionKind): ActionReceipt = success(index, kind)
        .setEmpty(EmptyReceipt.getDefaultInstance())
        .build()

    private fun captureUnavailable() = RuntimeJobExecutor.Failure(
        ErrorCode.ERROR_CODE_CAPTURE_FAILED,
        "Capture storage is unavailable.",
    )
}
