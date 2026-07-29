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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

final class RuntimeJobExecutor {
    private final VibrisRuntimeAdapter runtime;
    private final CoreProbe probe;
    private final SourceActivator activator;
    private final ShaderLogSink shaderLogs;
    private final CaptureJobExecutor captures;

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
    }

    TerminalResult execute(CoreJob job, Consumer<JobStage> progress) throws Failure {
        long deadline = RuntimeJobContext.deadline(job);
        CancellationToken cancellation = job.cancellation.token();
        ReloadResult reload = activateSource(job, progress, deadline);
        progress.accept(JobStage.JOB_STAGE_LOADING_WORLD);
        probe.event(job.requestId, "ENSURING_WORLD");
        progress.accept(JobStage.JOB_STAGE_APPLYING_CONTEXT);
        ContextApplyResult context = await(
            runtime.ensureWorldAndContext(RuntimeJobContext.toApi(job.submission.getContext()), cancellation),
            job, deadline);
        if (!context.successful()) throw new Failure(ErrorCode.WORLD_LOAD_FAILED, context.message());
        probe.contextApplied(job.requestId, job.workspaceId, RuntimeJobContext.toProtocol(context.context()));

        progress.accept(JobStage.JOB_STAGE_RESETTING_TEMPORAL_STATE);
        probe.event(job.requestId, "RESETTING_TEMPORAL_STATE");
        TemporalResetResult reset = await(runtime.resetTemporalState(cancellation), job, deadline);
        if (!reset.successful()) {
            throw new Failure(ErrorCode.INTERNAL_ERROR, "Runtime temporal state reset failed.");
        }

        int frames = captures.waitFrames(job);
        progress.accept(JobStage.JOB_STAGE_WARMING_UP);
        probe.event(job.requestId, "WARMING_UP");
        await(runtime.waitRenderedFrames(frames, cancellation), job, deadline);

        JobResult result = capture(job, progress, deadline, reload);
        return TerminalResult.completed(JobCompleted.newBuilder()
            .setRequestId(job.requestId)
            .setResult(result)
            .build());
    }

    private ReloadResult activateSource(CoreJob job, Consumer<JobStage> progress, long deadline) throws Failure {
        if (job.sources.size() != 1) {
            throw new Failure(ErrorCode.SOURCE_ACTIVATION_FAILED, "Exactly one prepared source is required.");
        }
        progress.accept(JobStage.JOB_STAGE_ACTIVATING_SOURCE);
        probe.event(job.requestId, "ACTIVATING_SOURCE");
        SourceActivator.Activation activation;
        try {
            activation = activator.begin(job.sources.getFirst());
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
            progress.accept(JobStage.JOB_STAGE_CAPTURING);
            probe.event(job.requestId, "CAPTURING");
            CaptureResult result = awaitCapture(
                runtime.capture(prepared.plan(), prepared.sink(), job.cancellation.token()),
                job, deadline);
            progress.accept(JobStage.JOB_STAGE_WRITING_ARTIFACTS);
            probe.event(job.requestId, "WRITING_ARTIFACTS");
            progress.accept(JobStage.JOB_STAGE_FINALIZING);
            probe.event(job.requestId, "FINALIZING");
            return captures.commit(job, prepared, result);
        } catch (java.io.IOException exception) {
            throw CaptureJobExecutor.failure(exception);
        }
    }

    private CaptureResult awaitCapture(CompletionStage<CaptureResult> stage, CoreJob job, long deadline)
        throws Failure {
        return await(stage, job, deadline, ErrorCode.CAPTURE_FAILED);
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

    private <T> T await(CompletionStage<T> stage, CoreJob job, long deadline) throws Failure {
        return await(stage, job, deadline, ErrorCode.INTERNAL_ERROR);
    }

    private <T> T await(CompletionStage<T> stage, CoreJob job, long deadline, ErrorCode operationFailure)
        throws Failure {
        CompletableFuture<T> future = stage.toCompletableFuture();
        try {
            if (job.cancellation.token().isCancellationRequested()) throw new CancellationException();
            long remaining = deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return remaining == Long.MAX_VALUE
                ? future.get()
                : future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            job.cancellation.cancel();
            awaitCancellation(future);
            probe.event(job.requestId, "SAFE_POINT_TIMEOUT");
            throw new Failure(ErrorCode.EXECUTION_TIMEOUT, "Job execution timed out.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            job.cancellation.cancel();
            throw new Failure(ErrorCode.CANCELLED, "Job execution was interrupted.");
        } catch (CancellationException exception) {
            throw new Failure(ErrorCode.CANCELLED, "Job execution was cancelled.");
        } catch (ExecutionException | CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof CancellationException || job.cancellation.token().isCancellationRequested()) {
                throw new Failure(ErrorCode.CANCELLED, "Job execution was cancelled.");
            }
            if (operationFailure == ErrorCode.CAPTURE_FAILED) throw CaptureJobExecutor.failure(cause);
            throw new Failure(operationFailure, operationFailure == ErrorCode.CAPTURE_FAILED
                ? "Runtime capture failed." : "Runtime adapter operation failed.");
        }
    }

    private static void awaitCancellation(CompletableFuture<?> future) throws Failure {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (CancellationException | ExecutionException ignored) {
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Failure(ErrorCode.CANCELLED, "Interrupted while waiting for a runtime safe point.");
        } catch (TimeoutException exception) {
            throw new Failure(ErrorCode.INTERNAL_ERROR, "Runtime did not stop at a cancellation safe point.");
        }
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