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
    private val histories = GpuTimingHistories()
    private var capture: Capture? = null

    fun capture(frames: Int): CompletionStage<GpuTimingSnapshot> {
        require(frames in 1..MAX_CAPTURE_FRAMES) { "frames must be between 1 and $MAX_CAPTURE_FRAMES" }
        check(capture == null) { "GPU metric capture is already active" }
        histories.clear()
        val result = CompletableFuture<GpuTimingSnapshot>()
        capture = Capture(frames, result)
        return result
    }

    fun begin(name: String) = begin(
        GpuTimingTarget.Aggregate(
            GpuTimingScope(name, GpuTimingScopeKind.COMPATIBILITY_AGGREGATE, null, null),
        ),
    )

    fun beginAggregate(scope: GpuTimingScope) = begin(GpuTimingTarget.Aggregate(scope))

    fun beginProgram(program: GpuTimingProgram, frameworkPass: String?, stage: String) {
        if (capture == null) return
        begin(GpuTimingTarget.Program(program.copy(stage = stage).snapshot(), frameworkPass))
    }

    private fun begin(target: GpuTimingTarget) {
        if (capture == null) return
        harvest(false)
        val query = GL15C.glGenQueries()
        GL33C.glQueryCounter(query, GL33C.GL_TIMESTAMP)
        active.addLast(ActiveTiming(target, query))
    }

    fun end() {
        if (active.isEmpty()) return
        val timing = active.removeLast()
        val endQuery = GL15C.glGenQueries()
        GL33C.glQueryCounter(endQuery, GL33C.GL_TIMESTAMP)
        pending.addLast(PendingTiming(timing.target, timing.startQuery, endQuery))
        harvest(false)
    }

    fun finishFrame() {
        val current = capture ?: return
        current.frames--
        if (current.frames > 0) return
        harvest(true)
        capture = null
        current.result.complete(histories.snapshot())
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
            histories.add(timing.target, (end - start).coerceAtLeast(0L))
        }
    }

    private data class ActiveTiming(val target: GpuTimingTarget, val startQuery: Int)

    private data class PendingTiming(val target: GpuTimingTarget, val startQuery: Int, val endQuery: Int)

    private data class Capture(
        var frames: Int,
        val result: CompletableFuture<GpuTimingSnapshot>,
    )
}

internal sealed interface GpuTimingTarget {
    data class Aggregate(val scope: GpuTimingScope) : GpuTimingTarget

    data class Program(val program: GpuTimingProgram, val frameworkPass: String?) : GpuTimingTarget
}

internal class GpuTimingHistories {
    private val aggregateHistories = linkedMapOf<String, TimingHistory>()
    private val aggregateScopes = linkedMapOf<String, GpuTimingScope>()
    private val programHistories = linkedMapOf<ProgramKey, TimingHistory>()

    fun clear() {
        aggregateHistories.clear()
        aggregateScopes.clear()
        programHistories.clear()
    }

    fun add(target: GpuTimingTarget, value: Long) {
        when (target) {
            is GpuTimingTarget.Aggregate -> addAggregate(target.scope, value)
            is GpuTimingTarget.Program -> addProgram(target.program.snapshot(), target.frameworkPass, value)
        }
    }

    fun snapshot(): GpuTimingSnapshot = GpuTimingSnapshot(
        aggregateTimings = aggregateHistories.mapValues { (_, history) -> history.stats() },
        aggregateScopes = aggregateScopes.values.toList(),
        programTimings = programHistories.map { (key, history) ->
            GpuProgramTimingStats(
                metric = "${key.program.program}_${key.program.stage}",
                program = key.program,
                frameworkPass = key.frameworkPass,
                compatibilityMetric = key.frameworkPass?.let { "${it}_${key.program.stage}" },
                statistics = history.stats(),
            )
        },
    )

    private fun addAggregate(scope: GpuTimingScope, value: Long) {
        aggregateScopes.putIfAbsent(scope.metric, scope)
        aggregateHistories.getOrPut(scope.metric, ::TimingHistory).add(value)
    }

    private fun addProgram(program: GpuTimingProgram, frameworkPass: String?, value: Long) {
        if (frameworkPass != null) {
            addAggregate(
                GpuTimingScope(
                    metric = "${frameworkPass}_${program.stage}",
                    kind = GpuTimingScopeKind.COMPATIBILITY_AGGREGATE,
                    frameworkPass = frameworkPass,
                    stage = program.stage,
                ),
                value,
            )
        }
        programHistories.getOrPut(ProgramKey(program, frameworkPass), ::TimingHistory).add(value)
    }

    private data class ProgramKey(val program: GpuTimingProgram, val frameworkPass: String?)
}

private fun GpuTimingProgram.snapshot(): GpuTimingProgram = copy(
    defines = java.util.Map.copyOf(defines),
)

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
