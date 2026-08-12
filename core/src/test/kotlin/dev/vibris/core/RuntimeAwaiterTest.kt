package dev.vibris.core

import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RuntimeAwaiterTest {
    @Test
    fun genericCancellationWaitsForPendingStageToQuiesce() {
        assertCancellationWaitsForPendingStage(capture = false)
    }

    @Test
    fun captureCancellationWaitsForPendingStageToQuiesce() {
        assertCancellationWaitsForPendingStage(capture = true)
    }

    private fun assertCancellationWaitsForPendingStage(capture: Boolean) {
        val awaiter = RuntimeAwaiter(CoreProbe())
        val job = job(if (capture) "capture-cancellation" else "generic-cancellation")
        val pending = CompletableFuture<String>()
        val started = CountDownLatch(1)
        job.cancellation.cancel()

        val execution = CompletableFuture.supplyAsync {
            started.countDown()
            assertThrows(RuntimeJobExecutor.Failure::class.java) {
                if (capture) {
                    awaiter.capture(pending, job, Long.MAX_VALUE)
                } else {
                    awaiter.await(pending, job, Long.MAX_VALUE)
                }
            }
        }

        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertThrows(TimeoutException::class.java) {
            execution.get(100, TimeUnit.MILLISECONDS)
        }
        assertFalse(execution.isDone)

        pending.complete("quiesced")
        val failure = execution.get(5, TimeUnit.SECONDS)
        assertEquals(ErrorCode.ERROR_CODE_CANCELLED, failure.code)
    }

    private fun job(requestId: String): CoreJob = CoreJob(
        JobSpec.newBuilder().setJobId(requestId).build(),
        requestId,
        "11111111-1111-4111-8111-111111111111",
        "message",
        null,
    )
}