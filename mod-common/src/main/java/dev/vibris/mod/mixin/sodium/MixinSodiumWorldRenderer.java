package dev.vibris.mod.mixin.sodium;

import dev.vibris.mod.mixinterface.VibrisTerrainQuiescence;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRenderer implements VibrisTerrainQuiescence {
	@Shadow private RenderSectionManager renderSectionManager;

	@Override
	public TerrainSnapshot iris$captureTerrainSnapshot() {
		if (renderSectionManager == null) {
			return new TerrainSnapshot(false, List.of(), List.of(), List.of(), true, List.of(),
				"render section manager is unavailable");
		}
		return ((VibrisTerrainQuiescence) renderSectionManager).iris$captureTerrainSnapshot();
	}
}
