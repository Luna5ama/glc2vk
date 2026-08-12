package dev.vibris.testruntime

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CompileCatalog
import dev.vibris.api.DeterministicTemporalCaptureOutcome
import dev.vibris.api.DeterministicTemporalCapturePlanner
import dev.vibris.api.DeterministicTemporalCapturePlanning
import dev.vibris.api.DeterministicTemporalCaptureRequest
import dev.vibris.api.ResourceCatalog
import dev.vibris.api.SceneContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FakeRuntimeAdapterTest {
    @Test
    fun completionCallbackMayCloseAdapterWithoutWaitingForItsOwnWorker() {
        val adapter = FakeRuntimeAdapter()
        val completion = adapter.captureDeterministicTemporalPhase(
            request(warmupFrames = 0),
            planned(capturePlan()),
            countingSink(AtomicInteger()),
            CancellationToken.none(),
        ).thenRun(adapter::close).toCompletableFuture()

        completion.get(5, TimeUnit.SECONDS)
        assertTrue(completion.isDone)
    }

    @Test
    fun plansOnceAfterContextAndAuthoritativeCatalogsThenCapturesExactTarget() {
        val adapter = FakeRuntimeAdapter()
        val compileCatalog = CompileCatalog.empty(42)
        adapter.replaceCatalogs(ResourceCatalog.empty(), compileCatalog)
        val plannerCalls = AtomicInteger()
        val sinkOpens = AtomicInteger()
        val plan = capturePlan()
        val request = request(warmupFrames = 2)

        val outcome = adapter.captureDeterministicTemporalPhase(
            request,
            DeterministicTemporalCapturePlanner { resources, compile ->
                plannerCalls.incrementAndGet()
                assertEquals(request.context, adapter.getStatus().toCompletableFuture().join().let { status ->
                    request.context.takeIf {
                        status.currentSaveId == it.saveId && status.currentDimensionId == it.dimensionId
                    }
                })
                assertEquals(ResourceCatalog.empty(), resources)
                assertSame(compileCatalog, compile)
                DeterministicTemporalCapturePlanning.Planned(plan)
            },
            countingSink(sinkOpens),
            CancellationToken.none(),
        ).toCompletableFuture().get(5, TimeUnit.SECONDS)

        assertTrue(outcome is DeterministicTemporalCaptureOutcome.Captured)
        outcome as DeterministicTemporalCaptureOutcome.Captured
        assertEquals(1, plannerCalls.get())
        assertSame(plan, outcome.plan)
        assertSame(compileCatalog, outcome.reloaded.compileCatalog)
        assertEquals(0, outcome.anchorFrame)
        assertEquals(2, outcome.warmupEndFrame)
        assertEquals(3, outcome.capture.frameId)
        assertEquals(1, sinkOpens.get())
        adapter.close()
    }

    @Test
    fun planningRejectionIsTypedAndDoesNotOpenSink() {
        val adapter = FakeRuntimeAdapter()
        val plannerCalls = AtomicInteger()
        val sinkOpens = AtomicInteger()
        val rejected = DeterministicTemporalCaptureOutcome.Failure(
            DeterministicTemporalCaptureOutcome.FailureKind.RESOURCE_NOT_FOUND,
            "missing framebuffer",
        )

        val outcome = adapter.captureDeterministicTemporalPhase(
            request(warmupFrames = 1),
            DeterministicTemporalCapturePlanner { _, _ ->
                plannerCalls.incrementAndGet()
                DeterministicTemporalCapturePlanning.Rejected(rejected)
            },
            countingSink(sinkOpens),
            CancellationToken.none(),
        ).toCompletableFuture().get(5, TimeUnit.SECONDS)

        assertTrue(outcome is DeterministicTemporalCaptureOutcome.PlanningRejected)
        outcome as DeterministicTemporalCaptureOutcome.PlanningRejected
        assertSame(rejected, outcome.failure)
        assertEquals(1, plannerCalls.get())
        assertEquals(0, sinkOpens.get())
        adapter.close()
    }

    @Test
    fun cancellationWhilePausedReturnsTypedWarmupRejectionWithoutLateSinkOpen() {
        val adapter = FakeRuntimeAdapter()
        val cancellation = CancellationToken.source()
        val sinkOpens = AtomicInteger()
        adapter.pauseExecution()

        val stage = adapter.captureDeterministicTemporalPhase(
            request(warmupFrames = 3),
            planned(capturePlan()),
            countingSink(sinkOpens),
            cancellation.token(),
        ).toCompletableFuture()
        awaitBoundary(adapter)

        cancellation.cancel()
        val outcome = stage.get(5, TimeUnit.SECONDS)

        assertTrue(outcome is DeterministicTemporalCaptureOutcome.WarmupRejected)
        outcome as DeterministicTemporalCaptureOutcome.WarmupRejected
        assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED, outcome.failure.kind)
        assertEquals(0, outcome.completedFrames)
        assertEquals(outcome.anchorFrame, outcome.currentFrame)
        assertEquals(0, sinkOpens.get())
        assertTrue(stage.isDone)
        adapter.close()
    }

    @Test
    fun closeWhilePausedWaitsForTypedWarmupRejectionAndPreventsLateSinkOpen() {
        val adapter = FakeRuntimeAdapter()
        val sinkOpens = AtomicInteger()
        adapter.pauseExecution()

        val stage = adapter.captureDeterministicTemporalPhase(
            request(warmupFrames = 3),
            planned(capturePlan()),
            countingSink(sinkOpens),
            CancellationToken.none(),
        ).toCompletableFuture()
        awaitBoundary(adapter)

        adapter.close()
        val outcome = stage.get(5, TimeUnit.SECONDS)

        assertTrue(outcome is DeterministicTemporalCaptureOutcome.WarmupRejected)
        outcome as DeterministicTemporalCaptureOutcome.WarmupRejected
        assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED, outcome.failure.kind)
        assertEquals(0, outcome.completedFrames)
        assertEquals(0, sinkOpens.get())
        assertTrue(stage.isDone)
    }

    @Test
    fun sinkFailureReturnsTypedCaptureRejectionWithExactTargetAndTerminalFrame() {
        val adapter = FakeRuntimeAdapter()
        val plan = capturePlan()

        val outcome = adapter.captureDeterministicTemporalPhase(
            request(warmupFrames = 0),
            DeterministicTemporalCapturePlanner { _, _ ->
                DeterministicTemporalCapturePlanning.Planned(plan)
            },
            ArtifactSink { throw IOException("sink failed") },
            CancellationToken.none(),
        ).toCompletableFuture().get(5, TimeUnit.SECONDS)

        assertTrue(outcome is DeterministicTemporalCaptureOutcome.CaptureRejected)
        outcome as DeterministicTemporalCaptureOutcome.CaptureRejected
        assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED, outcome.failure.kind)
        assertEquals(1, outcome.targetFrame)
        assertEquals(1, outcome.terminalFrame)
        adapter.close()
    }

    @Test
    fun registeredTargetBlocksConcurrentFrameAdvanceUntilCaptureIoCompletes() {
        val adapter = FakeRuntimeAdapter()
        val sink = BlockingOpenSink()
        val stage = adapter.captureDeterministicTemporalPhase(
            request(warmupFrames = 0),
            planned(capturePlan()),
            sink,
            CancellationToken.none(),
        ).toCompletableFuture()
        awaitBoundary(adapter)
        assertTrue(sink.awaitOpen())
        assertEquals(1, adapter.renderedFrame())

        val concurrentWait = adapter.waitRenderedFrames(1, CancellationToken.none()).toCompletableFuture()
        assertFalse(completesWithin(concurrentWait, 100))
        assertEquals(1, adapter.renderedFrame())

        sink.releaseOpen()
        val outcome = stage.get(5, TimeUnit.SECONDS)
        assertTrue(outcome is DeterministicTemporalCaptureOutcome.Captured)
        assertEquals(2, concurrentWait.get(5, TimeUnit.SECONDS))
        adapter.close()
    }

    @Test
    fun cancellationDuringBlockingOpenClosesStreamWithoutWriting() {
        val adapter = FakeRuntimeAdapter()
        val cancellation = CancellationToken.source()
        val sink = BlockingOpenSink()
        val stage = adapter.captureDeterministicTemporalPhase(
            request(warmupFrames = 0),
            planned(capturePlan()),
            sink,
            cancellation.token(),
        ).toCompletableFuture()
        awaitBoundary(adapter)
        assertTrue(sink.awaitOpen())

        cancellation.cancel()
        sink.releaseOpen()
        val outcome = stage.get(5, TimeUnit.SECONDS)

        assertTrue(outcome is DeterministicTemporalCaptureOutcome.CaptureRejected)
        outcome as DeterministicTemporalCaptureOutcome.CaptureRejected
        assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED, outcome.failure.kind)
        assertEquals(1, outcome.targetFrame)
        assertEquals(1, outcome.terminalFrame)
        assertEquals(1, sink.closes.get())
        assertEquals(0, sink.writes.get())
        adapter.close()
    }

    @Test
    fun closeDuringBlockingOpenSetsClosedPromptlyAndWaitsForStreamCleanup() {
        val adapter = FakeRuntimeAdapter()
        val sink = BlockingOpenSink()
        val stage = adapter.captureDeterministicTemporalPhase(
            request(warmupFrames = 0),
            planned(capturePlan()),
            sink,
            CancellationToken.none(),
        ).toCompletableFuture()
        awaitBoundary(adapter)
        assertTrue(sink.awaitOpen())

        val closeStage = CompletableFuture.runAsync(adapter::close)
        awaitClosed(adapter)
        assertFalse(closeStage.isDone)

        sink.releaseOpen()
        val outcome = stage.get(5, TimeUnit.SECONDS)
        closeStage.get(5, TimeUnit.SECONDS)

        assertTrue(outcome is DeterministicTemporalCaptureOutcome.CaptureRejected)
        outcome as DeterministicTemporalCaptureOutcome.CaptureRejected
        assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED, outcome.failure.kind)
        assertEquals(1, sink.closes.get())
        assertEquals(0, sink.writes.get())
    }

    private fun planned(
        plan: CapturePlan,
    ): DeterministicTemporalCapturePlanner = DeterministicTemporalCapturePlanner { _, _ ->
        DeterministicTemporalCapturePlanning.Planned(plan)
    }

    private fun awaitBoundary(adapter: FakeRuntimeAdapter) {
        assertTrue(adapter.awaitDeterministicBoundaryRegistration(5, TimeUnit.SECONDS))
    }

    private fun awaitClosed(adapter: FakeRuntimeAdapter) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!adapter.isClosedForTests() && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        assertTrue(adapter.isClosedForTests())
    }

    private fun completesWithin(stage: CompletableFuture<*>, millis: Long): Boolean =
        try {
            stage.get(millis, TimeUnit.MILLISECONDS)
            true
        } catch (_: java.util.concurrent.TimeoutException) {
            false
        }

    private fun countingSink(opens: AtomicInteger): ArtifactSink = ArtifactSink {
        opens.incrementAndGet()
        ByteArrayOutputStream()
    }

    private fun request(warmupFrames: Int): DeterministicTemporalCaptureRequest =
        DeterministicTemporalCaptureRequest(
            SceneContext(
                "capture-save",
                "minecraft:the_nether",
                "noon",
                "clear",
                "origin",
                70.0,
                SceneContext.Resolution(640, 360),
                "default",
            ),
            true,
            emptyMap(),
            warmupFrames,
        )

    private fun capturePlan(): CapturePlan = CapturePlan(
        listOf(
            CapturePlan.Target(
                CapturePlan.ResourceSelector(
                    ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER,
                    "final",
                    null,
                    0,
                    0,
                ),
                CapturePlan.ArtifactFormat.PNG,
                "screenshot",
                listOf(
                    CapturePlan.ArtifactOutputSpec(
                        "screenshot.png",
                        CapturePlan.ArtifactFormat.PNG,
                        CapturePlan.ArtifactRole.PRIMARY,
                        null,
                    ),
                ),
            ),
        ),
    )

    private class BlockingOpenSink : ArtifactSink {
        private val openEntered = CountDownLatch(1)
        private val openRelease = CountDownLatch(1)
        val closes = AtomicInteger()
        val writes = AtomicInteger()

        override fun open(artifactName: String): OutputStream {
            openEntered.countDown()
            if (!openRelease.await(5, TimeUnit.SECONDS)) {
                throw IOException("Timed out waiting to release fake artifact open")
            }
            return object : OutputStream() {
                override fun write(value: Int) {
                    writes.incrementAndGet()
                }

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    writes.incrementAndGet()
                }

                override fun close() {
                    closes.incrementAndGet()
                }
            }
        }

        fun awaitOpen(): Boolean = openEntered.await(5, TimeUnit.SECONDS)

        fun releaseOpen() {
            openRelease.countDown()
        }
    }
}