package dev.vibris.mod.mixin.iris;

import com.mojang.blaze3d.opengl.GlStateManager;
import dev.vibris.mod.ShaderStorageBufferHolderAccess;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import org.lwjgl.opengl.GL43C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = ShaderStorageBufferHolder.class, remap = false)
public abstract class MixinShaderStorageBufferHolder implements ShaderStorageBufferHolderAccess {
	@Shadow private ShaderStorageBuffer[] buffers;
	@Shadow private boolean destroyed;

	@Override
	public void vibris$resetBuffers() {
		if (destroyed) throw new IllegalStateException("Tried to reset destroyed buffer objects");
		for (ShaderStorageBuffer buffer : buffers) if (buffer != null) buffer.resetContents();
		GlStateManager._glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
	}

	@Override public ShaderStorageBuffer[] vibris$buffers() { return buffers.clone(); }
}
