package dev.vibris.core

import dev.vibris.api.CancellationToken
import dev.vibris.api.CaptureResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongFunction

class RenderedFrameClockTest {
    @Test
    fun capturesOnlyAtTheRegisteredAbsoluteTarget() {
        val clock = RenderedFrameClock()
        val captures = ArrayList<Long>()
        val result = clock.captureAtFrame(0, 3, CancellationToken.none()) { frameId ->
            captures.add(frameId)
            frameId
        }.toCompletableFuture()

        clock.renderedFrame()
        clock.renderedFrame()
        assertFalse(result.isDone)
        assertEquals(emptyList<Long>(), captures)

        clock.renderedFrame()
        assertEquals(3L, result.join())
        assertEquals(listOf(3L), captures)

        clock.renderedFrame()
        assertEquals(listOf(3L), captures)
        clock.close()
    }

    @Test
    fun rejectsMismatchedAnchorWithoutRegisteringCapture() {
        val clock = RenderedFrameClock()
        val captures = AtomicInteger()
        clock.renderedFrame()

        val failure = completionFailure(
            clock.captureAtFrame(0, 2, CancellationToken.none()) {
                captures.incrementAndGet()
            },
        )

        val mismatch = assertInstanceOf(RenderedFrameClock.AnchorMismatchException::class.java, failure)
        assertEquals(0L, mismatch.expectedAnchor)
        assertEquals(1L, mismatch.currentFrame)
        clock.renderedFrame()
        assertEquals(0, captures.get())
        clock.close()
    }

    @Test
    fun rejectsReachedTargetWithoutFallingBackToALaterFrame() {
        val clock = RenderedFrameClock()
        val captures = AtomicInteger()
        clock.renderedFrame()

        val failure = completionFailure(
            clock.captureAtFrame(1, 1, CancellationToken.none()) {
                captures.incrementAndGet()
            },
        )

        val missed = assertInstanceOf(RenderedFrameClock.TargetMissedException::class.java, failure)
        assertEquals(1L, missed.expectedAnchor)
        assertEquals(1L, missed.targetFrame)
        assertEquals(1L, missed.currentFrame)
        clock.renderedFrame()
        clock.renderedFrame()
        assertEquals(0, captures.get())
        clock.close()
    }

    @Test
    fun nextFrameCaptureRegisteredFromWaitCompletionDoesNotReenterCurrentFrame() {
        val clock = RenderedFrameClock()
        val capture = AtomicReference<CompletableFuture<Long>>()
        clock.waitRenderedFrames(1, CancellationToken.none()).thenRun {
            capture.set(
                clock.captureAtNextFrame(CancellationToken.none()) { frameId -> frameId }
                    .toCompletableFuture(),
            )
        }

        clock.renderedFrame()
        assertEquals(1L, clock.currentFrame())
        assertFalse(capture.get().isDone)

        clock.renderedFrame()
        assertEquals(2L, capture.get().join())
        clock.close()
    }

    @Test
    fun nextFrameCaptureRegisteredFromCaptureCompletionDoesNotReenterCurrentFrame() {
        val clock = RenderedFrameClock()
        val second = AtomicReference<CompletableFuture<Long>>()
        clock.captureAtFrame(0, 1, CancellationToken.none()) { frameId -> frameId }.thenRun {
            second.set(
                clock.captureAtNextFrame(CancellationToken.none()) { frameId -> frameId }
                    .toCompletableFuture(),
            )
        }

        clock.renderedFrame()
        assertEquals(1L, clock.currentFrame())
        assertFalse(second.get().isDone)

        clock.renderedFrame()
        assertEquals(2L, second.get().join())
        clock.close()
    }

    @Test
    fun zeroWarmupScheduleCapturesExactlyAtAnchorPlusOne() {
        val clock = RenderedFrameClock()
        val publications = ArrayList<String>()
        clock.renderedFrame()
        val scheduled = clock.scheduleDeterministicTemporalCapture(
            0,
            CancellationToken.none(),
            captureAction(),
        )
        scheduled.terminalFrame.thenRun { publications.add("terminal") }
        scheduled.capture.whenComplete { _, _ -> publications.add("capture") }

        assertEquals(1L, scheduled.anchorFrame)
        assertEquals(1L, scheduled.warmupEndFrame)
        assertEquals(2L, scheduled.captureFrame)
        assertFalse(scheduled.capture.toCompletableFuture().isDone)

        clock.renderedFrame()
        assertEquals(2L, scheduled.terminalFrame.toCompletableFuture().join())
        assertEquals(2L, scheduled.capture.toCompletableFuture().join().frameId)
        assertEquals(listOf("terminal", "capture"), publications)
        clock.close()
    }

