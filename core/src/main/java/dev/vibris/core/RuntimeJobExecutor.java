package dev.vibris.core;

import dev.vibris.api.CancellationToken;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.api.VibrisRuntimeAdapter;
import dev.vibris.protocol.v1.ArtifactMetadata;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobCompleted;
import dev.vibris.protocol.v1.JobResult;
import dev.vibris.protocol.v1.JobResultKind;
import dev.vibris.protocol.v1.JobStage;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class RuntimeJobExecutor {
    private final VibrisRuntimeAdapter runtime;
    private final CoreProbe probe;
    private final SourceActivator activator;
    private final ShaderLogSink shaderLogs;
    private final CaptureJobExecutor captures;
    private final RuntimeAwaiter awaiter;
    private final ActionJobExecutor actions;
    private final AbJobExecutor ab;

    RuntimeJobExecutor(
        VibrisRuntimeAdapter runtime,
        CoreProbe probe,
        SourceActivator activator,
        ShaderLogSink shaderLogs
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.probe = probe;
        this.activator = activator;
        this.shaderLogs = shaderLogs;
        captures = new CaptureJobExecutor(shaderLogs instanceof ArtifactManager manager ? manager : null);
        awaiter = new RuntimeAwaiter(probe);
        actions = new ActionJobExecutor(runtime, probe, captures, this);
        ab = new AbJobExecutor(this, captures, activator);
    }

    TerminalResult execute(CoreJob job, Consumer<JobStage> progress) throws Failure {
        long startedAtUnixMs = System.currentTimeMillis();
        long startedNanos = System.nanoTime();
        long deadline = RuntimeJobContext.deadline(job);
        JobResult result;
        if (job.submission.hasRecipe() && job.submission.getRecipe().hasAbCompare()) {
            result = ab.execute(job, progress, deadline);
        } else {
            ReloadResult reload = activateSource(job, progress, deadline);
            applyContext(job, progress, deadline);
            if (job.submission.hasActions()) {
                result = actions.execute(job, progress, deadline, reload);
            } else {
                reset(job, progress, deadline);
                waitFrames(job, progress, deadline, captures.waitFrames(job));
                result = capture(job, progress, deadline, reload);
            }
        }
        result = awaiter.withTimings(job, result, startedAtUnixMs, startedNanos);
        return TerminalResult.completed(JobCompleted.newBuilder()
            .setRequestId(job.requestId)
            .setResult(result)
            .build());
    }

    void applyContext(CoreJob job, Consumer<JobStage> progress, long deadline) throws Failure {
        CancellationToken cancellation = job.cancellation.token();
        progress.accept(JobStage.JOB_STAGE_LOADING_WORLD);
        probe.event(job.requestId, "ENSURING_WORLD");
        progress.accept(JobStage.JOB_STAGE_APPLYING_CONTEXT);
        ContextApplyResult context = await(
            runtime.ensureWorldAndContext(RuntimeJobContext.toApi(job.submission.getContext()), cancellation),
            job, deadline);
        if (!context.successful()) throw new Failure(ErrorCode.WORLD_LOAD_FAILED, context.message());
        probe.contextApplied(job.requestId, job.workspaceId, RuntimeJobContext.toProtocol(context.context()));
    }

    void reset(CoreJob job, Consumer<JobStage> progress, long deadline) throws Failure {
        progress.accept(JobStage.JOB_STAGE_RESETTING_TEMPORAL_STATE);
        probe.event(job.requestId, "RESETTING_TEMPORAL_STATE");
        TemporalResetResult reset = await(runtime.resetTemporalState(job.cancellation.token()), job, deadline);
        if (!reset.successful()) {
            throw new Failure(ErrorCode.INTERNAL_ERROR, "Runtime temporal state reset failed.");
        }
    }

    void waitFrames(CoreJob job, Consumer<JobStage> progress, long deadline, int frames) throws Failure {
        progress.accept(JobStage.JOB_STAGE_WARMING_UP);
        probe.event(job.requestId, "WARMING_UP");
        await(runtime.waitRenderedFrames(frames, job.cancellation.token()), job, deadline);
    }

    VibrisRuntimeAdapter runtime() {
        return runtime;
    }

    CoreProbe probe() {
        return probe;
    }

    private ReloadResult activateSource(CoreJob job, Consumer<JobStage> progress, long deadline) throws Failure {
        if (job.sources.size() != 1) {
            throw new Failure(ErrorCode.SOURCE_ACTIVATION_FAILED, "Exactly one prepared source is required.");
        }
        SourceRegistry.Lease source = job.sources.getFirst();
        String requested = null;
        if (job.submission.hasRecipe() && job.submission.getRecipe().hasReloadAndCapture()) {
            requested = job.submission.getRecipe().getReloadAndCapture().getSourceUuid();
        } else if (job.submission.hasRecipe() && job.submission.getRecipe().hasCaptureDebugBundle()) {
            requested = job.submission.getRecipe().getCaptureDebugBundle().getSourceUuid();
        }
        if (requested != null && !source.uuid().equalsIgnoreCase(requested)) {
            throw new Failure(ErrorCode.INVALID_SOURCE_UUID,
                "Recipe source UUID does not match the prepared source.");
        }
        return activateSource(job, source, progress, deadline);
    }

    ReloadResult activateSource(CoreJob job, SourceRegistry.Lease source,
        Consumer<JobStage> progress, long deadline) throws Failure {
        progress.accept(JobStage.JOB_STAGE_ACTIVATING_SOURCE);
        probe.event(job.requestId, "ACTIVATING_SOURCE");
        SourceActivator.Activation activation;
        try {
            activation = activator.begin(source);
        } catch (SourceActivator.Failure failure) {
            throw new Failure(failure.code, failure.getMessage());
        }
        Failure original = null;
        ReloadResult successful = null;
        boolean activeStatePreserved = false;
        try {
            progress.accept(JobStage.JOB_STAGE_RELOADING_SHADERS);
            probe.event(job.requestId, "RELOADING_SHADERS");
            ReloadResult reload = await(runtime.reloadVibrisShaderpack(job.cancellation.token()), job, deadline);
            if (!reload.successful()) {
                activeStatePreserved = reload.activeStatePreserved();
                throw ShaderReloadFailure.create(shaderLogs, job, reload);
            }
            successful = reload;
            try {
                activator.commit(activation);
            } catch (SourceActivator.Failure failure) {
                throw new Failure(failure.code, failure.getMessage());
            }
        } catch (Failure failure) {
            original = failure;
        }
        if (original == null) return successful;
        boolean restored = activator.rollback(activation);
        if (restored && activation.previous() != null && !activeStatePreserved && !reloadPreviousSource()) {
            activator.markNotReady();
        }
        activator.fail(activation);
        throw original;
    }

    private JobResult capture(CoreJob job, Consumer<JobStage> progress, long deadline, ReloadResult reload)
        throws Failure {
        CaptureJobExecutor.Prepared prepared = captures.prepare(
            job, runtime.getResourceCatalog(), reload.diagnostics());
        if (prepared == null) {
            return JobResult.newBuilder().setKind(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE).build();
        }
        try (prepared) {
            CaptureResult result = capture(job, progress, deadline, prepared, prepared.plan());
            progress.accept(JobStage.JOB_STAGE_WRITING_ARTIFACTS);
            probe.event(job.requestId, "WRITING_ARTIFACTS");
            progress.accept(JobStage.JOB_STAGE_FINALIZING);
            probe.event(job.requestId, "FINALIZING");
            return captures.commit(job, prepared, result);
        } catch (java.io.IOException exception) {
            throw CaptureJobExecutor.failure(exception);
        }
    }

    CaptureResult capture(CoreJob job, Consumer<JobStage> progress, long deadline,
        CaptureJobExecutor.Prepared prepared, dev.vibris.api.CapturePlan plan) throws Failure {
        progress.accept(JobStage.JOB_STAGE_CAPTURING);
        probe.event(job.requestId, "CAPTURING");
        return awaitCapture(runtime.capture(plan, prepared.sink(), job.cancellation.token()), job, deadline);
    }

    CaptureResult awaitCapture(CompletionStage<CaptureResult> stage, CoreJob job, long deadline)
        throws Failure {
        return awaiter.capture(stage, job, deadline);
    }

    private boolean reloadPreviousSource() {
        try {
            ReloadResult result = runtime.reloadVibrisShaderpack(CancellationToken.none())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            return result.successful();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    <T> T await(CompletionStage<T> stage, CoreJob job, long deadline) throws Failure {
        return awaiter.await(stage, job, deadline);
    }

    static final class Failure extends Exception {
        final ErrorCode code;
        final List<ArtifactMetadata> artifacts;

        Failure(ErrorCode code, String message) {
            this(code, message, List.of());
        }

        Failure(ErrorCode code, String message, ArtifactMetadata artifact) {
            this(code, message, List.of(artifact));
        }

        private Failure(ErrorCode code, String message, List<ArtifactMetadata> artifacts) {
            super(message);
            this.code = code;
            this.artifacts = artifacts;
        }
    }
}