package dev.vibris.mod.mixin.minecraft;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.vibris.mod.VibrisTime;
import dev.vibris.mod.mixinterface.VibrisTextureAtlasAnimation;
import net.irisshaders.iris.pbr.texture.PBRAtlasTexture;
import net.irisshaders.iris.pbr.texture.TextureAtlasExtension;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class MixinTextureAtlasVibris implements VibrisTextureAtlasAnimation {
	@Shadow private GpuTextureView[] mipViews;

	@Inject(method = "cycleAnimationFrames()V", at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
	private void vibris$freezeAnimation(CallbackInfo ci) {
		if (VibrisTime.active()) ci.cancel();
	}

	@Override
	public void iris$resetAnimationPhase() {
		TextureAtlas self = (TextureAtlas) (Object) this;
		TextureAtlasAccess accessor = (TextureAtlasAccess) self;
		PBRAtlasTexture.resetAndDrawAnimationStates(
			self.location(), accessor.vibris$animatedTextureStates(), mipViews, accessor.vibris$maxMipLevel());
		TextureAtlasExtension extension = (TextureAtlasExtension) self;
		if (extension.getPBRHolder() != null) extension.getPBRHolder().resetAnimationPhase();
	}
}