    @Test
    fun deterministicScheduleAtomicallyAnchorsAndRegistersItsAbsoluteTarget() {
        val clock = RenderedFrameClock()
        clock.renderedFrame()
        clock.renderedFrame()
        val scheduled = clock.scheduleDeterministicTemporalCapture(
            3,
            CancellationToken.none(),
            captureAction(),
        )

        assertEquals(2L, scheduled.anchorFrame)
        assertEquals(5L, scheduled.warmupEndFrame)
        assertEquals(6L, scheduled.captureFrame)
        repeat(3) {
            clock.renderedFrame()
            assertFalse(scheduled.capture.toCompletableFuture().isDone)
        }

        clock.renderedFrame()
        val captured = scheduled.capture.toCompletableFuture().join()
        assertEquals(6L, captured.frameId)
        assertEquals(scheduled.captureFrame, captured.frameId)
        clock.close()
    }

    @Test
    fun scheduleAndRenderedFrameLinearizeWithoutProducingALateCapture() {
        repeat(100) {
            val clock = RenderedFrameClock()
            val start = CountDownLatch(1)
            val scheduled = AtomicReference<DeterministicTemporalCaptureScheduler.ScheduledCapture>()
            val scheduler = CompletableFuture.runAsync {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                scheduled.set(
                    clock.scheduleDeterministicTemporalCapture(
                        0,
                        CancellationToken.none(),
                        captureAction(),
                    ),
                )
            }
            val renderer = CompletableFuture.runAsync {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                clock.renderedFrame()
            }

            start.countDown()
            scheduler.get(5, TimeUnit.SECONDS)
            renderer.get(5, TimeUnit.SECONDS)
            val capture = scheduled.get()
            while (clock.currentFrame() < capture.captureFrame) {
                clock.renderedFrame()
            }

            assertEquals(capture.anchorFrame + 1, capture.captureFrame)
            assertEquals(capture.captureFrame, capture.capture.toCompletableFuture().join().frameId)
            clock.renderedFrame()
            assertEquals(capture.captureFrame, capture.capture.toCompletableFuture().join().frameId)
            clock.close()
        }
    }

    @Test
    fun pendingScheduleCancellationFreezesItsDetectionFrameBeforeCaptureFailure() {
        val clock = RenderedFrameClock()
        val cancellation = CancellationToken.source()
        val publications = ArrayList<String>()
        val scheduled = clock.scheduleDeterministicTemporalCapture(
            20,
            cancellation.token(),
            captureAction(),
        )
        scheduled.terminalFrame.thenRun { publications.add("terminal") }
        scheduled.capture.whenComplete { _, _ -> publications.add("capture") }

        cancellation.cancel()
        val detectedFrame = scheduled.terminalFrame.toCompletableFuture().get(5, TimeUnit.SECONDS)
        assertInstanceOf(CancellationException::class.java, completionFailure(scheduled.capture))
        assertEquals(listOf("terminal", "capture"), publications)

        repeat(3) { clock.renderedFrame() }
        assertEquals(detectedFrame, scheduled.terminalFrame.toCompletableFuture().join())
        assertTrue(detectedFrame < scheduled.warmupEndFrame)
        clock.close()
    }

    @Test
    fun closingPendingScheduleFreezesCurrentFrameBeforeCaptureFailure() {
        val clock = RenderedFrameClock()
        val publications = ArrayList<String>()
        repeat(2) { clock.renderedFrame() }
        val scheduled = clock.scheduleDeterministicTemporalCapture(
            10,
            CancellationToken.none(),
            captureAction(),
        )
        scheduled.terminalFrame.thenRun { publications.add("terminal") }
        scheduled.capture.whenComplete { _, _ -> publications.add("capture") }

        clock.close()

        assertEquals(2L, scheduled.terminalFrame.toCompletableFuture().join())
        assertInstanceOf(CancellationException::class.java, completionFailure(scheduled.capture))
        assertEquals(listOf("terminal", "capture"), publications)
    }

