package dev.luna5ama.vibris.capture

import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL33C
import java.util.ArrayDeque

internal class GpuTimingMetrics {
    private val active = ArrayDeque<ActiveTiming>()
    private val pending = ArrayDeque<PendingTiming>()
    private val historyLock = Any()
    private val histories = linkedMapOf<String, TimingHistory>()

    fun begin(name: String) {
        harvest()
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
        harvest()
    }

    fun snapshot(): Map<String, GpuTimingStats> = synchronized(historyLock) {
        histories.mapValues { (_, history) -> history.stats() }
    }

    private fun harvest() {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val timing = iterator.next()
            if (GL15C.glGetQueryObjecti(timing.endQuery, GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) break
            val start = GL33C.glGetQueryObjectui64(timing.startQuery, GL15C.GL_QUERY_RESULT)
            val end = GL33C.glGetQueryObjectui64(timing.endQuery, GL15C.GL_QUERY_RESULT)
            GL15C.glDeleteQueries(timing.startQuery)
            GL15C.glDeleteQueries(timing.endQuery)
            iterator.remove()
            synchronized(historyLock) {
                histories.getOrPut(timing.name, ::TimingHistory).add((end - start).coerceAtLeast(0L))
            }
        }
    }

    private data class ActiveTiming(val name: String, val startQuery: Int)

    private data class PendingTiming(val name: String, val startQuery: Int, val endQuery: Int)
}

internal class TimingHistory(private val capacity: Int = 50) {
    private val samples = ArrayDeque<Long>(capacity)

    fun add(value: Long) {
        if (samples.size == capacity) samples.removeFirst()
        samples.addLast(value)
    }

    fun stats(): GpuTimingStats {
        val snapshot = samples.toList()
        return GpuTimingStats(
            snapshot.sum() / snapshot.size,
            snapshot.min(),
            snapshot.max(),
            snapshot.last(),
            snapshot
        )
    }
}