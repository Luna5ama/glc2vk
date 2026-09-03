package dev.vibris.mod.mixin.iris;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.luna5ama.vibris.capture.GraphicsProgramRegistry;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderCreator.class, remap = false)
public abstract class MixinShaderCreator {
	@Inject(method = "link", at = @At("RETURN"), require = 1, expect = 1)
	private static void vibris$registerGraphicsProgram(
		String name, String vertex, String geometry, String tessControl, String tessEval, String fragment,
		VertexFormat vertexFormat, boolean fallback, CallbackInfoReturnable<PartialShader> cir
	) {
		GraphicsProgramRegistry.register(cir.getReturnValue().program(), name,
			vertex, tessControl, tessEval, geometry, fragment);
	}
}