    @Test
    fun closingInFlightSchedulePublishesCloseFrameBeforeCaptureQuiesces() {
        val clock = RenderedFrameClock()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val publications = ArrayList<String>()
        val scheduled = clock.scheduleDeterministicTemporalCapture(
            0,
            CancellationToken.none(),
        ) { frameId ->
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            CaptureResult(frameId, emptyList())
        }
        scheduled.terminalFrame.thenRun { publications.add("terminal") }
        scheduled.capture.whenComplete { _, _ -> publications.add("capture") }
        val renderer = CompletableFuture.runAsync(clock::renderedFrame)

        assertTrue(started.await(5, TimeUnit.SECONDS))
        clock.close()
        assertEquals(1L, scheduled.terminalFrame.toCompletableFuture().join())
        assertFalse(scheduled.capture.toCompletableFuture().isDone)
        assertEquals(listOf("terminal"), publications)

        release.countDown()
        renderer.get(5, TimeUnit.SECONDS)
        assertInstanceOf(CancellationException::class.java, completionFailure(scheduled.capture))
        assertEquals(listOf("terminal", "capture"), publications)
    }

    @Test
    fun failingScheduledCapturePublishesTargetFrameBeforeFailure() {
        val clock = RenderedFrameClock()
        val publications = ArrayList<String>()
        val expected = IllegalStateException("scheduled capture failed")
        val scheduled = clock.scheduleDeterministicTemporalCapture(
            0,
            CancellationToken.none(),
        ) { throw expected }
        scheduled.terminalFrame.thenRun { publications.add("terminal") }
        scheduled.capture.whenComplete { _, _ -> publications.add("capture") }

        clock.renderedFrame()

        assertEquals(scheduled.captureFrame, scheduled.terminalFrame.toCompletableFuture().join())
        assertEquals(expected, completionFailure(scheduled.capture))
        assertEquals(listOf("terminal", "capture"), publications)
        clock.close()
    }

    @Test
    fun cancellationDetectedAfterScheduledActionUsesItsTerminalizationFrame() {
        val clock = RenderedFrameClock()
        val cancellation = CancellationToken.source()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scheduled = clock.scheduleDeterministicTemporalCapture(
            0,
            cancellation.token(),
        ) { frameId ->
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            CaptureResult(frameId, emptyList())
        }
        val renderer = CompletableFuture.runAsync(clock::renderedFrame)

        assertTrue(started.await(5, TimeUnit.SECONDS))
        clock.renderedFrame()
        clock.renderedFrame()
        cancellation.cancel()
        release.countDown()
        renderer.get(5, TimeUnit.SECONDS)

        assertEquals(3L, scheduled.terminalFrame.toCompletableFuture().join())
        assertInstanceOf(CancellationException::class.java, completionFailure(scheduled.capture))
        clock.close()
    }

    @Test
    fun terminalCallbackCanAdvanceClockBeforeScheduledCapturePublication() {
        val clock = RenderedFrameClock()
        val scheduled = clock.scheduleDeterministicTemporalCapture(
            0,
            CancellationToken.none(),
            captureAction(),
        )
        scheduled.terminalFrame.thenRun {
            clock.renderedFrame()
            clock.renderedFrame()
        }

        clock.renderedFrame()

        assertEquals(1L, scheduled.terminalFrame.toCompletableFuture().join())
        assertEquals(1L, scheduled.capture.toCompletableFuture().join().frameId)
        assertEquals(3L, clock.currentFrame())
        clock.close()
    }

    @Test
    fun cancellationBeforeTargetPreventsCapture() {
        val clock = RenderedFrameClock()
        val cancellation = CancellationToken.source()
        val captures = AtomicInteger()
        val result = clock.captureAtFrame(0, 2, cancellation.token()) {
            captures.incrementAndGet()
        }.toCompletableFuture()

        cancellation.cancel()
        clock.renderedFrame()
        assertThrows(CancellationException::class.java, result::join)
        clock.renderedFrame()
        assertEquals(0, captures.get())
        clock.close()
    }

    @Test
    fun directlyCancelledFutureDoesNotRunCapture() {
        val clock = RenderedFrameClock()
        val captures = AtomicInteger()
        val result = clock.captureAtFrame(0, 1, CancellationToken.none()) {
            captures.incrementAndGet()
        }.toCompletableFuture()

        assertTrue(result.cancel(false))
        clock.renderedFrame()

        assertTrue(result.isCancelled)
        assertEquals(0, captures.get())
        clock.close()
    }

