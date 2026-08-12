package dev.vibris.testruntime

import dev.vibris.core.CoreProbe
import dev.vibris.protocol.v2.SceneContext

class FakeRuntimeProbe internal constructor(
    private val runtime: FakeRuntimeAdapter,
    private val core: CoreProbe,
) {
    fun pauseExecution() {
        runtime.pauseExecution()
    }

    fun resumeExecution() {
        runtime.resumeExecution()
    }

    fun executionOrder(): List<String> = core.executionOrder()

    fun executionEvents(): List<String> = core.executionEvents()

    fun contextSnapshots(): List<SceneContext> = core.contextSnapshots()

    fun sourceStates(uuid: String): List<String> = core.sourceStates(uuid)

    fun maxConcurrentJobs(): Int = core.maxConcurrentJobs()

    fun executionCount(requestId: String): Int = core.executionCount(requestId)

    fun requestRegistrySize(): Int = core.requestRegistrySize()

    fun requestRegistryCapacity(): Int = core.requestRegistryCapacity()

    fun sourceRegistrySize(): Int = core.sourceRegistrySize()

    fun sourceRegistryCapacity(): Int = core.sourceRegistryCapacity()

    fun queueSize(): Int = core.queueSize()

    fun queueCapacity(): Int = core.queueCapacity()

    internal fun maxConcurrentRuntimeOperations(): Int = runtime.maxConcurrentOperations()

    internal fun snapshot(): CoreProbe.Snapshot = core.snapshot()
}