package dev.vibris.core

import dev.vibris.api.CaptureResult
import dev.vibris.protocol.v1.ErrorCode
import dev.vibris.protocol.v1.JobResult
import dev.vibris.protocol.v1.JobTimings
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class RuntimeAwaiter(private val probe: CoreProbe) {
    @Throws(RuntimeJobExecutor.Failure::class)
    fun capture(stage: CompletionStage<CaptureResult>, job: CoreJob, deadline: Long): CaptureResult =
        await(stage, job, deadline, ErrorCode.CAPTURE_FAILED)

    @Throws(RuntimeJobExecutor.Failure::class)
    fun <T> await(stage: CompletionStage<T>, job: CoreJob, deadline: Long): T =
        await(stage, job, deadline, ErrorCode.INTERNAL_ERROR)

    fun withTimings(job: CoreJob, result: JobResult, startedAtUnixMs: Long, startedNanos: Long): JobResult {
        val completedAtUnixMs = System.currentTimeMillis()
        val executionMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        val queueMs = TimeUnit.NANOSECONDS.toMillis(startedNanos - job.acceptedNanos)
        return result.toBuilder()
            .setTimings(
                JobTimings.newBuilder()
                    .setStartedAtUnixMs(startedAtUnixMs)
                    .setCompletedAtUnixMs(completedAtUnixMs)
                    .setQueueMs(queueMs)
                    .setExecutionMs(executionMs)
                    .setTotalMs(Math.addExact(queueMs, executionMs)),
            )
            .build()
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    private fun <T> await(
        stage: CompletionStage<T>,
        job: CoreJob,
        deadline: Long,
        operationFailureCode: ErrorCode,
    ): T {
        val future = stage.toCompletableFuture()
        try {
            if (job.cancellation.token().isCancellationRequested()) {
                throw CancellationException()
            }
            val remaining = if (deadline == Long.MAX_VALUE) Long.MAX_VALUE else deadline - System.nanoTime()
            if (remaining <= 0) {
                throw TimeoutException()
            }
            return if (remaining == Long.MAX_VALUE) future.get() else future.get(remaining, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            job.cancellation.cancel()
            awaitCancellation(future)
            probe.event(job.requestId, "SAFE_POINT_TIMEOUT")
            throw RuntimeJobExecutor.Failure(ErrorCode.EXECUTION_TIMEOUT, "Job execution timed out.")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            job.cancellation.cancel()
            throw RuntimeJobExecutor.Failure(ErrorCode.CANCELLED, "Job execution was interrupted.")
        } catch (_: CancellationException) {
            throw RuntimeJobExecutor.Failure(ErrorCode.CANCELLED, "Job execution was cancelled.")
        } catch (exception: ExecutionException) {
            throw operationFailure(exception.cause, job, operationFailureCode)
        } catch (exception: CompletionException) {
            throw operationFailure(exception.cause, job, operationFailureCode)
        }
    }

    private fun operationFailure(
        cause: Throwable?,
        job: CoreJob,
        code: ErrorCode,
    ): RuntimeJobExecutor.Failure {
        if (cause is CancellationException || job.cancellation.token().isCancellationRequested()) {
            return RuntimeJobExecutor.Failure(ErrorCode.CANCELLED, "Job execution was cancelled.")
        }
        return if (code == ErrorCode.CAPTURE_FAILED) {
            CaptureJobExecutor.failure(cause)
        } else {
            RuntimeJobExecutor.Failure(code, "Runtime adapter operation failed.")
        }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    private fun awaitCancellation(future: CompletableFuture<*>) {
        try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: CancellationException) {
        } catch (_: ExecutionException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeJobExecutor.Failure(
                ErrorCode.CANCELLED,
                "Interrupted while waiting for a runtime safe point.",
            )
        } catch (_: TimeoutException) {
            throw RuntimeJobExecutor.Failure(
                ErrorCode.INTERNAL_ERROR,
                "Runtime did not stop at a cancellation safe point.",
            )
        }
    }
}