package dev.vibris.mod.mixin.minecraft;

import com.mojang.blaze3d.opengl.GlProgram;
import dev.luna5ama.vibris.capture.GraphicsProgramRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlProgram.class)
public abstract class MixinGlProgramVibris {
	@Inject(method = "close", at = @At("HEAD"), require = 1, expect = 1)
	private void vibris$unregisterGraphicsProgram(CallbackInfo ci) {
		GraphicsProgramRegistry.unregister(((GlProgram) (Object) this).getProgramId());
	}
}
