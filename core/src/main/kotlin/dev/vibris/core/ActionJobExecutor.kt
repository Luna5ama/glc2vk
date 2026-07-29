package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.TemporalResetResult
import dev.vibris.api.VibrisRuntimeAdapter
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
    fun execute(
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        reload: ReloadResult,
    ): JobResult {
        val action = captures.prepareActions(job, runtime.getResourceCatalog(), reload.diagnostics())
        val prepared = action.prepared
        if (prepared == null) {
            for (step in action.program.steps) {
                when (step.type) {
                    CaptureProgramBuilder.ActionType.RESET -> reset(job, progress, deadline)
                    CaptureProgramBuilder.ActionType.WAIT -> waitFrames(job, progress, deadline, step.frames)
                    CaptureProgramBuilder.ActionType.CAPTURE -> throw RuntimeJobExecutor.Failure(
                        ErrorCode.CAPTURE_FAILED,
                        "Capture storage is unavailable.",
                    )
                }
            }
            val result = JobResult.newBuilder().setKind(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE)
            CaptureProtocolArtifacts.addDiagnostics(result, reload.diagnostics(), "")
            return result.build()
        }
        try {
            prepared.use {
                val results = ArrayList<CaptureResult>()
                for (step in action.program.steps) {
                    when (step.type) {
                        CaptureProgramBuilder.ActionType.RESET -> reset(job, progress, deadline)
                        CaptureProgramBuilder.ActionType.WAIT -> waitFrames(job, progress, deadline, step.frames)
                        CaptureProgramBuilder.ActionType.CAPTURE -> results.add(
                            capture(job, progress, deadline, prepared, step.capture!!),
                        )
                    }
                }
                progress.accept(JobStage.JOB_STAGE_WRITING_ARTIFACTS)
                probe.event(job.requestId, "WRITING_ARTIFACTS")
                progress.accept(JobStage.JOB_STAGE_FINALIZING)
                probe.event(job.requestId, "FINALIZING")
                return captures.commit(job, prepared, results, null)
            }
        } catch (exception: IOException) {
            throw CaptureJobExecutor.failure(exception)
        }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    private fun reset(job: CoreJob, progress: Consumer<JobStage>, deadline: Long) {
        progress.accept(JobStage.JOB_STAGE_RESETTING_TEMPORAL_STATE)
        probe.event(job.requestId, "RESETTING_TEMPORAL_STATE")
        val reset: TemporalResetResult = owner.await(
            runtime.resetTemporalState(job.cancellation.token()),
            job,
            deadline,
        )
        if (!reset.successful) {
            throw RuntimeJobExecutor.Failure(ErrorCode.INTERNAL_ERROR, "Runtime temporal state reset failed.")
        }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    private fun waitFrames(job: CoreJob, progress: Consumer<JobStage>, deadline: Long, frames: Int) {
        progress.accept(JobStage.JOB_STAGE_WARMING_UP)
        probe.event(job.requestId, "WARMING_UP")
        owner.await(runtime.waitRenderedFrames(frames, job.cancellation.token()), job, deadline)
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    private fun capture(
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        prepared: CaptureJobExecutor.Prepared,
        plan: CapturePlan,
    ): CaptureResult {
        progress.accept(JobStage.JOB_STAGE_CAPTURING)
        probe.event(job.requestId, "CAPTURING")
        return owner.awaitCapture(runtime.capture(plan, prepared.sink(), job.cancellation.token()), job, deadline)
    }
}