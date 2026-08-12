package dev.vibris.core

import dev.vibris.api.CancellationToken
import dev.vibris.api.CaptureResult
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.function.LongFunction

class RenderedFrameClock : AutoCloseable {
    private val waiters = ArrayList<Waiter>()
    private val captures = ArrayList<CaptureTask<*>>()
    private val inFlightCaptures = HashSet<CaptureTask<*>>()
    private var renderedFrames = 0L
    private var closed = false

    fun waitRenderedFrames(count: Int, cancellation: CancellationToken): CompletionStage<Long> {
        require(count >= 0) { "Frame count must not be negative" }
        val result = CompletableFuture<Long>()
        var failure: Throwable? = null
        var waiter: Waiter? = null
        var completedFrame: Long? = null
        synchronized(this) {
            when {
                closed -> failure = clockClosed()
                cancellation.isCancellationRequested() -> failure = frameWaitCancelled()
                count == 0 -> completedFrame = renderedFrames
                else -> {
                    val pending = Waiter(
                        Math.addExact(renderedFrames, count.toLong()),
                        cancellation,
                        result,
                    )
                    waiters.add(pending)
                    waiter = pending
                }
            }
        }
        failure?.let(result::completeExceptionally)
        completedFrame?.let(result::complete)
        waiter?.let(::pollCancellation)
        return result
    }

    fun <T> captureAtNextFrame(
        cancellation: CancellationToken,
        action: LongFunction<T>,
    ): CompletionStage<T> = registerCapture(null, null, cancellation, action)

    fun <T> captureAtFrame(
        expectedAnchor: Long,
        targetFrame: Long,
        cancellation: CancellationToken,
        action: LongFunction<T>,
    ): CompletionStage<T> = registerCapture(expectedAnchor, targetFrame, cancellation, action)

    fun scheduleDeterministicTemporalCapture(
        warmupFrames: Int,
        cancellation: CancellationToken,
        action: LongFunction<CaptureResult>,
    ): DeterministicTemporalCaptureScheduler.ScheduledCapture {
        require(warmupFrames >= 0) { "Warmup frame count must not be negative" }
        val result = CompletableFuture<CaptureResult>()
        val terminalFrame = CompletableFuture<Long>()
        val scheduled: DeterministicTemporalCaptureScheduler.ScheduledCapture
        var capture: CaptureTask<CaptureResult>? = null
        var publication: CapturePublication = CapturePublication.None
        synchronized(this) {
            val anchorFrame = renderedFrames
            val warmupEndFrame = Math.addExact(anchorFrame, warmupFrames.toLong())
            val captureFrame = Math.incrementExact(warmupEndFrame)
            when {
                closed -> {
                    publication = CapturePublication.Failure(
                        terminalFrame,
                        anchorFrame,
                        result,
                        clockClosed(),
                    )
                }
                cancellation.isCancellationRequested() -> {
                    publication = CapturePublication.Failure(
                        terminalFrame,
                        anchorFrame,
                        result,
                        captureCancelled(),
                    )
                }
                else -> {
                    val pending = CaptureTask(
                        anchorFrame,
                        captureFrame,
                        cancellation,
                        action,
                        result,
                        terminalFrame,
                    )
                    captures.add(pending)
                    capture = pending
                }
            }
            scheduled = DeterministicTemporalCaptureScheduler.ScheduledCapture(
                warmupFrames,
                anchorFrame,
                warmupEndFrame,
                captureFrame,
                terminalFrame.minimalCompletionStage(),
                result.minimalCompletionStage(),
            )
        }
        publication.publish()
        capture?.let(::pollCaptureCancellation)
        return scheduled
    }

