package dev.vibris.core;

import dev.vibris.api.CaptureResult;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.api.VibrisRuntimeAdapter;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobResult;
import dev.vibris.protocol.v1.JobResultKind;
import dev.vibris.protocol.v1.JobStage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class ActionJobExecutor {
    private final VibrisRuntimeAdapter runtime;
    private final CoreProbe probe;
    private final CaptureJobExecutor captures;
    private final RuntimeJobExecutor owner;

    ActionJobExecutor(VibrisRuntimeAdapter runtime, CoreProbe probe, CaptureJobExecutor captures,
        RuntimeJobExecutor owner) {
        this.runtime = runtime;
        this.probe = probe;
        this.captures = captures;
        this.owner = owner;
    }

    JobResult execute(CoreJob job, Consumer<JobStage> progress, long deadline, ReloadResult reload)
        throws RuntimeJobExecutor.Failure {
        CaptureJobExecutor.ActionPrepared action = captures.prepareActions(
            job, runtime.getResourceCatalog(), reload.diagnostics());
        if (action.prepared() == null) {
            for (CaptureProgramBuilder.ActionStep step : action.program().steps()) {
                if (step.type() == CaptureProgramBuilder.ActionType.RESET) reset(job, progress, deadline);
                else if (step.type() == CaptureProgramBuilder.ActionType.WAIT) {
                    waitFrames(job, progress, deadline, step.frames());
                } else {
                    throw new RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, "Capture storage is unavailable.");
                }
            }
            JobResult.Builder result = JobResult.newBuilder().setKind(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE);
            CaptureProtocolArtifacts.addDiagnostics(result, reload.diagnostics(), "");
            return result.build();
        }
        try (CaptureJobExecutor.Prepared prepared = action.prepared()) {
            List<CaptureResult> results = new ArrayList<>();
            for (CaptureProgramBuilder.ActionStep step : action.program().steps()) {
                switch (step.type()) {
                    case RESET -> reset(job, progress, deadline);
                    case WAIT -> waitFrames(job, progress, deadline, step.frames());
                    case CAPTURE -> results.add(capture(job, progress, deadline, prepared, step.capture()));
                }
            }
            progress.accept(JobStage.JOB_STAGE_WRITING_ARTIFACTS);
            probe.event(job.requestId, "WRITING_ARTIFACTS");
            progress.accept(JobStage.JOB_STAGE_FINALIZING);
            probe.event(job.requestId, "FINALIZING");
            return captures.commit(job, prepared, results, null);
        } catch (java.io.IOException exception) {
            throw CaptureJobExecutor.failure(exception);
        }
    }

    private void reset(CoreJob job, Consumer<JobStage> progress, long deadline)
        throws RuntimeJobExecutor.Failure {
        progress.accept(JobStage.JOB_STAGE_RESETTING_TEMPORAL_STATE);
        probe.event(job.requestId, "RESETTING_TEMPORAL_STATE");
        TemporalResetResult reset = owner.await(runtime.resetTemporalState(job.cancellation.token()), job, deadline);
        if (!reset.successful()) throw new RuntimeJobExecutor.Failure(
            ErrorCode.INTERNAL_ERROR, "Runtime temporal state reset failed.");
    }

    private void waitFrames(CoreJob job, Consumer<JobStage> progress, long deadline, int frames)
        throws RuntimeJobExecutor.Failure {
        progress.accept(JobStage.JOB_STAGE_WARMING_UP);
        probe.event(job.requestId, "WARMING_UP");
        owner.await(runtime.waitRenderedFrames(frames, job.cancellation.token()), job, deadline);
    }

    private CaptureResult capture(CoreJob job, Consumer<JobStage> progress, long deadline,
        CaptureJobExecutor.Prepared prepared, dev.vibris.api.CapturePlan plan)
        throws RuntimeJobExecutor.Failure {
        progress.accept(JobStage.JOB_STAGE_CAPTURING);
        probe.event(job.requestId, "CAPTURING");
        return owner.awaitCapture(runtime.capture(plan, prepared.sink(), job.cancellation.token()), job, deadline);
    }
}