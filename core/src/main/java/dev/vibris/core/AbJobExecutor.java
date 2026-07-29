package dev.vibris.core;

import dev.vibris.api.CaptureResult;
import dev.vibris.api.ReloadResult;
import dev.vibris.protocol.v1.AbComparisonResult;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobResult;
import dev.vibris.protocol.v1.JobStage;

import java.util.List;
import java.util.function.Consumer;

final class AbJobExecutor {
    private final RuntimeJobExecutor owner;
    private final CaptureJobExecutor captures;
    private final SourceActivator activator;

    AbJobExecutor(RuntimeJobExecutor owner, CaptureJobExecutor captures, SourceActivator activator) {
        this.owner = owner;
        this.captures = captures;
        this.activator = activator;
    }

    JobResult execute(CoreJob job, Consumer<JobStage> progress, long deadline)
        throws RuntimeJobExecutor.Failure {
        var recipe = job.submission.getRecipe().getAbCompare();
        SourceRegistry.Lease baseline = source(job, recipe.getBaseline().getSourceUuid());
        SourceRegistry.Lease candidate = source(job, recipe.getCandidate().getSourceUuid());
        if (baseline == candidate) throw new RuntimeJobExecutor.Failure(
            ErrorCode.INVALID_SOURCE_UUID, "A/B sources must be distinct.");

        ReloadResult baselineReload = owner.activateSource(job, baseline, progress, deadline);
        owner.applyContext(job, progress, deadline);
        owner.reset(job, progress, deadline);
        owner.waitFrames(job, progress, deadline, recipe.getWarmupFrames());

        CaptureJobExecutor.AbPrepared ab = captures.prepareAb(
            job, owner.runtime().getResourceCatalog(), baselineReload.diagnostics());
        try (CaptureJobExecutor.Prepared prepared = ab.prepared()) {
            CaptureResult a = owner.capture(job, progress, deadline, prepared, ab.program().baseline());
            ReloadResult candidateReload = owner.activateSource(job, candidate, progress, deadline);
            prepared.addDiagnostics(candidateReload.diagnostics());
            activator.release(List.of(baseline));
            owner.applyContext(job, progress, deadline);
            owner.reset(job, progress, deadline);
            owner.waitFrames(job, progress, deadline, recipe.getWarmupFrames());
            CaptureResult b = owner.capture(job, progress, deadline, prepared, ab.program().candidate());

            progress.accept(JobStage.JOB_STAGE_COMPARING);
            owner.probe().event(job.requestId, "COMPARING");
            AbComparisonResult comparison = captures.compare(job, ab);
            progress.accept(JobStage.JOB_STAGE_WRITING_ARTIFACTS);
            owner.probe().event(job.requestId, "WRITING_ARTIFACTS");
            progress.accept(JobStage.JOB_STAGE_FINALIZING);
            owner.probe().event(job.requestId, "FINALIZING");
            return captures.commit(job, prepared, List.of(a, b), comparison);
        } catch (java.io.IOException exception) {
            throw CaptureJobExecutor.failure(exception);
        }
    }

    private static SourceRegistry.Lease source(CoreJob job, String uuid) throws RuntimeJobExecutor.Failure {
        return job.sources.stream().filter(source -> source.uuid().equalsIgnoreCase(uuid)).findFirst()
            .orElseThrow(() -> new RuntimeJobExecutor.Failure(
                ErrorCode.INVALID_SOURCE_UUID, "A/B recipe references an unprepared source."));
    }
}