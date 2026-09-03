package dev.vibris.mod;

import dev.vibris.mod.mixinterface.VibrisTerrainQuiescence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftContextControllerTest {
	private static final Object REGULAR_RENDER_LIST_IDENTITY = new Object();
	private static final Object REGULAR_REGION_IDENTITY = new Object();
	private static final Object SHADOW_RENDER_LIST_IDENTITY = new Object();
	private static final Object SHADOW_REGION_IDENTITY = new Object();

	@Test
	void sceneReadyGateRequiresBothConsecutiveFramesAndStableDuration() {
		MinecraftContextController.SceneReadyGate gate = new MinecraftContextController.SceneReadyGate(3, 100);
		var signature = signature(20, 10);

		assertFalse(gate.observe(10, 0, true, signature));
		assertFalse(gate.observe(11, 50, true, signature));
		assertFalse(gate.observe(12, 99, true, signature));
		assertTrue(gate.observe(13, 100, true, signature));

		MinecraftContextController.SceneReadyGate frameLimited =
			new MinecraftContextController.SceneReadyGate(3, 100);
		assertFalse(frameLimited.observe(20, 0, true, signature));
		assertFalse(frameLimited.observe(21, 100, true, signature));
	}

	@Test
	void sceneReadyGateRestartsOnGapFailureDuplicateOrExactSignatureChange() {
		var first = signature(20, 10);
		var sameCountDifferentIdentity = signature(21, 10);

		MinecraftContextController.SceneReadyGate duplicate =
			new MinecraftContextController.SceneReadyGate(3, 0);
		assertFalse(duplicate.observe(10, 10, true, first));
		assertFalse(duplicate.observe(11, 11, true, first));
		assertFalse(duplicate.observe(11, 12, true, first));
		assertFalse(duplicate.observe(12, 13, true, first));
		assertTrue(duplicate.observe(13, 14, true, first));

		MinecraftContextController.SceneReadyGate gap = new MinecraftContextController.SceneReadyGate(2, 0);
		assertFalse(gap.observe(10, 10, true, first));
		assertFalse(gap.observe(12, 12, true, first));
		assertTrue(gap.observe(13, 13, true, first));

		MinecraftContextController.SceneReadyGate failure =
			new MinecraftContextController.SceneReadyGate(2, 0);
		assertFalse(failure.observe(10, 10, true, first));
		assertFalse(failure.observe(11, 11, false, null));
		assertFalse(failure.observe(12, 12, true, first));
		assertTrue(failure.observe(13, 13, true, first));

		MinecraftContextController.SceneReadyGate identity =
			new MinecraftContextController.SceneReadyGate(2, 0);
		assertFalse(identity.observe(10, 10, true, first));
		assertFalse(identity.observe(11, 11, true, sameCountDifferentIdentity));
		assertTrue(identity.observe(12, 12, true, sameCountDifferentIdentity));
	}

	@Test
	void sceneReadyGateDetectsUploadChangesAndAcceptsStableZeroGeometry() {
		MinecraftContextController.SceneReadyGate changedUpload =
			new MinecraftContextController.SceneReadyGate(2, 0);
		assertFalse(changedUpload.observe(1, 1, true, signature(20, 10)));
		assertFalse(changedUpload.observe(2, 2, true, signature(20, 11)));
		assertTrue(changedUpload.observe(3, 3, true, signature(20, 11)));

		MinecraftContextController.SceneReadyGate empty = new MinecraftContextController.SceneReadyGate(1, 0);
		assertTrue(empty.observe(1, 1, true, new MinecraftContextController.SceneSignature(
			0,
			0,
			16,
			new VibrisTerrainQuiescence.TerrainSnapshot(
				true, List.of(), List.of(), List.of(), false, List.of(), "")
		)));
	}

	@Test
	void sceneReadyGateDetectsGlobalEntitySectionChanges() {
		MinecraftContextController.SceneReadyGate gate = new MinecraftContextController.SceneReadyGate(2, 0);

		assertFalse(gate.observe(1, 1, true, globalEntitySignature(10)));
		assertFalse(gate.observe(2, 2, true, globalEntitySignature(11)));
		assertTrue(gate.observe(3, 3, true, globalEntitySignature(11)));
	}

	@Test
	void sceneReadyGateDetectsExactShadowPendingUpdateChanges() {
		MinecraftContextController.SceneReadyGate gate = new MinecraftContextController.SceneReadyGate(2, 0);

		assertFalse(gate.observe(1, 1, true, shadowSignature(2, 10)));
		assertFalse(gate.observe(2, 2, true, shadowSignature(2, 11)));
		assertFalse(gate.observe(3, 3, true, shadowSignature(4, 11)));
		assertTrue(gate.observe(4, 4, true, shadowSignature(4, 11)));
	}

	@Test
	void sceneReadyGateDetectsExactRenderListTraversalOrderChanges() {
		MinecraftContextController.SceneReadyGate gate = new MinecraftContextController.SceneReadyGate(2, 0);

		assertFalse(gate.observe(1, 1, true, orderedSignature(20, 21)));
		assertFalse(gate.observe(2, 2, true, orderedSignature(21, 20)));
		assertTrue(gate.observe(3, 3, true, orderedSignature(21, 20)));
	}

	@Test
	void sceneReadyGateRejectsInvalidRequirementsAndBackwardTime() {
		assertThrows(IllegalArgumentException.class, () -> new MinecraftContextController.SceneReadyGate(0, 0));
		assertThrows(IllegalArgumentException.class, () -> new MinecraftContextController.SceneReadyGate(1, -1));

		MinecraftContextController.SceneReadyGate gate = new MinecraftContextController.SceneReadyGate(2, 0);
		var signature = signature(20, 10);
		assertFalse(gate.observe(1, 10, true, signature));
		assertFalse(gate.observe(2, 9, true, signature));
		assertTrue(gate.observe(3, 10, true, signature));
	}

	@Test
	void completeRenderViewCoverageAtDistanceSixteenContains921Chunks() {
		MinecraftContextController.ViewCoverage coverage =
			MinecraftContextController.inspectRenderViewCoverage(0, 0, 16, ignored -> true);

		assertEquals(921, coverage.expected());
		assertEquals(0, coverage.missing());
		assertTrue(coverage.complete());
	}

	@Test
	void cameraNeighborhoodAloneCannotSatisfyFullRenderViewCoverage() {
		MinecraftContextController.ViewCoverage coverage =
			MinecraftContextController.inspectRenderViewCoverage(0, 0, 16, packed ->
				Math.abs(MinecraftContextController.unpackChunkX(packed)) <= 1 &&
					Math.abs(MinecraftContextController.unpackChunkZ(packed)) <= 1);

		assertEquals(921, coverage.expected());
		assertEquals(912, coverage.missing());
		assertFalse(coverage.complete());
	}

	@Test
	void missingFarInteriorChunkFailsEvenWhenCardinalityCouldStayConstant() {
		long missing = MinecraftContextController.packChunk(16, 0);
		MinecraftContextController.ViewCoverage coverage =
			MinecraftContextController.inspectRenderViewCoverage(0, 0, 16, packed -> packed != missing);

		assertEquals(1, coverage.missing());
		assertEquals(16, coverage.firstMissingX());
		assertEquals(0, coverage.firstMissingZ());
		assertFalse(coverage.complete());

		MinecraftContextController.ViewCoverage negative =
			MinecraftContextController.inspectRenderViewCoverage(-31, -47, 16, ignored -> true);
		assertEquals(921, negative.expected());
		assertTrue(negative.complete());
	}

	@Test
	void differentLoadedSaveRequiresAutonomousSwitch() {
		assertTrue(MinecraftContextController.requiresSaveSwitch("New World (7)", "craftcollection2"));
		assertFalse(MinecraftContextController.requiresSaveSwitch("New World (7)", "New World (7)"));
	}

	private static MinecraftContextController.SceneSignature signature(long position, int lastUploadFrame) {
		return new MinecraftContextController.SceneSignature(
			0,
			0,
			16,
			new VibrisTerrainQuiescence.TerrainSnapshot(
				true,
				List.of(renderListState(
					false,
					REGULAR_RENDER_LIST_IDENTITY,
					REGULAR_REGION_IDENTITY,
					List.of(new VibrisTerrainQuiescence.SectionState(
						position, 1, 2, lastUploadFrame, 0, 0, null)))),
				List.of(),
				List.of(),
				false,
				List.of(),
				""
			)
		);
	}

	private static MinecraftContextController.SceneSignature shadowSignature(
		int pendingUpdateType,
		long pendingUpdateSince
	) {
		return new MinecraftContextController.SceneSignature(
			0,
			0,
			16,
			new VibrisTerrainQuiescence.TerrainSnapshot(
				true,
				List.of(),
				List.of(renderListState(
					false,
					SHADOW_RENDER_LIST_IDENTITY,
					SHADOW_REGION_IDENTITY,
					List.of(new VibrisTerrainQuiescence.SectionState(
						20, 1, 2, 10, pendingUpdateType, pendingUpdateSince, null)))),
				List.of(),
				false,
				List.of(),
				""
			)
		);
	}

	private static MinecraftContextController.SceneSignature orderedSignature(long first, long second) {
		return new MinecraftContextController.SceneSignature(
			0,
			0,
			16,
			new VibrisTerrainQuiescence.TerrainSnapshot(
				true,
				List.of(renderListState(
					false,
					REGULAR_RENDER_LIST_IDENTITY,
					REGULAR_REGION_IDENTITY,
					List.of(
						new VibrisTerrainQuiescence.SectionState(first, 1, 2, 10, 0, 0, null),
						new VibrisTerrainQuiescence.SectionState(second, 1, 2, 10, 0, 0, null)
					))),
				List.of(),
				List.of(),
				false,
				List.of(),
				""
			)
		);
	}

	private static VibrisTerrainQuiescence.RenderListState renderListState(
		boolean reverse,
		Object identity,
		Object regionIdentity,
		List<VibrisTerrainQuiescence.SectionState> geometrySections
	) {
		return new VibrisTerrainQuiescence.RenderListState(
			reverse, identity, regionIdentity, geometrySections, List.of(), List.of());
	}

	private static MinecraftContextController.SceneSignature globalEntitySignature(int lastUploadFrame) {
		return new MinecraftContextController.SceneSignature(
			0,
			0,
			16,
			new VibrisTerrainQuiescence.TerrainSnapshot(
				true,
				List.of(),
				List.of(),
				List.of(),
				false,
				List.of(new VibrisTerrainQuiescence.GlobalEntitySectionState(
					new VibrisTerrainQuiescence.SectionState(20, 1, 2, lastUploadFrame, 0, 0, null),
					List.of()
				)),
				""
			)
		);
	}
}