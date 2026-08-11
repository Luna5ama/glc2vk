package dev.vibris.testruntime

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.CompileCatalog
import dev.vibris.api.ContextApplyResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.api.RuntimeStatus
import dev.vibris.api.RuntimeAction
import dev.vibris.api.SceneContext
import dev.vibris.api.TemporalResetResult
import dev.vibris.api.VibrisRuntimeAdapter
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

class FakeRuntimeAdapter : VibrisRuntimeAdapter {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val frameGate = java.lang.Object()
    private val renderedFrames = AtomicLong()
    private val activeOperations = AtomicInteger()
    private val maxConcurrentOperationsValue = AtomicInteger()

    @Volatile
    private var currentSaveId = "test-save"

    @Volatile
    private var currentDimensionId = "minecraft:overworld"

    @Volatile
    private var paused = false

    @Volatile
    private var closed = false

    override fun getStatus(): CompletionStage<RuntimeStatus> =
        immediate { RuntimeStatus(true, currentSaveId, currentDimensionId, "") }

    override fun ensureWorldAndContext(
        context: SceneContext,
        cancellation: CancellationToken,
    ): CompletionStage<ContextApplyResult> =
        immediate(cancellation) {
            currentSaveId = context.saveId
            currentDimensionId = context.dimensionId
            ContextApplyResult.success(context)
        }

    override fun reloadVibrisShaderpack(
        config: Map<String, String>?,
        cancellation: CancellationToken,
    ): CompletionStage<ReloadResult> =
        immediate(cancellation) { ReloadResult.success(emptyList()) }

    override fun resetTemporalState(
        cancellation: CancellationToken,
    ): CompletionStage<TemporalResetResult> =
        immediate(cancellation) { TemporalResetResult(true) }

    override fun getCompileCatalog(cancellation: CancellationToken): CompletionStage<CompileCatalog> =
        immediate(cancellation) { CompileCatalog.empty(0) }

    override fun waitRenderedFrames(
        frameCount: Int,
        cancellation: CancellationToken,
    ): CompletionStage<Long> {
        if (frameCount < 0) {
            return CompletableFuture.failedFuture(
                IllegalArgumentException("frameCount must not be negative"),
            )
        }
        val result = CompletableFuture<Long>()
        Thread.ofVirtual().name("Vibris fake frames").start {
            val active = activeOperations.incrementAndGet()
            maxConcurrentOperationsValue.accumulateAndGet(active) { left, right -> maxOf(left, right) }
            try {
                var remainingNanos = Math.multiplyExact(frameCount.toLong(), NANOS_PER_FRAME)
                while (remainingNanos > 0) {
                    awaitUnpaused(cancellation)
                    val startedNanos = System.nanoTime()
                    LockSupport.parkNanos(minOf(remainingNanos, MAX_WAIT_SLICE_NANOS))
                    checkActive(cancellation)
                    remainingNanos -= minOf(System.nanoTime() - startedNanos, remainingNanos)
                }
                checkActive(cancellation)
                val completedFrame = renderedFrames.addAndGet(frameCount.toLong())
                activeOperations.decrementAndGet()
                result.complete(completedFrame)
            } catch (throwable: Throwable) {
                activeOperations.decrementAndGet()
                result.completeExceptionally(throwable)
            }
        }
        return result
    }

    internal fun maxConcurrentOperations(): Int = maxConcurrentOperationsValue.get()

    override fun getResourceCatalog(): ResourceCatalog {
        ensureOpen()
        return ResourceCatalog.empty()
    }

    override fun capture(
        plan: CapturePlan,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult> =
        immediate(cancellation) {
            CaptureResult(renderedFrames.get(), emptyList())
        }

    override fun capturePatchedShaders(
        artifactName: String,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult> = immediate(cancellation) {
        val fileName = "$artifactName.001_fake.vsh"
        val bytes = "void main() {}\n".encodeToByteArray()
        sink.open(fileName).use { it.write(bytes) }
        val resource = ResourceCatalog.ResourceDescriptor(
            "patched_shaders",
            ResourceCatalog.ResourceKind.PATCHED_SHADERS,
            0, 0, 0, 0, 1, "text", 0, ResourceCatalog.ScalarType.UNSPECIFIED,
            bytes.size.toLong(), renderedFrames.get(), "patched_shaders", "patched_shaders",
            "", "", "", 0, "", "",
        )
        CaptureResult(renderedFrames.get(), listOf(CaptureResult.ArtifactGroup(
            artifactName,
            resource,
            listOf(CaptureResult.CapturedArtifact(
                fileName,
                CapturePlan.ArtifactFormat.TEXT,
                CapturePlan.ArtifactRole.SUBRESOURCE,
                0,
            )),
        )))
    }

    override fun executeAction(action: RuntimeAction): CompletionStage<String> = immediate {
        when (action) {
            RuntimeAction.CaptureStatus -> "{\"pending\":false,\"active\":false,\"saving\":false}"
            is RuntimeAction.CapturePass -> "{\"ok\":true,\"path\":\"capture-pass\"}"
            is RuntimeAction.CaptureMulti -> "{\"ok\":true,\"path\":\"capture-multi\"}"
            is RuntimeAction.GpuMetrics ->
                "{\"avg\":1.0,\"p5\":0.9,\"p50\":1.0,\"p95\":1.1}"
            RuntimeAction.ListBuffers -> "{\"buffers\":[]}"
            RuntimeAction.ListTextures -> "{\"textures\":[]}"
        }
    }

    fun pauseExecution() {
        synchronized(frameGate) {
            if (!closed) {
                paused = true
            }
        }
    }

    fun resumeExecution() {
        synchronized(frameGate) {
            paused = false
            frameGate.notifyAll()
        }
    }

    override fun close() {
        synchronized(frameGate) {
            closed = true
            paused = false
            frameGate.notifyAll()
        }
    }

    @Throws(InterruptedException::class)
    private fun awaitUnpaused(cancellation: CancellationToken) {
        synchronized(frameGate) {
            while (paused && !closed && !cancellation.isCancellationRequested()) {
                frameGate.wait(1)
            }
            checkActive(cancellation)
        }
    }

    private fun checkActive(cancellation: CancellationToken) {
        cancellation.throwIfCancellationRequested()
        if (closed) {
            throw CancellationException("Vibris fake runtime was closed")
        }
    }

    private fun ensureOpen() {
        check(!closed) { "Vibris fake runtime is closed" }
    }

    private fun <T> immediate(operation: () -> T): CompletionStage<T> =
        try {
            ensureOpen()
            CompletableFuture.completedFuture(operation())
        } catch (exception: RuntimeException) {
            CompletableFuture.failedFuture(exception)
        }

    private fun <T> immediate(
        cancellation: CancellationToken,
        operation: () -> T,
    ): CompletionStage<T> =
        immediate {
            cancellation.throwIfCancellationRequested()
            operation()
        }

    private companion object {
        const val NANOS_PER_FRAME = 100_000L
        const val MAX_WAIT_SLICE_NANOS = 1_000_000L
    }
}
