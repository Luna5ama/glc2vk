package dev.vibris.core

import dev.vibris.api.CancellationToken
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.function.LongFunction

class RenderedFrameClock : AutoCloseable {
    private val waiters = ArrayList<Waiter>()
    private val captures = ArrayList<CaptureTask<*>>()
    private var renderedFrames = 0L
    private var closed = false

    @Synchronized
    fun waitRenderedFrames(count: Int, cancellation: CancellationToken): CompletionStage<Long> {
        require(count >= 0) { "Frame count must not be negative" }
        if (closed || cancellation.isCancellationRequested()) {
            return cancelled()
        }
        if (count == 0) {
            return CompletableFuture.completedFuture(renderedFrames)
        }

        val result = CompletableFuture<Long>()
        val waiter = Waiter(renderedFrames + count, cancellation, result)
        waiters.add(waiter)
        pollCancellation(waiter)
        return result
    }

    fun <T> captureAtNextFrame(
        cancellation: CancellationToken,
        action: LongFunction<T>,
    ): CompletionStage<T> = synchronized(this) {
        if (closed || cancellation.isCancellationRequested()) {
            return@synchronized cancelled()
        }
        val result = CompletableFuture<T>()
        val capture = CaptureTask(cancellation, action, result)
        captures.add(capture)
        pollCaptureCancellation(capture)
        result
    }

    fun renderedFrame() {
        val current: List<CaptureTask<*>>
        val frameId: Long
        synchronized(this) {
            if (closed) {
                return
            }
            renderedFrames++
            frameId = renderedFrames
            waiters.removeIf(::complete)
            current = java.util.List.copyOf(captures)
            captures.clear()
        }
        current.forEach { capture -> capture.run(frameId) }
    }

    @Synchronized
    fun currentFrame(): Long = renderedFrames

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        waiters.forEach { waiter ->
            waiter.result.completeExceptionally(CancellationException("Vibris frame clock closed"))
        }
        waiters.clear()
        captures.forEach { capture ->
            capture.result.completeExceptionally(CancellationException("Vibris frame clock closed"))
        }
        captures.clear()
    }

    private fun complete(waiter: Waiter): Boolean {
        if (waiter.cancellation.isCancellationRequested()) {
            waiter.result.completeExceptionally(CancellationException("Vibris frame wait cancelled"))
            return true
        }
        if (renderedFrames < waiter.target) {
            return false
        }
        waiter.result.complete(renderedFrames)
        return true
    }

    private fun pollCancellation(waiter: Waiter) {
        CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS).execute task@{
            synchronized(this) {
                if (waiter.result.isDone || closed || !waiters.contains(waiter)) {
                    return@task
                }
                if (waiter.cancellation.isCancellationRequested()) {
                    waiters.remove(waiter)
                    waiter.result.completeExceptionally(CancellationException("Vibris frame wait cancelled"))
                    return@task
                }
            }
            pollCancellation(waiter)
        }
    }

    private fun pollCaptureCancellation(capture: CaptureTask<*>) {
        CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS).execute task@{
            synchronized(this) {
                if (capture.result.isDone || closed || !captures.contains(capture)) {
                    return@task
                }
                if (capture.cancellation.isCancellationRequested()) {
                    captures.remove(capture)
                    capture.result.completeExceptionally(CancellationException("Vibris capture cancelled"))
                    return@task
                }
            }
            pollCaptureCancellation(capture)
        }
    }

    @JvmRecord
    private data class Waiter(
        val target: Long,
        val cancellation: CancellationToken,
        val result: CompletableFuture<Long>,
    )

    @JvmRecord
    private data class CaptureTask<T>(
        val cancellation: CancellationToken,
        val action: LongFunction<T>,
        val result: CompletableFuture<T>,
    ) {
        fun run(frameId: Long) {
            try {
                cancellation.throwIfCancellationRequested()
                result.complete(action.apply(frameId))
            } catch (throwable: Throwable) {
                result.completeExceptionally(throwable)
            }
        }
    }

    companion object {
        private fun <T> cancelled(): CompletionStage<T> =
            CompletableFuture.failedFuture(CancellationException("Vibris frame wait cancelled"))
    }
}