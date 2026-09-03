package dev.vibris.mod;

import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.CompileCatalog;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.DeterministicTemporalCaptureOutcome;
import dev.vibris.api.DeterministicTemporalCaptureReloaded;
import dev.vibris.api.EffectiveShaderSettings;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.RuntimeEnvironment;
import dev.vibris.api.SceneContext;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.core.ArtifactManager;
import dev.vibris.core.DeterministicTemporalCaptureScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftVibrisRuntimeHostTest {
	private VibrisTime.Scope activeTimeScope;

	@AfterEach
	void resetSystemTime() {
		if (activeTimeScope != null) activeTimeScope.close();
		activeTimeScope = null;
	}

	@Test
	void preservesExactMaintainedRuntimeIdentity() {
		RuntimeEnvironment environment = MinecraftVibrisRuntimeHost.runtimeEnvironment(
			"1.21.11",
			"1.10.6-vibris.1+mc1.21.11",
			"0.1.0-alpha.1",
			"21.0.8+9-LTS",
			"Windows x86_64",
			"NVIDIA Corporation",
			"NVIDIA GeForce RTX 5090/PCIe/SSE2",
			"4.6.0 NVIDIA 581.29",
			"4.6.0 NVIDIA 581.29"
		);

		assertEquals("1.21.11", environment.minecraftVersion());
		assertEquals("1.10.6-vibris.1+mc1.21.11", environment.irisVersion());
		assertEquals("0.1.0-alpha.1", environment.vibrisVersion());
		assertEquals("21.0.8+9-LTS", environment.javaVersion());
		assertEquals("Windows x86_64", environment.operatingSystem());
		assertEquals("NVIDIA Corporation", environment.gpuVendor());
		assertEquals("NVIDIA GeForce RTX 5090/PCIe/SSE2", environment.gpuRenderer());
		assertEquals("4.6.0 NVIDIA 581.29", environment.openglVersion());
		assertEquals("4.6.0 NVIDIA 581.29", environment.driverVersion());
	}

	@Test
	void successfulScheduledCaptureExitsDeterministicTimeBeforePublishing() {
		CapturePlan plan = capturePlan();
		CaptureResult capture = capture(plan, 13);
		DeterministicTemporalCaptureScheduler.ScheduledCapture scheduled = scheduled(2, 10, 13, capture);
		CompletableFuture<DeterministicTemporalCaptureOutcome> result = new CompletableFuture<>();
		activeTimeScope = VibrisTime.begin(0L);

		MinecraftVibrisRuntimeHost.completeScheduledCapture(
			result, reloaded(), plan, new TemporalResetResult(true), 2, scheduled, activeTimeScope, capture, null);

		DeterministicTemporalCaptureOutcome.Captured outcome = assertInstanceOf(
			DeterministicTemporalCaptureOutcome.Captured.class, result.join());
		assertEquals(10, outcome.anchorFrame());
		assertEquals(12, outcome.warmupEndFrame());
		assertEquals(13, outcome.capture().frameId());
		assertRealTimeRestored();
	}

	@Test
	void warmupCancellationExitsDeterministicTimeBeforePublishing() {
		CapturePlan plan = capturePlan();
		CaptureResult capture = capture(plan, 14);
		DeterministicTemporalCaptureScheduler.ScheduledCapture scheduled = scheduled(3, 10, 11, capture);
		CompletableFuture<DeterministicTemporalCaptureOutcome> result = new CompletableFuture<>();
		activeTimeScope = VibrisTime.begin(0L);

		MinecraftVibrisRuntimeHost.completeScheduledCapture(
			result, reloaded(), plan, new TemporalResetResult(true), 2, scheduled, activeTimeScope, null,
			new CancellationException("cancelled"));

		DeterministicTemporalCaptureOutcome.WarmupRejected outcome = assertInstanceOf(
			DeterministicTemporalCaptureOutcome.WarmupRejected.class, result.join());
		assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.CANCELLED, outcome.failure().kind());
		assertEquals(1, outcome.completedFrames());
		assertEquals(11, outcome.currentFrame());
		assertRealTimeRestored();
	}

	@Test
	void captureFailureExitsDeterministicTimeBeforePublishing() {
		CapturePlan plan = capturePlan();
		CaptureResult capture = capture(plan, 12);
		DeterministicTemporalCaptureScheduler.ScheduledCapture scheduled = scheduled(1, 10, 12, capture);
		CompletableFuture<DeterministicTemporalCaptureOutcome> result = new CompletableFuture<>();
		activeTimeScope = VibrisTime.begin(0L);

		MinecraftVibrisRuntimeHost.completeScheduledCapture(
			result, reloaded(), plan, new TemporalResetResult(true), 2, scheduled, activeTimeScope, null,
			new IllegalStateException("capture failed"));

		DeterministicTemporalCaptureOutcome.CaptureRejected outcome = assertInstanceOf(
			DeterministicTemporalCaptureOutcome.CaptureRejected.class, result.join());
		assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED, outcome.failure().kind());
		assertEquals(12, outcome.targetFrame());
		assertEquals(12, outcome.terminalFrame());
		assertRealTimeRestored();
	}

	@Test
	void cleanupFailureAfterSuccessfulCaptureIsTypedAndAttemptedBeforePublishing() {
		CapturePlan plan = capturePlan();
		CaptureResult capture = capture(plan, 12);
		DeterministicTemporalCaptureScheduler.ScheduledCapture scheduled = scheduled(1, 10, 12, capture);
		CompletableFuture<DeterministicTemporalCaptureOutcome> result = new CompletableFuture<>();
		AtomicBoolean cleanupAttempted = new AtomicBoolean();
		AutoCloseable captureScope = () -> {
			cleanupAttempted.set(true);
			throw new IllegalStateException("cleanup failed");
		};

		MinecraftVibrisRuntimeHost.completeScheduledCapture(
			result, reloaded(), plan, new TemporalResetResult(true), 2, scheduled, captureScope, capture, null);

		DeterministicTemporalCaptureOutcome.CaptureRejected outcome = assertInstanceOf(
			DeterministicTemporalCaptureOutcome.CaptureRejected.class, result.join());
		assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.CLEANUP_FAILED, outcome.failure().kind());
		assertTrue(cleanupAttempted.get());
	}

	@Test
	void preAnchorScopeFailureIsResetRejectedWithoutFrameEvidence() {
		CompletableFuture<DeterministicTemporalCaptureOutcome> result = new CompletableFuture<>();
		IllegalStateException resetFailure = new IllegalStateException("atlas reset failed");
		IllegalStateException cleanupFailure = new IllegalStateException("time cleanup failed");

		MinecraftVibrisRuntimeHost.completeResetRejected(
			result, reloaded(), capturePlan(), resetFailure, cleanupFailure);

		DeterministicTemporalCaptureOutcome.ResetRejected outcome = assertInstanceOf(
			DeterministicTemporalCaptureOutcome.ResetRejected.class, result.join());
		assertFalse(outcome.reset().successful());
		assertEquals(DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED, outcome.failure().kind());
		assertEquals(List.of(cleanupFailure), List.of(resetFailure.getSuppressed()));
	}

	@Test
	void wrappedArtifactSizeFailureRetainsTypedOutcome() {
		assertWrappedArtifactFailure(
			new UncheckedIOException(new ArtifactManager.JobTooLargeException(2, 1)),
			DeterministicTemporalCaptureOutcome.FailureKind.ARTIFACT_TOO_LARGE
		);
	}

	@Test
	void wrappedArtifactQuotaFailureRetainsTypedOutcome() {
		assertWrappedArtifactFailure(
			new UncheckedIOException(new ArtifactManager.QuotaExceededException(1)),
			DeterministicTemporalCaptureOutcome.FailureKind.ARTIFACT_QUOTA_EXCEEDED
		);
	}

	private void assertWrappedArtifactFailure(
		Throwable failure,
		DeterministicTemporalCaptureOutcome.FailureKind expectedKind
	) {
		CapturePlan plan = capturePlan();
		CaptureResult capture = capture(plan, 12);
		DeterministicTemporalCaptureScheduler.ScheduledCapture scheduled = scheduled(1, 10, 12, capture);
		CompletableFuture<DeterministicTemporalCaptureOutcome> result = new CompletableFuture<>();
		activeTimeScope = VibrisTime.begin(0L);

		MinecraftVibrisRuntimeHost.completeScheduledCapture(
			result, reloaded(), plan, new TemporalResetResult(true), 2, scheduled, activeTimeScope, null, failure);

		DeterministicTemporalCaptureOutcome.CaptureRejected outcome = assertInstanceOf(
			DeterministicTemporalCaptureOutcome.CaptureRejected.class, result.join());
		assertEquals(expectedKind, outcome.failure().kind());
		assertRealTimeRestored();
	}

	private void assertRealTimeRestored() {
		assertFalse(VibrisTime.active());
	}

	private static DeterministicTemporalCaptureScheduler.ScheduledCapture scheduled(
		int warmupFrames,
		long anchorFrame,
		long terminalFrame,
		CaptureResult capture
	) {
		long warmupEndFrame = anchorFrame + warmupFrames;
		long captureFrame = warmupEndFrame + 1;
		return new DeterministicTemporalCaptureScheduler.ScheduledCapture(
			warmupFrames,
			anchorFrame,
			warmupEndFrame,
			captureFrame,
			CompletableFuture.completedFuture(terminalFrame),
			CompletableFuture.completedFuture(capture)
		);
	}

	private static DeterministicTemporalCaptureReloaded reloaded() {
		return new DeterministicTemporalCaptureReloaded(
			ContextApplyResult.success(context()),
			ReloadResult.success(EffectiveShaderSettings.empty(), List.of()),
			1,
			ResourceCatalog.empty(),
			CompileCatalog.empty(1)
		);
	}

	private static SceneContext context() {
		return new SceneContext(
			"test-save", "minecraft:overworld", "noon", "clear", "origin", 70.0,
			new SceneContext.Resolution(1280, 720), "default");
	}

	private static CapturePlan capturePlan() {
		return new CapturePlan(List.of(new CapturePlan.Target(
			new CapturePlan.ResourceSelector(
				ResourceCatalog.ResourceKind.TEXTURE,
				"colortex0",
				ResourceCatalog.TextureView.CURRENT,
				0,
				0
			),
			CapturePlan.ArtifactFormat.PNG,
			"colortex0",
			List.of()
		)));
	}

	private static CaptureResult capture(CapturePlan plan, long frameId) {
		CapturePlan.Target target = plan.targets().getFirst();
		ResourceCatalog.ResourceDescriptor resource = ResourceCatalog.ResourceDescriptor.of(
			target.resource().logicalName(),
			ResourceCatalog.ResourceKind.TEXTURE,
			List.of(ResourceCatalog.TextureView.CURRENT),
			1,
			1,
			1,
			1,
			1,
			"RGBA8",
			4,
			ResourceCatalog.ScalarType.UINT8,
			4,
			frameId,
			"test color",
			"test",
			"texture_2d",
			"RGBA",
			"uint",
			8,
			"RGBA",
			"UNSIGNED_BYTE"
		);
		return new CaptureResult(frameId, List.of(new CaptureResult.ArtifactGroup(
			target.artifactName(), resource, List.of())));
	}
}
