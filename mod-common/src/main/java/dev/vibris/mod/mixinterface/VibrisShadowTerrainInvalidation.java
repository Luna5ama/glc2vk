package dev.vibris.mod.mixinterface;

/**
 * Invalidates the retained Sodium shadow render-list snapshot when a real shadow input changes.
 */
public interface VibrisShadowTerrainInvalidation {
	void iris$markShadowRenderListDirty();

	static boolean isGenerationReady(
		boolean needsUpdate,
		long requestedGeneration,
		long finalizedGeneration,
		boolean traversalPendingFinalization
	) {
		if (traversalPendingFinalization || finalizedGeneration <= 0) return false;
		if (requestedGeneration == finalizedGeneration) return !needsUpdate;
		return needsUpdate && requestedGeneration == finalizedGeneration + 1;
	}
}
