package dev.vibris.mod;

import com.google.common.collect.ImmutableSet;
import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ResourceCatalog;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisVibrisPassCaptureTest {
	private static final ArtifactSink SINK = ignored -> new ByteArrayOutputStream();

	@Test
	void registersStableOrderedPassDescriptors() {
		FakeBackend backend = new FakeBackend();
		IrisVibrisPassCapture capture = new IrisVibrisPassCapture(backend);
		capture.register(ResourceCatalog.PassStage.BEGIN, "begin1");
		capture.register(ResourceCatalog.PassStage.PREPARE, "prepare2");
		capture.register(ResourceCatalog.PassStage.DEFERRED, "deferred3");
		capture.register(ResourceCatalog.PassStage.COMPOSITE, "composite4");
		capture.register(ResourceCatalog.PassStage.FINAL, "final");
		capture.register(ResourceCatalog.PassStage.SHADOW_COMPOSITE, "shadowcomp0");

		ResourceCatalog catalog = capture.resourceCatalog(7);
		assertEquals(List.of(
			"begin/begin1", "prepare/prepare2", "deferred/deferred3",
			"composite/composite4", "final/final", "shadow_composite/shadowcomp0"),
			catalog.passes().stream().map(ResourceCatalog.PassDescriptor::passId).toList());
		assertEquals(List.of("colortex0", "iris_ssbo_6"), catalog.passes().getFirst().readableResources());
		assertEquals(List.of(0, 1, 2, 3, 4, 5),
			catalog.passes().stream().map(ResourceCatalog.PassDescriptor::order).toList());
	}

	@Test
	void currentUsesTheNextStageReadablePhysicalSide() {
		assertFalse(IrisVibrisPassCapture.currentIsAlt("colortex0", Set.of(), Set.of()));
		assertTrue(IrisVibrisPassCapture.currentIsAlt("colortex0", Set.of(0), Set.of()));
		assertFalse(IrisVibrisPassCapture.currentIsAlt("shadowcolor1", Set.of(1), Set.of()));
		assertTrue(IrisVibrisPassCapture.currentIsAlt("shadowcolor1", Set.of(), Set.of(1)));
		assertTrue(IrisVibrisPassCapture.selectAlt(ResourceCatalog.TextureView.CURRENT, true));
		assertFalse(IrisVibrisPassCapture.selectAlt(ResourceCatalog.TextureView.ALTERNATE, true));
		assertFalse(IrisVibrisPassCapture.selectAlt(ResourceCatalog.TextureView.MAIN, true));
		assertTrue(IrisVibrisPassCapture.selectAlt(ResourceCatalog.TextureView.ALT, false));
	}

	@Test
	void duplicateLogicalResourcesFailBeforeBuildingPassDescriptors() {
		IrisVibrisPassCapture capture = new IrisVibrisPassCapture(new FakeBackend());
		IllegalStateException failure = assertThrows(
			IllegalStateException.class,
			() -> capture.resourceCatalog(List.of(texture(1), texture(1))));
		assertEquals("Duplicate Vibris resource logical name: colortex0", failure.getMessage());
	}

	@Test
	void exactBoundaryProducesOneShotReceiptAfterFlipSnapshot() {
		FakeBackend backend = new FakeBackend();
		IrisVibrisPassCapture capture = new IrisVibrisPassCapture(backend);
		IrisVibrisPassCapture.PassHandle handle =
			capture.register(ResourceCatalog.PassStage.COMPOSITE, "composite7");
		ResourceCatalog catalog = capture.resourceCatalog(0);
		CapturePlan.AfterPassRequest request = request(catalog, catalog.passes().getFirst());

		var receipt = capture.schedule(request, SINK, CancellationToken.none(), catalog).toCompletableFuture();
		assertFalse(receipt.isDone());
		capture.captureBoundary(handle, ImmutableSet.of(0));

		CapturePlan.AfterPassReceipt result = receipt.join();
		assertEquals(1, result.passOccurrence());
		assertEquals("colortex0.alt", result.physicalName());
		assertEquals(1, result.capture().groups().size());
		assertEquals(1, backend.snapshots.get());

		capture.captureBoundary(handle, ImmutableSet.of());
		assertEquals(1, backend.snapshots.get(), "a completed request must not remain attached to the pass");
	}

	@Test
	void unknownCancelledAndTimedOutRequestsLeaveNoBoundaryHook() {
		FakeBackend backend = new FakeBackend();
		IrisVibrisPassCapture capture = new IrisVibrisPassCapture(backend);
		IrisVibrisPassCapture.PassHandle handle =
			capture.register(ResourceCatalog.PassStage.COMPOSITE, "composite7");
		ResourceCatalog catalog = capture.resourceCatalog(0);

		ResourceCatalog.PassDescriptor unknown = ResourceCatalog.PassDescriptor.of(
			ResourceCatalog.PassStage.COMPOSITE, "missing", 99, List.of("colortex0"));
		var unknownFuture = capture.schedule(request(catalog, unknown), SINK, CancellationToken.none(), catalog)
			.toCompletableFuture();
		assertInstanceOf(IllegalArgumentException.class,
			assertThrows(CompletionException.class, unknownFuture::join).getCause());

		CancellationToken.Source cancelled = CancellationToken.source();
		cancelled.cancel();
		var cancelledFuture = capture.schedule(
			request(catalog, catalog.passes().getFirst()), SINK, cancelled.token(), catalog).toCompletableFuture();
		assertThrows(java.util.concurrent.CancellationException.class, cancelledFuture::join);

		var timedOut = capture.schedule(
			request(catalog, catalog.passes().getFirst()), SINK, CancellationToken.none(), catalog)
			.toCompletableFuture();
		assertTrue(timedOut.completeExceptionally(new java.util.concurrent.TimeoutException("deadline")));
		capture.captureBoundary(handle, ImmutableSet.of(0));
		assertEquals(0, backend.snapshots.get());
	}

	private static CapturePlan.AfterPassRequest request(
		ResourceCatalog catalog,
		ResourceCatalog.PassDescriptor pass
	) {
		CapturePlan.ResourceSelector selector = new CapturePlan.ResourceSelector(
			ResourceCatalog.ResourceKind.TEXTURE, "colortex0", ResourceCatalog.TextureView.CURRENT, 0, 0);
		CapturePlan.ArtifactOutputSpec output = new CapturePlan.ArtifactOutputSpec(
			"colortex0.bin", CapturePlan.ArtifactFormat.BIN, CapturePlan.ArtifactRole.PRIMARY, null);
		CapturePlan.Target target = new CapturePlan.Target(
			selector, CapturePlan.ArtifactFormat.BIN, "colortex0", List.of(output));
		return new CapturePlan.AfterPassRequest(catalog.mappingSha256(), pass, target);
	}

	private static ResourceCatalog.ResourceDescriptor texture(long frameId) {
		return ResourceCatalog.ResourceDescriptor.of(
			"colortex0", ResourceCatalog.ResourceKind.TEXTURE,
			List.of(ResourceCatalog.TextureView.CURRENT, ResourceCatalog.TextureView.ALTERNATE,
				ResourceCatalog.TextureView.MAIN, ResourceCatalog.TextureView.ALT),
			2, 2, 1, 1, 1, "RGBA8", 4, ResourceCatalog.ScalarType.UINT8, 16, frameId,
			"colortex0", "colortex", "texture_2d", "RGBA", "unorm", 8,
			"RGBA", "UNSIGNED_BYTE");
	}

	private static ResourceCatalog.ResourceDescriptor buffer(long frameId) {
		return ResourceCatalog.ResourceDescriptor.of(
			"iris_ssbo_6", ResourceCatalog.ResourceKind.BUFFER, List.of(),
			0, 0, 0, 0, 0, "binary", 0, ResourceCatalog.ScalarType.UINT8, 16, frameId,
			"iris_ssbo_6", "iris_ssbo", "", "", "", 0, "", "");
	}

	private static final class FakeBackend implements IrisVibrisPassCapture.Backend {
		private final AtomicInteger snapshots = new AtomicInteger();

		@Override
		public List<ResourceCatalog.ResourceDescriptor> resources(long frameId) {
			return new ArrayList<>(List.of(texture(frameId), buffer(frameId)));
		}

		@Override
		public IrisVibrisPassCapture.OwnedSnapshot snapshot(
			CapturePlan.ResourceSelector selector,
			Set<Integer> mainFlips,
			Set<Integer> shadowFlips
		) {
			snapshots.incrementAndGet();
			String physical = IrisVibrisPassCapture.currentIsAlt(selector.logicalName(), mainFlips, shadowFlips)
				? "colortex0.alt" : "colortex0.main";
			return new IrisVibrisPassCapture.OwnedSnapshot() {
				@Override
				public String physicalName() {
					return physical;
				}

				@Override
				public CaptureResult write(
					CapturePlan.Target target,
					ArtifactSink sink,
					long frameId,
					CancellationToken cancellation
				) {
					CaptureResult.CapturedArtifact artifact = new CaptureResult.CapturedArtifact(
						target.outputs().getFirst().fileName(), target.outputs().getFirst().format(),
						target.outputs().getFirst().role(), null);
					return new CaptureResult(frameId, List.of(
						new CaptureResult.ArtifactGroup(target.artifactName(), texture(frameId), List.of(artifact))));
				}

				@Override
				public void close() {
				}
			};
		}

		@Override
		public IrisVibrisPassCapture.ResolvedResource resolve(
			CapturePlan.ResourceSelector selector,
			Set<Integer> mainFlips,
			Set<Integer> shadowFlips
		) {
			return new IrisVibrisPassCapture.ResolvedResource(selector.logicalName(), 1);
		}
	}
}