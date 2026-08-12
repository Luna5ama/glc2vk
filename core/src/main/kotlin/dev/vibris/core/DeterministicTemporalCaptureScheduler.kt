package dev.vibris.core

import dev.vibris.api.CancellationToken
import dev.vibris.api.CaptureResult
import java.util.concurrent.CompletionStage
import java.util.function.LongFunction

interface DeterministicTemporalCaptureScheduler {
    fun schedule(
        warmupFrames: Int,
        cancellation: CancellationToken,
        capture: LongFunction<CaptureResult>,
    ): ScheduledCapture

    fun currentFrame(): Long

    @JvmRecord
    data class ScheduledCapture(
        val warmupFrames: Int,
        val anchorFrame: Long,
        val warmupEndFrame: Long,
        val captureFrame: Long,
        /** Atomic terminal-frame snapshot, published before [capture] completes on every path. */
        val terminalFrame: CompletionStage<Long>,
        val capture: CompletionStage<CaptureResult>,
    ) {
        init {
            require(warmupFrames >= 0) { "Warmup frame count must not be negative" }
            require(anchorFrame >= 0) { "Anchor frame must not be negative" }
            require(warmupEndFrame == Math.addExact(anchorFrame, warmupFrames.toLong())) {
                "Warmup end frame must equal the anchor plus the requested warmup"
            }
            require(captureFrame == Math.incrementExact(warmupEndFrame)) {
                "Capture frame must immediately follow the warmup end frame"
            }
        }
    }
}