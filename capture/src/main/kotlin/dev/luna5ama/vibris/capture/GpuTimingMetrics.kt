package dev.luna5ama.vibris.capture

import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL33C
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.math.ceil
import kotlin.math.roundToLong

internal class GpuTimingMetrics {
    private val active = ArrayDeque<ActiveTiming>()
    private val pending = ArrayDeque<PendingTiming>()
    private val histories = linkedMapOf<String, TimingHistory>()
    private var capture: Capture? = null

    fun capture(frames: Int): CompletionStage<Map<String, GpuTimingStats>> {
        require(frames in 1..MAX_CAPTURE_FRAMES) { "frames must be between 1 and $MAX_CAPTURE_FRAMES" }
        check(capture == null) { "GPU metric capture is already active" }
        histories.clear()
        val result = CompletableFuture<Map<String, GpuTimingStats>>()
        capture = Capture(frames, result)
        return result
    }

    fun begin(name: String) {
        if (capture == null) return
        harvest(false)
        val query = GL15C.glGenQueries()
        GL33C.glQueryCounter(query, GL33C.GL_TIMESTAMP)
        active.addLast(ActiveTiming(name, query))
    }

    fun end() {
        if (active.isEmpty()) return
        val timing = active.removeLast()
        val endQuery = GL15C.glGenQueries()
        GL33C.glQueryCounter(endQuery, GL33C.GL_TIMESTAMP)
        pending.addLast(PendingTiming(timing.name, timing.startQuery, endQuery))
        harvest(false)
    }

    fun finishFrame() {
        val current = capture ?: return
        current.frames--
        if (current.frames > 0) return
        harvest(true)
        capture = null
        current.result.complete(histories.mapValues { (_, history) -> history.stats() })
    }

    private fun harvest(wait: Boolean) {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val timing = iterator.next()
            if (!wait && GL15C.glGetQueryObjecti(timing.endQuery, GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) break
            val start = GL33C.glGetQueryObjectui64(timing.startQuery, GL15C.GL_QUERY_RESULT)
            val end = GL33C.glGetQueryObjectui64(timing.endQuery, GL15C.GL_QUERY_RESULT)
            GL15C.glDeleteQueries(timing.startQuery)
            GL15C.glDeleteQueries(timing.endQuery)
            iterator.remove()
            histories.getOrPut(timing.name, ::TimingHistory).add((end - start).coerceAtLeast(0L))
        }
    }

    private data class ActiveTiming(val name: String, val startQuery: Int)

    private data class PendingTiming(val name: String, val startQuery: Int, val endQuery: Int)

    private data class Capture(
        var frames: Int,
        val result: CompletableFuture<Map<String, GpuTimingStats>>,
    )
}

internal class TimingHistory {
    private val samples = ArrayList<Long>()

    fun add(value: Long) {
        samples.add(value)
    }

    fun stats(): GpuTimingStats {
        val sorted = samples.sorted()
        return GpuTimingStats(
            samples.sum() / samples.size,
            percentile(sorted, 0.05),
            percentile(sorted, 0.95),
            percentile(sorted, 0.50),
        )
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long {
        val index = sorted.lastIndex * percentile
        val lower = index.toInt()
        val upper = ceil(index).toInt()
        return (sorted[lower] + (sorted[upper] - sorted[lower]) * (index - lower)).roundToLong()
    }
}

private const val MAX_CAPTURE_FRAMES = 10_000
