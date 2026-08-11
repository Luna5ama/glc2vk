package dev.vibris.core

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ContextApplyResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.api.RuntimeStatus
import dev.vibris.api.SceneContext
import dev.vibris.api.TemporalResetResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger

class ThreadBoundVibrisRuntimeAdapterTest {
    @Test
    fun reportsPendingFrameWorkAndNotifiesOnlyOnIdleToActiveTransitions() {
        val frames = RenderedFrameClock()
        val activityNotifications = AtomicInteger()
        val adapter = ThreadBoundVibrisRuntimeAdapter(
            StubHost(),
            frames,
            null,
            activityNotifications::incrementAndGet,
        )

        assertTrue(adapter.isIdle())
        val first = adapter.waitRenderedFrames(1, CancellationToken.none())
        val second = adapter.waitRenderedFrames(2, CancellationToken.none())
        assertFalse(adapter.isIdle())
        assertEquals(1, activityNotifications.get())

        frames.renderedFrame()
        assertEquals(1L, first.toCompletableFuture().join())
        assertFalse(adapter.isIdle())
        frames.renderedFrame()
        assertEquals(2L, second.toCompletableFuture().join())
        assertTrue(adapter.isIdle())

        val third = adapter.waitRenderedFrames(1, CancellationToken.none())
        assertEquals(2, activityNotifications.get())
        frames.renderedFrame()
        assertEquals(3L, third.toCompletableFuture().join())
        assertTrue(adapter.isIdle())
        adapter.close()
    }

    private class StubHost : VibrisRuntimeHost {
        override fun isClientThread(): Boolean = true

        override fun executeOnClient(task: Runnable) = task.run()

        override fun status(): RuntimeStatus = unsupported()

        override fun applyContext(
            context: SceneContext,
            cancellation: CancellationToken,
        ): CompletionStage<ContextApplyResult> = unsupported()

        override fun reload(config: Map<String, String>?, cancellation: CancellationToken): ReloadResult = unsupported()

        override fun resetTemporal(cancellation: CancellationToken): TemporalResetResult = unsupported()

        override fun resourceCatalog(frameId: Long): ResourceCatalog = unsupported()

        override fun capture(
            plan: CapturePlan,
            sink: ArtifactSink,
            frameId: Long,
            cancellation: CancellationToken,
        ): CaptureResult = unsupported()

        override fun close() = Unit

        private fun <T> unsupported(): T = throw UnsupportedOperationException()
    }
}
