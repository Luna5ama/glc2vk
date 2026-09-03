package dev.vibris.mod.mixin.iris;

import dev.vibris.mod.RenderTargetsAccess;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = RenderTargets.class, remap = false)
public abstract class MixinRenderTargets implements RenderTargetsAccess {
	@Shadow private boolean translucentDepthDirty;
	@Shadow private boolean handDepthDirty;

	@Override
	public void vibris$resetDepthCopies() {
		translucentDepthDirty = true;
		handDepthDirty = true;
	}
}
