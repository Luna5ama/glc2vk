package dev.vibris.core

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ContextApplyResult
import dev.vibris.api.ContextValidationResult
import dev.vibris.api.DebugControlCommand
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.api.RuntimeStatus
import dev.vibris.api.SceneContext
import dev.vibris.api.ScenePreset
import dev.vibris.api.TemporalResetResult
import dev.vibris.api.VibrisRuntimeAdapter
import java.util.Objects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BiConsumer
import java.util.function.Supplier

class ThreadBoundVibrisRuntimeAdapter @JvmOverloads constructor(
    host: VibrisRuntimeHost?,
    frames: RenderedFrameClock?,
    private val frameWaitObserver: BiConsumer<Long, Long>? = null,
) : VibrisRuntimeAdapter {
    private val host = Objects.requireNonNull(host, "host")!!
    private val frames = Objects.requireNonNull(frames, "frames")!!
    private val closed = AtomicBoolean()

    @Volatile
    private var catalog = ResourceCatalog.empty()

    override fun getStatus(): CompletionStage<RuntimeStatus> = onClient(
        Supplier {
            val status = host.status()
            catalog = try {
                host.resourceCatalog(frames.currentFrame())
            } catch (_: IllegalStateException) {
                ResourceCatalog.empty()
            }
            status
        },
        CancellationToken.none(),
    )

    override fun debugControl(command: DebugControlCommand): CompletionStage<String> =
        onClient(Supplier { host.debugControl(command) }, CancellationToken.none())

    override fun listPresets(): CompletionStage<List<ScenePreset>> =
        onClient(Supplier(host::presets), CancellationToken.none())

    override fun validateContext(context: SceneContext): CompletionStage<ContextValidationResult> =
        onClient(Supplier { host.validateContext(context) }, CancellationToken.none())

    override fun ensureWorldAndContext(
        context: SceneContext,
        cancellation: CancellationToken,
    ): CompletionStage<ContextApplyResult> =
        onClientStage(Supplier { host.applyContext(context, cancellation) }, cancellation)

    override fun reloadVibrisShaderpack(cancellation: CancellationToken): CompletionStage<ReloadResult> =
        onClient(
            Supplier {
                val result = host.reload(cancellation)
                if (result.successful) {
                    catalog = host.resourceCatalog(frames.currentFrame())
                }
                result
            },
            cancellation,
        )

    override fun resetTemporalState(cancellation: CancellationToken): CompletionStage<TemporalResetResult> =
        onClient(Supplier { host.resetTemporal(cancellation) }, cancellation)

    override fun waitRenderedFrames(
        frameCount: Int,
        cancellation: CancellationToken,
    ): CompletionStage<Long> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("Vibris runtime is closed"))
        }
        val start = frames.currentFrame()
        return frames.waitRenderedFrames(frameCount, cancellation).thenApply { end ->
            frameWaitObserver?.accept(start, end)
            end
        }
    }

    override fun getResourceCatalog(): ResourceCatalog = catalog

    override fun capture(
        plan: CapturePlan,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("Vibris runtime is closed"))
        }
        return frames.captureAtNextFrame(cancellation) { frameId ->
            val result = host.capture(plan, sink, frameId, cancellation)
            catalog = host.resourceCatalog(frameId)
            result
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        frames.close()
        if (host.isClientThread()) {
            host.close()
            return
        }
        val complete = CompletableFuture<Void>()
        host.executeOnClient {
            try {
                host.close()
                complete.complete(null)
            } catch (throwable: Throwable) {
                complete.completeExceptionally(throwable)
            }
        }
        complete.join()
    }

    private fun <T> onClient(action: Supplier<T>, cancellation: CancellationToken): CompletionStage<T> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("Vibris runtime is closed"))
        }
        val result = CompletableFuture<T>()
        val task = Runnable {
            try {
                cancellation.throwIfCancellationRequested()
                result.complete(action.get())
            } catch (throwable: Throwable) {
                result.completeExceptionally(throwable)
            }
        }
        if (host.isClientThread()) {
            task.run()
        } else {
            host.executeOnClient(task)
        }
        return result
    }

    private fun <T> onClientStage(
        action: Supplier<CompletionStage<T>>,
        cancellation: CancellationToken,
    ): CompletionStage<T> = onClient(action, cancellation).thenCompose { stage -> stage }
}
