package dev.vibris.mod;

import dev.vibris.mod.mixinterface.VibrisShadowTerrainInvalidation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinRenderSectionManagerShadowTest {
	@Test
	void acceptsOnlyFinalizedCurrentOrSingleDeferredGeneration() {
		assertTrue(VibrisShadowTerrainInvalidation.isGenerationReady(false, 20, 20, false));
		assertTrue(VibrisShadowTerrainInvalidation.isGenerationReady(true, 21, 20, false));

		assertFalse(VibrisShadowTerrainInvalidation.isGenerationReady(true, 20, 20, false));
		assertFalse(VibrisShadowTerrainInvalidation.isGenerationReady(false, 21, 20, false));
		assertFalse(VibrisShadowTerrainInvalidation.isGenerationReady(true, 22, 20, false));
		assertFalse(VibrisShadowTerrainInvalidation.isGenerationReady(false, 0, 0, false));
		assertFalse(VibrisShadowTerrainInvalidation.isGenerationReady(false, 20, 20, true));
	}
}