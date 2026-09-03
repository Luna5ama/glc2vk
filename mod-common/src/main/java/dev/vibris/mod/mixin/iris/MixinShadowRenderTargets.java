package dev.vibris.mod.mixin.iris;

import dev.vibris.mod.ShadowTemporalReset;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = ShadowRenderTargets.class, remap = false)
public abstract class MixinShadowRenderTargets implements ShadowTemporalReset {
	@Shadow private boolean[] flipped;
	@Shadow private boolean translucentDepthDirty;

	@Override
	public void vibris$resetTemporalState() {
		java.util.Arrays.fill(flipped, false);
		translucentDepthDirty = true;
	}
}
