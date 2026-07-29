package dev.vibris.core;

import dev.vibris.api.CaptureResult;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobResult;
import dev.vibris.protocol.v1.JobTimings;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class RuntimeAwaiter {
    private final CoreProbe probe;

    RuntimeAwaiter(CoreProbe probe) {
        this.probe = probe;
    }

    CaptureResult capture(CompletionStage<CaptureResult> stage, CoreJob job, long deadline)
        throws RuntimeJobExecutor.Failure {
        return await(stage, job, deadline, ErrorCode.CAPTURE_FAILED);
    }

    <T> T await(CompletionStage<T> stage, CoreJob job, long deadline) throws RuntimeJobExecutor.Failure {
        return await(stage, job, deadline, ErrorCode.INTERNAL_ERROR);
    }

    JobResult withTimings(CoreJob job, JobResult result, long startedAtUnixMs, long startedNanos) {
        long completedAtUnixMs = System.currentTimeMillis();
        long executionMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        long queueMs = TimeUnit.NANOSECONDS.toMillis(startedNanos - job.acceptedNanos);
        return result.toBuilder().setTimings(JobTimings.newBuilder()
            .setStartedAtUnixMs(startedAtUnixMs)
            .setCompletedAtUnixMs(completedAtUnixMs)
            .setQueueMs(queueMs)
            .setExecutionMs(executionMs)
            .setTotalMs(Math.addExact(queueMs, executionMs))).build();
    }

    private <T> T await(CompletionStage<T> stage, CoreJob job, long deadline, ErrorCode operationFailure)
        throws RuntimeJobExecutor.Failure {
        CompletableFuture<T> future = stage.toCompletableFuture();
        try {
            if (job.cancellation.token().isCancellationRequested()) throw new CancellationException();
            long remaining = deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return remaining == Long.MAX_VALUE ? future.get() : future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            job.cancellation.cancel();
            awaitCancellation(future);
            probe.event(job.requestId, "SAFE_POINT_TIMEOUT");
            throw new RuntimeJobExecutor.Failure(ErrorCode.EXECUTION_TIMEOUT, "Job execution timed out.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            job.cancellation.cancel();
            throw new RuntimeJobExecutor.Failure(ErrorCode.CANCELLED, "Job execution was interrupted.");
        } catch (CancellationException exception) {
            throw new RuntimeJobExecutor.Failure(ErrorCode.CANCELLED, "Job execution was cancelled.");
        } catch (ExecutionException | CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof CancellationException || job.cancellation.token().isCancellationRequested()) {
                throw new RuntimeJobExecutor.Failure(ErrorCode.CANCELLED, "Job execution was cancelled.");
            }
            if (operationFailure == ErrorCode.CAPTURE_FAILED) throw CaptureJobExecutor.failure(cause);
            throw new RuntimeJobExecutor.Failure(operationFailure, "Runtime adapter operation failed.");
        }
    }

    private static void awaitCancellation(CompletableFuture<?> future) throws RuntimeJobExecutor.Failure {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (CancellationException | ExecutionException ignored) {
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeJobExecutor.Failure(
                ErrorCode.CANCELLED, "Interrupted while waiting for a runtime safe point.");
        } catch (TimeoutException exception) {
            throw new RuntimeJobExecutor.Failure(
                ErrorCode.INTERNAL_ERROR, "Runtime did not stop at a cancellation safe point.");
        }
    }
}