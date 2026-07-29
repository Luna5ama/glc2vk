package dev.vibris.core;

import dev.vibris.api.CancellationToken;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.SceneContext;
import dev.vibris.api.VibrisRuntimeAdapter;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobCompleted;
import dev.vibris.protocol.v1.JobResult;
import dev.vibris.protocol.v1.JobResultKind;
import dev.vibris.protocol.v1.JobStage;

import java.time.Duration;
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

    RuntimeJobExecutor(VibrisRuntimeAdapter runtime, CoreProbe probe) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.probe = probe;
    }

    TerminalResult execute(CoreJob job, Consumer<JobStage> progress) throws Failure {
        long deadline = deadline(job);
        CancellationToken cancellation = job.cancellation.token();
        probe.event(job.requestId, "ENSURING_WORLD");
        ContextApplyResult context = await(
            runtime.ensureWorldAndContext(toApi(job.submission.getContext()), cancellation), job, deadline);
        if (!context.successful()) throw new Failure(ErrorCode.WORLD_LOAD_FAILED, context.message());
        probe.contextApplied(job.requestId, job.workspaceId, toProtocol(context.context()));

        int frames = waitFrames(job);
        progress.accept(JobStage.JOB_STAGE_WARMING_UP);
        probe.event(job.requestId, "WARMING_UP");
        await(runtime.waitRenderedFrames(frames, cancellation), job, deadline);

        JobResult result = JobResult.newBuilder()
            .setKind(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE)
            .build();
        return TerminalResult.completed(JobCompleted.newBuilder()
            .setRequestId(job.requestId)
            .setResult(result)
            .build());
    }

    private <T> T await(CompletionStage<T> stage, CoreJob job, long deadline) throws Failure {
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
            throw new Failure(ErrorCode.INTERNAL_ERROR, "Runtime adapter operation failed.");
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

    private static long deadline(CoreJob job) {
        long executionMillis = job.submission.getTimeouts().getExecutionTimeoutMs();
        long totalMillis = job.submission.getTimeouts().getTotalTimeoutMs();
        long now = System.nanoTime();
        long execution = addDuration(now, executionMillis);
        long total = addDuration(job.acceptedNanos, totalMillis);
        return Math.min(execution, total);
    }

    private static long addDuration(long start, long milliseconds) {
        if (milliseconds == 0) return Long.MAX_VALUE;
        long nanos;
        try {
            nanos = Duration.ofMillis(milliseconds).toNanos();
            return Math.addExact(start, nanos);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static int waitFrames(CoreJob job) throws Failure {
        long frames = 0;
        for (var action : job.submission.getActions().getActionsList()) {
            if (action.hasWaitFrames()) frames += action.getWaitFrames().getFrameCount();
            if (frames > Integer.MAX_VALUE) {
                throw new Failure(ErrorCode.INTERNAL_ERROR, "Requested frame count is too large.");
            }
        }
        return (int) frames;
    }

    private static SceneContext toApi(dev.vibris.protocol.v1.SceneContext source) {
        dev.vibris.protocol.v1.Resolution resolution = source.getResolution();
        return new SceneContext(
            source.getSaveId(),
            source.getDimensionId(),
            source.getTimePresetId(),
            source.getWeatherPresetId(),
            source.getCameraPresetId(),
            source.getFov(),
            resolution.getWidth() == 0
                ? SceneContext.Resolution.unspecified()
                : new SceneContext.Resolution(resolution.getWidth(), resolution.getHeight()),
            source.getSettingsPresetId());
    }

    private static dev.vibris.protocol.v1.SceneContext toProtocol(SceneContext source) {
        return dev.vibris.protocol.v1.SceneContext.newBuilder()
            .setSaveId(source.saveId())
            .setDimensionId(source.dimensionId())
            .setTimePresetId(source.timePresetId())
            .setWeatherPresetId(source.weatherPresetId())
            .setCameraPresetId(source.cameraPresetId())
            .setFov(source.fov())
            .setSettingsPresetId(source.settingsPresetId())
            .setResolution(dev.vibris.protocol.v1.Resolution.newBuilder()
                .setWidth(source.resolution().width())
                .setHeight(source.resolution().height()))
            .build();
    }

    static final class Failure extends Exception {
        final ErrorCode code;

        Failure(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }
    }
}