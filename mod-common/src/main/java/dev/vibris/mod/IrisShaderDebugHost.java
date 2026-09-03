package dev.vibris.mod;

import dev.luna5ama.vibris.capture.GlArtifactCapture;
import dev.luna5ama.vibris.capture.GlCaptureMetadata;
import dev.luna5ama.vibris.capture.ShaderDebugHost;
import dev.luna5ama.vibris.capture.StorageBufferInfo;
import dev.luna5ama.vibris.capture.TextureCatalog;
import dev.luna5ama.vibris.capture.TextureInfo;
import dev.vibris.api.ReloadResult;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import dev.vibris.mod.mixin.iris.ShaderPrinterAccess;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL45C;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

public final class IrisShaderDebugHost implements ShaderDebugHost {
	private final Supplier<ReloadResult> shaderReloader;

	public IrisShaderDebugHost() {
		this(() -> IrisShaderpackBridge.reload(null));
	}

	IrisShaderDebugHost(Supplier<ReloadResult> shaderReloader) {
		this.shaderReloader = shaderReloader;
	}

	@Override
	public String shaderPackName() {
		return Iris.getCurrentPackName();
	}

	@Override
	public void reloadShaders() {
		ReloadResult result = shaderReloader.get();
		if (!result.successful()) {
			String message = result.diagnostics().stream()
				.filter(diagnostic -> diagnostic.severity() == ReloadResult.Severity.ERROR)
				.map(ReloadResult.Diagnostic::message)
				.findFirst()
				.orElse("Fixed Vibris shaderpack reload failed");
			throw new IllegalStateException(message);
		}
	}

	@Override
	public Path gameDirectory() {
		return VibrisPlatform.getInstance().gameDirectory();
	}

	@Override
	public boolean debugShadersEnabled() {
		return Iris.getIrisConfig().areDebugOptionsEnabled();
	}

	@Override
	public List<StorageBufferInfo> storageBuffers() {
		return ((VibrisPipeline) pipeline()).vibris$getStorageBuffers().stream()
			.map(buffer -> new StorageBufferInfo(
				"iris_ssbo_" + buffer.getIndex(),
				buffer.getId(),
				GL45C.glGetNamedBufferParameteri64(buffer.getId(), GL15C.GL_BUFFER_SIZE),
				"iris_ssbo"))
			.toList();
	}

	@Override
	public TextureCatalog textureCatalog() {
		return new TextureCatalog(new ArrayList<>(namedTextures().values()));
	}

	@Override
	public Integer resolveTexture(String name) {
		TextureInfo texture = namedTextures().get(name);
		return texture == null ? null : texture.getTextureId();
	}

	@Override
	public void awaitPatchedShaderWrites() throws java.io.IOException {
		ShaderPrinterAccess.vibris$awaitPendingWrites();
	}

	private static Map<String, TextureInfo> namedTextures() {
		IrisRenderingPipeline pipeline = pipeline();
		Map<String, TextureInfo> textures = new LinkedHashMap<>();
		VibrisPipeline access = (VibrisPipeline) pipeline;
		RenderTargets targets = access.vibris$getRenderTargets();
		for (int index = 0; index < targets.getRenderTargetCount(); index++) {
			RenderTarget target = targets.get(index);
			if (target != null) {
				add(textures, "colortex" + index + ".main", "colortex", target.getMainTexture());
				add(textures, "colortex" + index + ".alt", "colortex", target.getAltTexture());
			}
		}

		add(textures, "depthtex0", "depthtex", glId(targets.getDepthTexture()));
		add(textures, "depthtex1", "depthtex", glId(targets.getDepthTextureNoTranslucents()));
		add(textures, "depthtex2", "depthtex", glId(targets.getDepthTextureNoHand()));

		ShadowRenderTargets shadow = access.vibris$getShadowRenderTargets();
		if (shadow != null) {
			add(textures, "shadowtex0", "shadowtex", glId(shadow.getDepthTexture()));
			add(textures, "shadowtex1", "shadowtex", glId(shadow.getDepthTextureNoTranslucents()));
			for (int index = 0; index < shadow.getRenderTargetCount(); index++) {
				RenderTarget target = shadow.get(index);
				if (target != null) {
					add(textures, "shadowcolor" + index + ".main", "shadowcolor", target.getMainTexture());
					add(textures, "shadowcolor" + index + ".alt", "shadowcolor", target.getAltTexture());
				}
			}
		}

		CustomTextureManager manager = pipeline.getCustomTextureManager();
		add(textures, "noisetex", "noise_texture", textureId(manager.getNoiseTexture()));
		manager.getCustomTextureIdMap().forEach((stage, stageTextures) ->
			stageTextures.forEach((sampler, texture) -> add(textures,
				"custom_texture." + stageName(stage) + "." + sampler,
				"custom_texture", textureId(texture))));
		manager.getIrisCustomTextures().forEach((name, texture) ->
			add(textures, "iris_custom_texture." + name, "iris_custom_texture", textureId(texture)));
		for (GlImage image : access.vibris$getCustomImages()) {
			add(textures, "iris_custom_image." + image.getName(), "iris_custom_image", image.getId());
		}

		add(textures, "gbuffers_terrain.gtexture", "terrain_atlas", access.vibris$getTerrainAtlasTexture());
		add(textures, "gbuffers_terrain.normals", "terrain_atlas", access.vibris$getTerrainNormalTexture());
		add(textures, "gbuffers_terrain.specular", "terrain_atlas", access.vibris$getTerrainSpecularTexture());
		return textures;
	}

	private static String stageName(TextureStage stage) {
		return switch (stage) {
			case GBUFFERS_AND_SHADOW -> "gbuffers";
			case COMPOSITE_AND_FINAL -> "composite";
			default -> stage.name().toLowerCase(Locale.ROOT);
		};
	}

	private static void add(Map<String, TextureInfo> textures, String name, String category, int textureId) {
		if (textureId == 0) return;
		GlCaptureMetadata metadata = GlArtifactCapture.describeTextureOrNull(textureId, 0);
		if (metadata == null) return;
		TextureInfo conflict = textures.putIfAbsent(name, new TextureInfo(
			name, textureId, category, metadata.getTextureTarget(), metadata.getWidth(), metadata.getHeight(),
			metadata.getDepth(), metadata.getMipLevels(), metadata.getInternalFormat(), metadata.getChannelLayout(),
			metadata.getNumericClass(), metadata.getComponentBits()));
		if (conflict != null && conflict.getTextureId() != textureId) {
			throw new IllegalStateException("Ambiguous debug texture name: " + name);
		}
	}

	private static IrisRenderingPipeline pipeline() {
		if (Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
			return pipeline;
		}
		throw new IllegalStateException("No Iris shader pipeline is active");
	}

	private static int textureId(TextureAccess texture) {
		return texture.getTextureId().getAsInt();
	}

	private static int glId(Object texture) {
		return ((GpuTextureInterface) texture).iris$getGlId();
	}
}
