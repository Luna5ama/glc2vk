package dev.vibris.mod.mixin.iris;

import dev.luna5ama.vibris.capture.GraphicsProgramRegistry;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;

@Mixin(value = ProgramBuilder.class, remap = false)
public abstract class MixinProgramBuilder {
	@Shadow(remap = false) @Final private EnumMap<ShaderType, String> sources;
	@Unique private String vibris$name;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1, expect = 1)
	private void vibris$captureName(String name, int program, com.google.common.collect.ImmutableSet<Integer> reservedTextureUnits,
								EnumMap<ShaderType, String> sources, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		vibris$name = name;
	}

	@Inject(method = "build", at = @At("RETURN"), require = 1, expect = 1)
	private void vibris$registerGraphicsProgram(CallbackInfoReturnable<Program> cir) {
		Program program = cir.getReturnValue();
		if (sources.containsKey(ShaderType.VERTEX) && sources.containsKey(ShaderType.FRAGMENT)) {
			GraphicsProgramRegistry.register(program.getProgramId(), vibris$name,
				sources.get(ShaderType.VERTEX), sources.get(ShaderType.TESSELATION_CONTROL),
				sources.get(ShaderType.TESSELATION_EVAL), sources.get(ShaderType.GEOMETRY),
				sources.get(ShaderType.FRAGMENT));
		}
	}
}