    @Test
    fun cancellationDuringCaptureCannotPublishSuccess() {
        val clock = RenderedFrameClock()
        val cancellation = CancellationToken.source()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = clock.captureAtFrame(0, 1, cancellation.token()) {
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            42
        }.toCompletableFuture()
        val renderer = CompletableFuture.runAsync(clock::renderedFrame)

        assertTrue(started.await(5, TimeUnit.SECONDS))
        cancellation.cancel()
        release.countDown()
        renderer.get(5, TimeUnit.SECONDS)

        assertThrows(CancellationException::class.java, result::join)
        clock.close()
    }

    @Test
    fun cancellationAfterActionBeforePublicationCannotPublishSuccess() {
        val clock = RenderedFrameClock()
        val cancellation = CancellationToken.source()
        val firstFinished = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val first = clock.captureAtFrame(0, 1, cancellation.token()) {
            firstFinished.countDown()
            41
        }.toCompletableFuture()
        val second = clock.captureAtFrame(0, 1, CancellationToken.none()) {
            secondStarted.countDown()
            assertTrue(releaseSecond.await(5, TimeUnit.SECONDS))
            42
        }.toCompletableFuture()
        val renderer = CompletableFuture.runAsync(clock::renderedFrame)

        assertTrue(firstFinished.await(5, TimeUnit.SECONDS))
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
        assertFalse(first.isDone)
        cancellation.cancel()
        releaseSecond.countDown()
        renderer.get(5, TimeUnit.SECONDS)

        assertThrows(CancellationException::class.java, first::join)
        assertEquals(42, second.join())
        clock.close()
    }

    @Test
    fun closeBeforeTargetPreventsCaptureAndIsIdempotent() {
        val clock = RenderedFrameClock()
        val captures = AtomicInteger()
        val result = clock.captureAtFrame(0, 2, CancellationToken.none()) {
            captures.incrementAndGet()
        }.toCompletableFuture()

        clock.close()
        clock.close()
        clock.renderedFrame()
        clock.renderedFrame()

        assertThrows(CancellationException::class.java, result::join)
        assertEquals(0, captures.get())
        assertEquals(0L, clock.currentFrame())
    }

    @Test
    fun closeDuringCaptureCannotPublishSuccess() {
        val clock = RenderedFrameClock()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = clock.captureAtFrame(0, 1, CancellationToken.none()) {
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            42
        }.toCompletableFuture()
        val renderer = CompletableFuture.runAsync(clock::renderedFrame)

        assertTrue(started.await(5, TimeUnit.SECONDS))
        clock.close()
        assertFalse(result.isDone)
        release.countDown()
        renderer.get(5, TimeUnit.SECONDS)

        assertThrows(CancellationException::class.java, result::join)
    }

    @Test
    fun captureFailureIsReportedOnceAndNeverRetried() {
        val clock = RenderedFrameClock()
        val attempts = AtomicInteger()
        val expected = IllegalStateException("capture failed")
        val result = clock.captureAtFrame(0, 1, CancellationToken.none()) {
            attempts.incrementAndGet()
            throw expected
        }.toCompletableFuture()

        clock.renderedFrame()
        assertEquals(expected, completionFailure(result))
        clock.renderedFrame()
        assertEquals(1, attempts.get())
        clock.close()
    }

    @Test
    fun multipleTargetsExecuteOnceAtTheirExactFrames() {
        val clock = RenderedFrameClock()
        val order = ArrayList<String>()
        val first = clock.captureAtFrame(0, 2, CancellationToken.none()) { frameId ->
            order.add("first:$frameId")
            frameId
        }.toCompletableFuture()
        val second = clock.captureAtFrame(0, 2, CancellationToken.none()) { frameId ->
            order.add("second:$frameId")
            frameId
        }.toCompletableFuture()
        val later = clock.captureAtFrame(0, 3, CancellationToken.none()) { frameId ->
            order.add("later:$frameId")
            frameId
        }.toCompletableFuture()

        clock.renderedFrame()
        assertTrue(order.isEmpty())
        clock.renderedFrame()
        assertEquals(2L, first.join())
        assertEquals(2L, second.join())
        assertFalse(later.isDone)
        assertEquals(listOf("first:2", "second:2"), order)
        clock.renderedFrame()
        assertEquals(3L, later.join())
        assertEquals(listOf("first:2", "second:2", "later:3"), order)
        clock.close()
    }

    private fun completionFailure(stage: CompletionStage<*>): Throwable = try {
        stage.toCompletableFuture().join()
        throw AssertionError("Expected stage to fail")
    } catch (failure: CompletionException) {
        failure.cause ?: failure
    }

    private fun captureAction(): LongFunction<CaptureResult> = LongFunction { frameId ->
        CaptureResult(frameId, emptyList())
    }
}