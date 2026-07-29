package dev.vibris.api

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

fun interface CancellationToken {
    fun isCancellationRequested(): Boolean

    fun throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw CancellationException("Vibris operation was cancelled")
        }
    }

    class Source private constructor() {
        private val cancelled = AtomicBoolean()
        private val token = CancellationToken { cancelled.get() }

        fun token(): CancellationToken = token

        fun cancel(): Boolean = cancelled.compareAndSet(false, true)

        companion object {
            internal fun create(): Source = Source()
        }
    }

    companion object {
        @JvmStatic
        fun none(): CancellationToken = CancellationToken { false }

        @JvmStatic
        fun source(): Source = Source.create()
    }
}