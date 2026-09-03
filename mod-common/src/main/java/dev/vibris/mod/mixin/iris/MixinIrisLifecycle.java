package dev.vibris.mod.mixin.iris;

import dev.vibris.mod.VibrisClient;
import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Iris.class, remap = false)
public abstract class MixinIrisLifecycle {
	@Inject(method = "onEarlyInitialize", at = @At("HEAD"), require = 1, expect = 1)
	private void vibris$initializeAutomation(CallbackInfo ci) {
		VibrisClient.initializeAutomation();
	}

	@Inject(method = "onRenderSystemInit", at = @At("TAIL"), require = 1, expect = 1)
	private static void vibris$start(CallbackInfo ci) {
		VibrisClient.onGlReady();
	}
}