    fun renderedFrame() {
        val completedWaiters = ArrayList<Waiter>()
        val failedWaiters = ArrayList<Pair<Waiter, Throwable>>()
        val readyCaptures = ArrayList<CaptureTask<*>>()
        val capturePublications = ArrayList<CapturePublication>()
        val frameId: Long
        synchronized(this) {
            if (closed) {
                return
            }
            frameId = Math.incrementExact(renderedFrames)
            renderedFrames = frameId

            val waiterIterator = waiters.iterator()
            while (waiterIterator.hasNext()) {
                val waiter = waiterIterator.next()
                when {
                    waiter.result.isDone -> waiterIterator.remove()
                    waiter.cancellation.isCancellationRequested() -> {
                        waiterIterator.remove()
                        failedWaiters.add(waiter to frameWaitCancelled())
                    }
                    waiter.target <= frameId -> {
                        waiterIterator.remove()
                        completedWaiters.add(waiter)
                    }
                }
            }

            val captureIterator = captures.iterator()
            while (captureIterator.hasNext()) {
                val capture = captureIterator.next()
                when {
                    capture.result.isDone -> {
                        captureIterator.remove()
                        capture.state = CaptureState.TERMINAL
                        capturePublications.add(
                            CapturePublication.TerminalOnly(capture.terminalFrame, frameId),
                        )
                    }
                    capture.cancellation.isCancellationRequested() -> {
                        captureIterator.remove()
                        capture.state = CaptureState.TERMINAL
                        capturePublications.add(
                            CapturePublication.Failure(
                                capture.terminalFrame,
                                frameId,
                                capture.result,
                                captureCancelled(),
                            ),
                        )
                    }
                    capture.targetFrame < frameId -> {
                        captureIterator.remove()
                        capture.state = CaptureState.TERMINAL
                        capturePublications.add(
                            CapturePublication.Failure(
                                capture.terminalFrame,
                                frameId,
                                capture.result,
                                TargetMissedException(
                                    capture.expectedAnchor,
                                    capture.targetFrame,
                                    frameId,
                                ),
                            ),
                        )
                    }
                    capture.targetFrame == frameId -> {
                        captureIterator.remove()
                        capture.state = CaptureState.CLAIMED
                        inFlightCaptures.add(capture)
                        readyCaptures.add(capture)
                    }
                }
            }
        }

        val captureCompletions = readyCaptures.map { capture -> executeCapture(capture, frameId) }
        captureCompletions.forEach(Runnable::run)
        capturePublications.forEach(CapturePublication::publish)
        completedWaiters.forEach { waiter -> waiter.result.complete(frameId) }
        failedWaiters.forEach { (waiter, failure) -> waiter.result.completeExceptionally(failure) }
    }

    @Synchronized
    fun currentFrame(): Long = renderedFrames

    override fun close() {
        val pendingWaiters: List<Waiter>
        val capturePublications = ArrayList<CapturePublication>()
        synchronized(this) {
            if (closed) {
                return
            }
            closed = true
            pendingWaiters = java.util.List.copyOf(waiters)
            waiters.clear()
            val pendingCaptures = java.util.List.copyOf(captures)
            captures.clear()
            pendingCaptures.forEach { capture ->
                capture.state = CaptureState.TERMINAL
                capturePublications.add(
                    CapturePublication.Failure(
                        capture.terminalFrame,
                        renderedFrames,
                        capture.result,
                        clockClosed(),
                    ),
                )
            }
            inFlightCaptures.forEach { capture ->
                if (capture.abort == null) {
                    val failure = clockClosed()
                    capture.abort = TerminalAbort(renderedFrames, failure)
                    capturePublications.add(
                        CapturePublication.TerminalOnly(capture.terminalFrame, renderedFrames),
                    )
                }
            }
        }
        pendingWaiters.forEach { waiter -> waiter.result.completeExceptionally(clockClosed()) }
        capturePublications.forEach(CapturePublication::publish)
    }

