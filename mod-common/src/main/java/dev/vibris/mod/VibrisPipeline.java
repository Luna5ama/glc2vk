package dev.vibris.mod;

import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.RenderTargets;

import java.util.Set;
import java.util.List;

public interface VibrisPipeline {
	IrisVibrisPassCapture vibris$getPassCapture();

	void vibris$resetTemporalState();
	RenderTargets vibris$getRenderTargets();
	ShadowRenderTargets vibris$getShadowRenderTargets();
	Set<GlImage> vibris$getCustomImages();
	List<ShaderStorageBuffer> vibris$getStorageBuffers();
	int vibris$getTerrainAtlasTexture();
	int vibris$getTerrainNormalTexture();
	int vibris$getTerrainSpecularTexture();
}
