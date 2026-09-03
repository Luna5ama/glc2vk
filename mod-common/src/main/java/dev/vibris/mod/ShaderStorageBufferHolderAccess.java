package dev.vibris.mod;

import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;

public interface ShaderStorageBufferHolderAccess {
	void vibris$resetBuffers();

	ShaderStorageBuffer[] vibris$buffers();
}
