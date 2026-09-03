package dev.vibris.mod.mixin.iris;

import dev.luna5ama.vibris.capture.GraphicsProgramRegistry;
import net.irisshaders.iris.gl.program.Program;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Program.class, remap = false)
public abstract class MixinProgram {
	@Inject(method = "destroyInternal", at = @At("HEAD"), require = 1, expect = 1)
	private void vibris$unregisterGraphicsProgram(CallbackInfo ci) {
		GraphicsProgramRegistry.unregister(((Program) (Object) this).getProgramId());
	}
}
