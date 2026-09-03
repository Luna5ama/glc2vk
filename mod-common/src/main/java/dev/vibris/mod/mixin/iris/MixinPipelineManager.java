package dev.vibris.mod.mixin.iris;

import dev.vibris.mod.VibrisPipelineManager;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(value = PipelineManager.class, remap = false)
public abstract class MixinPipelineManager implements VibrisPipelineManager {
	@Shadow @Final private Map<NamespacedId, WorldRenderingPipeline> pipelinesPerDimension;
	@Shadow private WorldRenderingPipeline pipeline;
	@Shadow private void reloadWorldRendererIfRequired() { }

	@Override
	public void vibris$installPipeline(NamespacedId dimension, WorldRenderingPipeline replacement) {
		if (pipeline != null || !pipelinesPerDimension.isEmpty()) {
			throw new IllegalStateException("The previous pipeline must be destroyed before installing a replacement");
		}
		SystemTimeUniforms.COUNTER.reset();
		SystemTimeUniforms.TIMER.reset();
		pipeline = replacement;
		pipelinesPerDimension.put(dimension, replacement);
		reloadWorldRendererIfRequired();
	}
}
