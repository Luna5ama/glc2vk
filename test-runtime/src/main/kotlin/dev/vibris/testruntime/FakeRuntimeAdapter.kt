package dev.vibris.testruntime

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.CompileCatalog
import dev.vibris.api.ContextApplyResult
import dev.vibris.api.DeterministicTemporalCaptureOutcome
import dev.vibris.api.DeterministicTemporalCapturePlanner
import dev.vibris.api.DeterministicTemporalCapturePlanning
import dev.vibris.api.DeterministicTemporalCaptureReloaded
import dev.vibris.api.DeterministicTemporalCaptureRequest
import dev.vibris.api.EffectiveShaderSettings
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.api.RuntimeStatus
import dev.vibris.api.RuntimeAction
import dev.vibris.api.RuntimeEnvironment
import dev.vibris.api.SceneContext
import dev.vibris.api.TemporalResetResult
import dev.vibris.api.VibrisRuntimeAdapter
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

class FakeRuntimeAdapter : VibrisRuntimeAdapter {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val frameGate = java.lang.Object()
    private val renderedFrames = AtomicLong()
    private val activeOperations = AtomicInteger()
    private val maxConcurrentOperationsValue = AtomicInteger()
    private val activeCompoundStages = mutableSetOf<CompletableFuture<DeterministicTemporalCaptureOutcome>>()
    private var registeredTarget: TemporalBoundary? = null

    @Volatile
    private var resourceCatalog = ResourceCatalog.empty()

    @Volatile
    private var compileCatalog = CompileCatalog.empty(0)

    @Volatile
    private var currentSaveId = "test-save"

    @Volatile
    private var currentDimensionId = "minecraft:overworld"

    @Volatile
    private var paused = false

    @Volatile
    private var closed = false

    override fun getRuntimeEnvironment(): CompletionStage<RuntimeEnvironment> = immediate {
        RuntimeEnvironment(
            "test-minecraft", "test-iris", "test-vibris", "test-java", "test-os",
            "test-gpu-vendor", "test-gpu-renderer", "test-opengl", "test-driver",
        )
    }

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
        immediate(cancellation) { ReloadResult.success(EffectiveShaderSettings.empty(), emptyList()) }

    override fun resetTemporalState(
        cancellation: CancellationToken,
    ): CompletionStage<TemporalResetResult> =
        immediate(cancellation) { TemporalResetResult(true) }

    override fun getCompileCatalog(cancellation: CancellationToken): CompletionStage<CompileCatalog> =
        immediate(cancellation) { compileCatalog }

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
                val completedFrame = advanceRenderedFrames(frameCount.toLong(), cancellation)
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

