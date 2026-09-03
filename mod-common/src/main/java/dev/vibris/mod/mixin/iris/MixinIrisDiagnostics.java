package dev.vibris.mod.mixin.iris;

import com.google.common.base.Throwables;
import dev.vibris.mod.VibrisClient;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Iris.class, remap = false)
public abstract class MixinIrisDiagnostics {
	@Inject(method = "handleException", at = @At("HEAD"), require = 1, expect = 1)
	private static void vibris$recordShaderError(Exception exception, CallbackInfo ci) {
		VibrisClient.shaderDebugControl().recordError(
			exception.getClass().getSimpleName(),
			exception instanceof ShaderCompileException shaderException ? shaderException.getFilename() : "",
			exception.getMessage() == null ? "" : exception.getMessage(),
			Throwables.getStackTraceAsString(exception), System.currentTimeMillis());
	}

	@Inject(method = "reload", at = @At("HEAD"), require = 1, expect = 1)
	private static void vibris$clearShaderErrors(CallbackInfo ci) {
		VibrisClient.shaderDebugControl().clearErrors();
	}
}
