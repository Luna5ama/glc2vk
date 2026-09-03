package dev.vibris.mod;

import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

public interface VibrisPipelineManager {
	void vibris$installPipeline(NamespacedId dimension, WorldRenderingPipeline replacement);
}
