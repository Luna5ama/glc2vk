package dev.vibris.core

import dev.vibris.protocol.v2.SceneContext
import java.util.ArrayList
import java.util.LinkedHashMap

class CoreProbe {
    private val executionOrder = ArrayList<String>()
    private val executionEvents = ArrayList<String>()
    private val contextSnapshots = ArrayList<SceneContext>()
    private val jobTraces = ArrayList<JobTrace>()
    private val sourceTransitions = ArrayList<SourceTransition>()
    private val sourceStates = LinkedHashMap<String, MutableList<String>>()
    private val executionCounts = LinkedHashMap<String, Int>()
    private var concurrentJobs = 0
    private var maxConcurrentJobs = 0
    private var requestRegistrySize = 0
    private var requestRegistryPeak = 0
    private var sourceRegistrySize = 0
    private var sourceRegistryPeak = 0
    private var queueSize = 0
    private var queuePeak = 0

    @Synchronized
    fun jobStarted(requestId: String) {
        boundedAdd(executionOrder, requestId, TRACE_LIMIT)
        executionCounts.merge(requestId, 1) { left, right -> left + right }
        trimMap(executionCounts)
        concurrentJobs++
        maxConcurrentJobs = maxOf(maxConcurrentJobs, concurrentJobs)
    }

    @Synchronized
    fun contextApplied(requestId: String, workspaceId: String, context: SceneContext) {
        boundedAdd(contextSnapshots, context, TRACE_LIMIT)
        boundedAdd(jobTraces, JobTrace(requestId, workspaceId, context), TRACE_LIMIT)
    }

    @Synchronized
    fun event(requestId: String, event: String) {
        boundedAdd(executionEvents, "$requestId:$event", TRACE_LIMIT)
    }

    @Synchronized
    fun jobStopped() {
        concurrentJobs--
    }

    @Synchronized
    fun sourceTransition(uuid: String, from: String, to: String) {
        val states = sourceStates.computeIfAbsent(uuid) { ArrayList() }
        trimMap(sourceStates)
        if (states.isEmpty() && from.isNotEmpty()) {
            states.add(from)
        }
        states.add(to)
        boundedAdd(sourceTransitions, SourceTransition(uuid, from, to), TRACE_LIMIT)
    }

    @Synchronized
    fun registries(requests: Int, sources: Int, queued: Int) {
        requestRegistrySize = requests
        requestRegistryPeak = maxOf(requestRegistryPeak, requests)
        sourceRegistrySize = sources
        sourceRegistryPeak = maxOf(sourceRegistryPeak, sources)
        queueSize = queued
        queuePeak = maxOf(queuePeak, queued)
    }

    @Synchronized
    fun executionOrder(): List<String> = java.util.List.copyOf(executionOrder)

    @Synchronized
    fun executionEvents(): List<String> = java.util.List.copyOf(executionEvents)

    @Synchronized
    fun contextSnapshots(): List<SceneContext> = java.util.List.copyOf(contextSnapshots)

    @Synchronized
    fun sourceStates(uuid: String): List<String> =
        java.util.List.copyOf(sourceStates.getOrDefault(uuid, emptyList()))

    @Synchronized
    fun maxConcurrentJobs(): Int = maxConcurrentJobs

    @Synchronized
    fun executionCount(requestId: String): Int = executionCounts.getOrDefault(requestId, 0)

    @Synchronized
    fun requestRegistrySize(): Int = requestRegistrySize

    fun requestRegistryCapacity(): Int = VibrisCoreEngine.REQUEST_REGISTRY_CAPACITY

    @Synchronized
    fun sourceRegistrySize(): Int = sourceRegistrySize

    fun sourceRegistryCapacity(): Int = SourceRegistry.CAPACITY

    @Synchronized
    fun queueSize(): Int = queueSize

    fun queueCapacity(): Int = FairJobScheduler.CAPACITY

    @Synchronized
    fun snapshot(): Snapshot =
        Snapshot(
            java.util.List.copyOf(jobTraces),
            java.util.List.copyOf(sourceTransitions),
            java.util.List.copyOf(executionOrder),
            java.util.List.copyOf(executionEvents),
            java.util.Map.copyOf(executionCounts),
            maxConcurrentJobs,
            queuePeak,
            requestRegistrySize,
            requestRegistryPeak,
            sourceRegistrySize,
            sourceRegistryPeak,
        )

    @JvmRecord
    data class JobTrace(
        val requestId: String,
        val workspaceId: String,
        val context: SceneContext,
    )

    @JvmRecord
    data class SourceTransition(
        val uuid: String,
        val from: String,
        val to: String,
    )

    @JvmRecord
    data class Snapshot(
        val jobTraces: List<JobTrace>,
        val sourceTransitions: List<SourceTransition>,
        val executionOrder: List<String>,
        val executionEvents: List<String>,
        val executionCounts: Map<String, Int>,
        val maxConcurrentJobs: Int,
        val queuePeak: Int,
        val requestRegistrySize: Int,
        val requestRegistryPeak: Int,
        val sourceRegistrySize: Int,
        val sourceRegistryPeak: Int,
    )

    companion object {
        private const val TRACE_LIMIT = 1_024
        private const val HISTORY_LIMIT = 128

        private fun <T> boundedAdd(values: MutableList<T>, value: T, limit: Int) {
            if (values.size == limit) {
                values.removeAt(0)
            }
            values.add(value)
        }

        private fun trimMap(values: MutableMap<*, *>) {
            while (values.size > HISTORY_LIMIT) {
                values.remove(values.keys.iterator().next())
            }
        }
    }
}