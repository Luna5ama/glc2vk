package dev.vibris.mod.mixin.iris;

import dev.luna5ama.vibris.capture.CaptureKt;
import dev.vibris.mod.VibrisClient;
import net.irisshaders.iris.gl.GLDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GLDebug.class, remap = false)
public abstract class MixinGLDebug {
	@Inject(method = "pushGroup", at = @At("HEAD"), require = 1, expect = 1)
	private static void vibris$pushGroup(int id, String name, CallbackInfo ci) {
		CaptureKt.captureDebugLabelPush(name);
		VibrisClient.shaderDebugControl().pushPass(name);
	}

	@Inject(method = "popGroup", at = @At("HEAD"), require = 1, expect = 1)
	private static void vibris$popGroup(CallbackInfo ci) {
		CaptureKt.captureDebugLabelPop();
		VibrisClient.shaderDebugControl().popPass();
	}
}