    private fun <T> registerCapture(
        expectedAnchor: Long?,
        requestedTarget: Long?,
        cancellation: CancellationToken,
        action: LongFunction<T>,
    ): CompletionStage<T> {
        val result = CompletableFuture<T>()
        var capture: CaptureTask<T>? = null
        var failure: Throwable? = null
        synchronized(this) {
            val currentFrame = renderedFrames
            when {
                closed -> failure = clockClosed()
                cancellation.isCancellationRequested() -> failure = captureCancelled()
                expectedAnchor != null && expectedAnchor != currentFrame -> {
                    failure = AnchorMismatchException(expectedAnchor, currentFrame)
                }
                else -> {
                    val targetFrame = try {
                        requestedTarget ?: Math.incrementExact(currentFrame)
                    } catch (overflow: ArithmeticException) {
                        failure = IllegalStateException(
                            "Vibris frame counter cannot advance beyond $currentFrame.",
                            overflow,
                        )
                        currentFrame
                    }
                    if (failure == null && targetFrame <= currentFrame) {
                        failure = TargetMissedException(expectedAnchor ?: currentFrame, targetFrame, currentFrame)
                    }
                    if (failure == null) {
                        val pending = CaptureTask(
                            expectedAnchor ?: currentFrame,
                            targetFrame,
                            cancellation,
                            action,
                            result,
                            null,
                        )
                        captures.add(pending)
                        capture = pending
                    }
                }
            }
        }
        failure?.let(result::completeExceptionally)
        capture?.let(::pollCaptureCancellation)
        return result
    }

