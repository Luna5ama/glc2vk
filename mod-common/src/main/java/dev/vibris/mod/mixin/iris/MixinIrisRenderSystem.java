package dev.vibris.mod.mixin.iris;

import dev.vibris.mod.VibrisClient;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.joml.Vector3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumMap;

@Mixin(value = IrisRenderSystem.class, remap = false)
public abstract class MixinIrisRenderSystem {
	@Inject(method = "dispatchCompute(Ljava/util/EnumMap;Ljava/lang/String;Lorg/joml/Vector3i;)V", at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
	private static void vibris$captureCompute(EnumMap<ShaderType, String> sources, String passName, Vector3i groups, CallbackInfo ci) {
		VibrisClient.shaderDebugControl().beginCompute();
		try {
			if (VibrisClient.captureManager().dispatchCompute(sources.get(ShaderType.COMPUTE), passName, groups.x, groups.y, groups.z)) ci.cancel();
		} finally {
			VibrisClient.shaderDebugControl().endCompute();
		}
	}

	@Inject(method = "dispatchComputeIndirect(Ljava/util/EnumMap;Ljava/lang/String;J)V", at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
	private static void vibris$captureComputeIndirect(EnumMap<ShaderType, String> sources, String passName, long offset, CallbackInfo ci) {
		VibrisClient.shaderDebugControl().beginCompute();
		try {
			if (VibrisClient.captureManager().dispatchComputeIndirect(sources.get(ShaderType.COMPUTE), passName, offset)) ci.cancel();
		} finally {
			VibrisClient.shaderDebugControl().endCompute();
		}
	}
}
