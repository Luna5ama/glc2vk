package dev.vibris.mod;

import dev.vibris.api.CompileCatalog;
import dev.vibris.api.EffectiveShaderSettings;
import dev.vibris.api.ReloadResult;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.minecraft.client.Minecraft;

import java.nio.file.FileSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.vibris.mod.mixin.iris.IrisAccessor.*;

public final class IrisShaderpackBridge {
	private IrisShaderpackBridge() { }

	public static ReloadResult reload(Map<String, String> requestOverrides) {
		try {
			return reloadInternal(requestOverrides);
		} catch (Throwable failure) {
			VibrisClient.LOGGER.error("Unhandled failure while reloading the fixed Vibris shaderpack.", failure);
			throw failure;
		} finally {
			TransformPatcher.clearParsingCaches();
		}
	}

	private static ReloadResult reloadInternal(Map<String, String> requestOverrides) {
		VibrisClient.shaderDebugControl().clearErrors();
		ShaderPack previousPack = Iris.getCurrentPack().orElse(null);
		String previousPackName = Iris.getCurrentPackName();
		boolean previousFallback = vibris$getFallback();
		FileSystem previousZip = vibris$getZipFileSystem();
		EffectiveShaderSettings previousSettings = previousPack == null
			? EffectiveShaderSettings.empty()
			: IrisVibrisEffectiveSettings.capture(previousPack, Map.of(), Map.of());
		Map<String, String> preservedValues = requestOverrides == null && "vibris".equals(previousPackName)
			? previousSettings.values() : Map.of();
		NamespacedId dimension = Minecraft.getInstance().level == null ? DimensionId.OVERWORLD : Iris.getCurrentDimension();

		boolean loaded = vibris$loadExternalShaderpack("vibris");
		WorldRenderingPipeline replacement = null;
		CompileCatalog attemptedCatalog = null;
		if (loaded) {
			Iris.getPipelineManager().destroyPipeline();
			ProgramSet programs = Iris.getCurrentPack().orElseThrow().getProgramSet(dimension);
			IrisVibrisCompileCatalog.Session session = IrisVibrisCompileCatalog.begin(programs);
			try {
				replacement = new IrisRenderingPipeline(programs);
				IrisVibrisCompileCatalog.succeedRemaining(session);
			} catch (Exception exception) {
				IrisVibrisCompileCatalog.failRemaining(session, exception);
				ShaderStorageBufferHolder.forceDeleteBuffers();
				vibris$handleException(exception);
				VibrisClient.LOGGER.error("Failed to create the Vibris pipeline, restoring the previous pipeline.", exception);
			} finally {
				attemptedCatalog = IrisVibrisCompileCatalog.finish(session);
			}
		}

		List<ReloadResult.Diagnostic> diagnostics = VibrisClient.shaderDebugControl().errorList().stream()
			.map(error -> new ReloadResult.Diagnostic(ReloadResult.Severity.ERROR, error.getFilename(), 0, error.getMessage()))
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		boolean active = loaded && "vibris".equals(Iris.getCurrentPackName()) && Iris.getCurrentPack().isPresent()
			&& !vibris$getFallback() && replacement instanceof IrisRenderingPipeline;
		if (active) {
			((VibrisPipelineManager) Iris.getPipelineManager()).vibris$installPipeline(dimension, replacement);
			IrisVibrisCompileCatalog.publish(attemptedCatalog);
			vibris$closeShaderpackFileSystem(previousZip);
			vibris$setZipFileSystem(null);
			EffectiveShaderSettings settings = IrisVibrisEffectiveSettings.capture(
				Iris.getCurrentPack().orElseThrow(), preservedValues, requestOverrides == null ? Map.of() : requestOverrides);
			return ReloadResult.success(settings, diagnostics);
		}

		vibris$setCurrentPack(previousPack);
		vibris$setCurrentPackName(previousPackName);
		vibris$setFallback(previousFallback);
		vibris$setZipFileSystem(previousZip);
		boolean restored = true;
		if (loaded) {
			WorldRenderingPipeline restoredPipeline = Iris.getPipelineManager().preparePipeline(dimension);
			restored = previousPack == null
				? restoredPipeline instanceof VanillaRenderingPipeline
				: restoredPipeline instanceof IrisRenderingPipeline && !vibris$getFallback();
		}
		if (diagnostics.isEmpty()) {
			diagnostics.add(new ReloadResult.Diagnostic(ReloadResult.Severity.ERROR, "shaderpack", 0,
				"The fixed Vibris shaderpack did not produce an active Iris pipeline."));
		}
		if (attemptedCatalog != null) IrisVibrisCompileCatalog.publish(attemptedCatalog);
		return restored
			? ReloadResult.failurePreservingActiveState(previousSettings, diagnostics)
			: ReloadResult.failure(diagnostics);
	}
}