    internal fun awaitDeterministicBoundaryRegistration(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        synchronized(frameGate) {
            while (registeredTarget == null && !closed) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    return false
                }
                TimeUnit.NANOSECONDS.timedWait(frameGate, remaining)
            }
            return registeredTarget != null
        }
    }

    internal fun renderedFrame(): Long = synchronized(frameGate) { renderedFrames.get() }

    internal fun isClosedForTests(): Boolean = closed

    internal fun replaceCatalogs(resourceCatalog: ResourceCatalog, compileCatalog: CompileCatalog) {
        synchronized(frameGate) {
            ensureOpen()
            this.resourceCatalog = resourceCatalog
            this.compileCatalog = compileCatalog
        }
    }

    override fun getResourceCatalog(): ResourceCatalog {
        ensureOpen()
        return resourceCatalog
    }

    override fun capture(
        plan: CapturePlan,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult> =
        immediate(cancellation) {
            CaptureResult(renderedFrames.get(), emptyList())
        }

    override fun captureDeterministicTemporalPhase(
        request: DeterministicTemporalCaptureRequest,
        planner: DeterministicTemporalCapturePlanner,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<DeterministicTemporalCaptureOutcome> {
        val result = CompletableFuture<DeterministicTemporalCaptureOutcome>()

        synchronized(frameGate) {
            if (closed) {
                return CompletableFuture.failedFuture(IllegalStateException("Vibris fake runtime is closed"))
            }
            activeCompoundStages.add(result)
        }

        val active = activeOperations.incrementAndGet()
        maxConcurrentOperationsValue.accumulateAndGet(active) { left, right -> maxOf(left, right) }
        try {
            Thread.ofVirtual().name("Vibris fake deterministic capture").start {
                val terminal = runCatching {
                    runDeterministicCapture(request, planner, sink, cancellation)
                }
                activeOperations.decrementAndGet()
                synchronized(frameGate) {
                    activeCompoundStages.remove(result)
                    frameGate.notifyAll()
                }
                terminal.fold(result::complete, result::completeExceptionally)
            }
        } catch (throwable: Throwable) {
            activeOperations.decrementAndGet()
            synchronized(frameGate) {
                activeCompoundStages.remove(result)
                frameGate.notifyAll()
            }
            result.completeExceptionally(throwable)
        }
        return result
    }

    private fun runDeterministicCapture(
        request: DeterministicTemporalCaptureRequest,
        planner: DeterministicTemporalCapturePlanner,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): DeterministicTemporalCaptureOutcome {
        val context = try {
            checkActive(cancellation)
            currentSaveId = request.context.saveId
            currentDimensionId = request.context.dimensionId
            checkActive(cancellation)
            ContextApplyResult.success(request.context)
        } catch (throwable: Throwable) {
            return DeterministicTemporalCaptureOutcome.ContextRejected(
                ContextApplyResult.failure(request.context, failureMessage(throwable)),
                failure(throwable, cancellation),
            )
        }

        val reloaded = try {
            checkActive(cancellation)
            val reload = ReloadResult.success(EffectiveShaderSettings.empty(), emptyList())
            val catalogs = synchronized(frameGate) {
                checkActive(cancellation)
                resourceCatalog to compileCatalog
            }
            DeterministicTemporalCaptureReloaded(
                context,
                reload,
                System.currentTimeMillis().coerceAtLeast(1L),
                catalogs.first,
                catalogs.second,
            )
        } catch (throwable: Throwable) {
            return DeterministicTemporalCaptureOutcome.ReloadRejected(
                context,
                ReloadResult.failure(emptyList()),
                failure(throwable, cancellation),
            )
        }

        val planning = try {
            checkActive(cancellation)
            val resolved = planner.plan(reloaded.resourceCatalog, reloaded.compileCatalog)
            checkActive(cancellation)
            resolved
        } catch (throwable: Throwable) {
            return DeterministicTemporalCaptureOutcome.PlanningRejected(
                reloaded,
                failure(throwable, cancellation),
            )
        }
        val plan = when (planning) {
            is DeterministicTemporalCapturePlanning.Planned -> planning.plan
            is DeterministicTemporalCapturePlanning.Rejected -> {
                return DeterministicTemporalCaptureOutcome.PlanningRejected(reloaded, planning.failure)
            }
        }

        val boundary = try {
            registerDeterministicTarget(request.warmupFrames, cancellation)
        } catch (throwable: Throwable) {
            return DeterministicTemporalCaptureOutcome.ResetRejected(
                reloaded,
                plan,
                TemporalResetResult(false),
                failure(throwable, cancellation),
            )
        }

        return try {
            awaitFrameDuration(Math.addExact(request.warmupFrames, 1), cancellation)
            val actualFrame = advanceToRegisteredTarget(boundary, cancellation)
            val capture = capturePlan(plan, sink, actualFrame, cancellation)
            checkActive(cancellation)
            DeterministicTemporalCaptureOutcome.Captured(
                reloaded,
                plan,
                boundary.reset,
                boundary.resetCompletedAtUnixMs,
                request.warmupFrames,
                boundary.anchorFrame,
                boundary.warmupEndFrame,
                capture,
            )
        } catch (throwable: Throwable) {
            deterministicFailure(
                reloaded,
                plan,
                boundary,
                request.warmupFrames,
                renderedFrame(),
                failure(throwable, cancellation),
            )
        } finally {
            releaseDeterministicTarget(boundary)
        }
    }

    private fun deterministicFailure(
        reloaded: DeterministicTemporalCaptureReloaded,
        plan: CapturePlan,
        boundary: TemporalBoundary,
        warmupFrames: Int,
        terminalFrame: Long,
        failure: DeterministicTemporalCaptureOutcome.Failure,
    ): DeterministicTemporalCaptureOutcome {
        if (warmupFrames > 0 && terminalFrame < boundary.warmupEndFrame) {
            val completedFrames = Math.subtractExact(terminalFrame, boundary.anchorFrame).toInt()
            return DeterministicTemporalCaptureOutcome.WarmupRejected(
                reloaded,
                plan,
                boundary.reset,
                boundary.resetCompletedAtUnixMs,
                warmupFrames,
                boundary.anchorFrame,
                completedFrames,
                terminalFrame,
                failure,
            )
        }
        return DeterministicTemporalCaptureOutcome.CaptureRejected(
            reloaded,
            plan,
            boundary.reset,
            boundary.resetCompletedAtUnixMs,
            warmupFrames,
            boundary.anchorFrame,
            boundary.warmupEndFrame,
            boundary.targetFrame,
            terminalFrame,
            failure,
        )
    }

    private fun failure(
        throwable: Throwable,
        cancellation: CancellationToken,
    ): DeterministicTemporalCaptureOutcome.Failure {
        val kind = when {
            throwable is MissedTargetException ->
                DeterministicTemporalCaptureOutcome.FailureKind.MISSED_TARGET
            throwable is CancellationException || closed || cancellation.isCancellationRequested() ->
                DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED
            else -> DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED
        }
        return DeterministicTemporalCaptureOutcome.Failure(kind, failureMessage(throwable))
    }

    private fun failureMessage(throwable: Throwable): String =
        throwable.message?.takeIf(String::isNotBlank) ?: throwable.javaClass.simpleName

    private data class TemporalBoundary(
        val reset: TemporalResetResult,
        val resetCompletedAtUnixMs: Long,
        val anchorFrame: Long,
        val warmupEndFrame: Long,
        val targetFrame: Long,
    )

    private class MissedTargetException(message: String) : IllegalStateException(message)

    override fun beginDeterministicSequence(cancellation: CancellationToken): CompletionStage<Void> =
        immediateVoid(cancellation)

    override fun endDeterministicSequence(cancellation: CancellationToken): CompletionStage<Void> =
        immediateVoid(cancellation)

    override fun captureAfterPass(
        request: CapturePlan.AfterPassRequest,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CapturePlan.AfterPassReceipt> = CompletableFuture.failedFuture(
        UnsupportedOperationException("After-pass capture is unavailable in the scheduling fixture"),
    )

    override fun capturePatchedShaders(
        artifactName: String,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult> = immediate(cancellation) {
        val fileName = "$artifactName.001_fake.vsh"
        val bytes = "void main() {}\n".encodeToByteArray()
        writeArtifact(sink, fileName, bytes, cancellation)
        val resource = ResourceCatalog.ResourceDescriptor.of(
            "patched_shaders",
            ResourceCatalog.ResourceKind.PATCHED_SHADERS,
            emptyList(), 0, 0, 0, 0, 0, "text", 0, ResourceCatalog.ScalarType.UNSPECIFIED,
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
        var interrupted = false
        synchronized(frameGate) {
            closed = true
            paused = false
            frameGate.notifyAll()
            while (activeCompoundStages.isNotEmpty()) {
                try {
                    frameGate.wait()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
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

    private fun awaitFrameDuration(frameCount: Int, cancellation: CancellationToken) {
        var remainingNanos = Math.multiplyExact(frameCount.toLong(), NANOS_PER_FRAME)
        while (remainingNanos > 0) {
            awaitUnpaused(cancellation)
            val startedNanos = System.nanoTime()
            val slice = minOf(remainingNanos, MAX_WAIT_SLICE_NANOS)
            LockSupport.parkNanos(slice)
            checkActive(cancellation)
            remainingNanos -= minOf(System.nanoTime() - startedNanos, remainingNanos)
        }
    }

    private fun registerDeterministicTarget(
        warmupFrames: Int,
        cancellation: CancellationToken,
    ): TemporalBoundary = synchronized(frameGate) {
        checkActive(cancellation)
        check(registeredTarget == null) { "A deterministic capture target is already registered" }
        val anchorFrame = renderedFrames.get()
        val warmupEndFrame = Math.addExact(anchorFrame, warmupFrames.toLong())
        val boundary = TemporalBoundary(
            TemporalResetResult(true),
            System.currentTimeMillis().coerceAtLeast(1L),
            anchorFrame,
            warmupEndFrame,
            Math.addExact(warmupEndFrame, 1L),
        )
        registeredTarget = boundary
        frameGate.notifyAll()
        boundary
    }

    private fun advanceToRegisteredTarget(
        boundary: TemporalBoundary,
        cancellation: CancellationToken,
    ): Long = synchronized(frameGate) {
        checkActive(cancellation)
        if (registeredTarget !== boundary || renderedFrames.get() != boundary.anchorFrame) {
            throw MissedTargetException("The deterministic capture target was missed")
        }
        val advanced = renderedFrames.addAndGet(
            Math.subtractExact(boundary.targetFrame, boundary.anchorFrame),
        )
        if (advanced != boundary.targetFrame) {
            throw MissedTargetException("The deterministic capture target was missed")
        }
        advanced
    }

    private fun releaseDeterministicTarget(boundary: TemporalBoundary) {
        synchronized(frameGate) {
            if (registeredTarget === boundary) {
                registeredTarget = null
                frameGate.notifyAll()
            }
        }
    }

    private fun advanceRenderedFrames(frameCount: Long, cancellation: CancellationToken): Long =
        synchronized(frameGate) {
            while (registeredTarget != null && !closed && !cancellation.isCancellationRequested()) {
                frameGate.wait(1)
            }
            checkActive(cancellation)
            renderedFrames.addAndGet(frameCount)
        }

    private fun capturePlan(
        plan: CapturePlan,
        sink: ArtifactSink,
        frameId: Long,
        cancellation: CancellationToken,
    ): CaptureResult {
        val groups = plan.targets.map { target ->
            val artifacts = target.outputs.map { output ->
                val bytes = "fake:${target.resource.logicalName}:$frameId:${output.fileName}\n".encodeToByteArray()
                writeArtifact(sink, output.fileName, bytes, cancellation)
                CaptureResult.CapturedArtifact(
                    output.fileName,
                    output.format,
                    output.role,
                    output.subresourceIndex,
                )
            }
            val texture = target.resource.kind == ResourceCatalog.ResourceKind.TEXTURE
            val resource = ResourceCatalog.ResourceDescriptor.of(
                target.resource.logicalName,
                target.resource.kind,
                if (texture) listOf(requireNotNull(target.resource.textureView)) else emptyList(),
                0,
                0,
                0,
                if (texture) 1 else 0,
                if (texture) 1 else 0,
                "fake",
                0,
                ResourceCatalog.ScalarType.UNSPECIFIED,
                0,
                frameId,
                target.resource.logicalName,
                "fake",
                "",
                "",
                "",
                0,
                "",
                "",
            )
            CaptureResult.ArtifactGroup(target.artifactName, resource, artifacts)
        }
        return CaptureResult(frameId, groups)
    }

    private fun writeArtifact(
        sink: ArtifactSink,
        fileName: String,
        bytes: ByteArray,
        cancellation: CancellationToken,
    ) {
        checkActive(cancellation)
        val output = sink.open(fileName)
        try {
            checkActive(cancellation)
            output.write(bytes)
            checkActive(cancellation)
        } finally {
            output.close()
        }
        checkActive(cancellation)
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

    private fun immediateVoid(cancellation: CancellationToken): CompletionStage<Void> =
        try {
            ensureOpen()
            cancellation.throwIfCancellationRequested()
            CompletableFuture<Void>().also { it.complete(null) }
        } catch (exception: RuntimeException) {
            CompletableFuture.failedFuture(exception)
        }

    private companion object {
        const val NANOS_PER_FRAME = 100_000L
        const val MAX_WAIT_SLICE_NANOS = 1_000_000L
    }
}