    private fun pollCancellation(waiter: Waiter) {
        CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS).execute {
            var failure: Throwable? = null
            var reschedule = false
            synchronized(this) {
                when {
                    waiter.result.isDone -> waiters.remove(waiter)
                    !waiters.contains(waiter) -> Unit
                    closed -> {
                        waiters.remove(waiter)
                        failure = clockClosed()
                    }
                    waiter.cancellation.isCancellationRequested() -> {
                        waiters.remove(waiter)
                        failure = frameWaitCancelled()
                    }
                    else -> reschedule = true
                }
            }
            failure?.let(waiter.result::completeExceptionally)
            if (reschedule) {
                pollCancellation(waiter)
            }
        }
    }

    private fun pollCaptureCancellation(capture: CaptureTask<*>) {
        CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS).execute {
            var publication: CapturePublication = CapturePublication.None
            var reschedule = false
            synchronized(this) {
                when {
                    capture.state != CaptureState.PENDING -> Unit
                    capture.result.isDone -> {
                        captures.remove(capture)
                        capture.state = CaptureState.TERMINAL
                        publication = CapturePublication.TerminalOnly(
                            capture.terminalFrame,
                            renderedFrames,
                        )
                    }
                    closed -> {
                        captures.remove(capture)
                        capture.state = CaptureState.TERMINAL
                        publication = CapturePublication.Failure(
                            capture.terminalFrame,
                            renderedFrames,
                            capture.result,
                            clockClosed(),
                        )
                    }
                    capture.cancellation.isCancellationRequested() -> {
                        captures.remove(capture)
                        capture.state = CaptureState.TERMINAL
                        publication = CapturePublication.Failure(
                            capture.terminalFrame,
                            renderedFrames,
                            capture.result,
                            captureCancelled(),
                        )
                    }
                    else -> reschedule = true
                }
            }
            publication.publish()
            if (reschedule) {
                pollCaptureCancellation(capture)
            }
        }
    }

    private fun <T> executeCapture(capture: CaptureTask<T>, frameId: Long): Runnable {
        val beforeFailure = synchronized(this) {
            capture.abort?.failure
                ?: (if (closed) clockClosed() else null)
                ?: (if (capture.cancellation.isCancellationRequested()) captureCancelled() else null)
        }
        if (beforeFailure != null || capture.result.isDone) {
            return finishCapture(capture, null, beforeFailure)
        }

        val outcome = try {
            CaptureOutcome.Success(capture.action.apply(frameId))
        } catch (failure: Throwable) {
            CaptureOutcome.Failure(failure)
        }
        return finishCapture(capture, outcome, null)
    }

    private fun <T> finishCapture(
        capture: CaptureTask<T>,
        outcome: CaptureOutcome<T>?,
        initialFailure: Throwable?,
    ): Runnable = Runnable {
        val publication = synchronized(this) {
            if (capture.state != CaptureState.CLAIMED) {
                return@synchronized CapturePublication.None
            }
            capture.state = CaptureState.TERMINAL
            inFlightCaptures.remove(capture)
            if (capture.result.isDone) {
                return@synchronized CapturePublication.None
            }
            val abort = capture.abort
            val terminalFailure = when {
                abort != null -> TerminalFailure(abort.frame, abort.failure)
                initialFailure != null -> TerminalFailure(renderedFrames, initialFailure)
                closed -> TerminalFailure(renderedFrames, clockClosed())
                capture.cancellation.isCancellationRequested() -> {
                    TerminalFailure(renderedFrames, captureCancelled())
                }
                outcome is CaptureOutcome.Failure -> {
                    TerminalFailure(capture.targetFrame, outcome.failure)
                }
                else -> null
            }
            if (terminalFailure != null) {
                return@synchronized CapturePublication.Failure(
                    capture.terminalFrame,
                    terminalFailure.frame,
                    capture.result,
                    terminalFailure.failure,
                )
            }
            val value = (outcome as CaptureOutcome.Success<T>).value
            CapturePublication.Success(
                capture.terminalFrame,
                capture.targetFrame,
                capture.result,
                value,
            )
        }
        publication.publish()
    }

    class AnchorMismatchException(
        val expectedAnchor: Long,
        val currentFrame: Long,
    ) : IllegalStateException(
        "Vibris capture anchor $expectedAnchor does not match current frame $currentFrame.",
    )

    class TargetMissedException(
        val expectedAnchor: Long,
        val targetFrame: Long,
        val currentFrame: Long,
    ) : IllegalStateException(
        "Vibris capture target $targetFrame for anchor $expectedAnchor was already reached at frame $currentFrame.",
    )

    @JvmRecord
    private data class Waiter(
        val target: Long,
        val cancellation: CancellationToken,
        val result: CompletableFuture<Long>,
    )

    private class CaptureTask<T>(
        val expectedAnchor: Long,
        val targetFrame: Long,
        val cancellation: CancellationToken,
        val action: LongFunction<T>,
        val result: CompletableFuture<T>,
        val terminalFrame: CompletableFuture<Long>?,
        var state: CaptureState = CaptureState.PENDING,
        var abort: TerminalAbort? = null,
    )

    private data class TerminalAbort(
        val frame: Long,
        val failure: Throwable,
    )

    private data class TerminalFailure(
        val frame: Long,
        val failure: Throwable,
    )

    private enum class CaptureState {
        PENDING,
        CLAIMED,
        TERMINAL,
    }

    private sealed interface CaptureOutcome<out T> {
        data class Success<T>(val value: T) : CaptureOutcome<T>

        data class Failure(val failure: Throwable) : CaptureOutcome<Nothing>
    }

    private sealed interface CapturePublication {
        fun publish()

        data object None : CapturePublication {
            override fun publish() = Unit
        }

        data class TerminalOnly(
            val terminalFrame: CompletableFuture<Long>?,
            val frame: Long,
        ) : CapturePublication {
            override fun publish() {
                terminalFrame?.complete(frame)
            }
        }

        data class Success<T>(
            val terminalFrame: CompletableFuture<Long>?,
            val frame: Long,
            val result: CompletableFuture<T>,
            val value: T,
        ) : CapturePublication {
            override fun publish() {
                terminalFrame?.complete(frame)
                result.complete(value)
            }
        }

        data class Failure(
            val terminalFrame: CompletableFuture<Long>?,
            val frame: Long,
            val result: CompletableFuture<*>,
            val failure: Throwable,
        ) : CapturePublication {
            override fun publish() {
                terminalFrame?.complete(frame)
                result.completeExceptionally(failure)
            }
        }
    }

    companion object {
        private fun clockClosed(): CancellationException =
            CancellationException("Vibris frame clock closed")

        private fun frameWaitCancelled(): CancellationException =
            CancellationException("Vibris frame wait cancelled")

        private fun captureCancelled(): CancellationException =
            CancellationException("Vibris capture cancelled")
    }
}