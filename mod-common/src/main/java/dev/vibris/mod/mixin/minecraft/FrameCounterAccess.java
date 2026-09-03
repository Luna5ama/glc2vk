package dev.vibris.mod.mixin.minecraft;

import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SystemTimeUniforms.FrameCounter.class, remap = false)
public interface FrameCounterAccess {
	@Accessor("count") void vibris$setCount(int count);
}
