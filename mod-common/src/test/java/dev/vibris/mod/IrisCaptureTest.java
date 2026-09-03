package dev.vibris.mod;

import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResourceNotFoundException;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.CompileCatalog;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.DeterministicTemporalCaptureOutcome;
import dev.vibris.api.DeterministicTemporalCapturePlanner;
import dev.vibris.api.DeterministicTemporalCaptureRequest;
import dev.vibris.api.EffectiveShaderSettings;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.RuntimeEnvironment;
import dev.vibris.api.RuntimeStatus;
import dev.vibris.api.SceneContext;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.core.RenderedFrameClock;
import dev.vibris.core.DeterministicTemporalCaptureScheduler;
import dev.vibris.core.ThreadBoundVibrisRuntimeAdapter;
import dev.vibris.core.VibrisRuntimeHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static dev.vibris.api.CapturePlan.ArtifactFormat.BIN;
import static dev.vibris.api.CapturePlan.ArtifactFormat.PNG;
import static dev.vibris.api.CapturePlan.ArtifactFormat.JSON;
import static dev.vibris.api.CapturePlan.ArtifactRole.METADATA;
import static dev.vibris.api.CapturePlan.ArtifactRole.PRIMARY;
import static dev.vibris.api.ResourceCatalog.ResourceKind.BUFFER;
import static dev.vibris.api.ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER;
import static dev.vibris.api.ResourceCatalog.ResourceKind.TEXTURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisCaptureTest {
	@TempDir
	Path output;

	@Test
	void screenshotTextureBufferReadable() throws Exception {
		RenderedFrameClock frames = new RenderedFrameClock();
		ThreadBoundVibrisRuntimeAdapter adapter = new ThreadBoundVibrisRuntimeAdapter(new CaptureHost(), frames);
		CapturePlan plan = new CapturePlan(List.of(
			target(FINAL_FRAMEBUFFER, "beauty", PNG, "beauty"),
			target(TEXTURE, "colortex0", BIN, "colortex0-main"),
			target(BUFFER, "iris_ssbo_6", BIN, "ssbo-6")));

		var capture = adapter.capture(plan, new DirectorySink(output), CancellationToken.none())
			.toCompletableFuture();
		assertFalse(capture.isDone());
		frames.renderedFrame();
		CaptureResult result = capture.join();

		assertEquals(1, result.frameId());
		assertEquals(1, result.groups().stream().map(CaptureResult.ArtifactGroup::resource)
			.map(ResourceCatalog.ResourceDescriptor::frameId)
			.distinct().count());
		assertNotNull(ImageIO.read(output.resolve("beauty.png").toFile()));
		assertEquals(32, Files.size(output.resolve("colortex0-main.bin")));
		assertEquals(16, Files.size(output.resolve("ssbo-6.bin")));
		assertTrue(Files.isRegularFile(output.resolve("colortex0-main.json")));
		assertTrue(Files.isRegularFile(output.resolve("ssbo-6.json")));
	}

	@Test
	void sameFrameBundleAndUnknownResource() {
		RenderedFrameClock frames = new RenderedFrameClock();
		ThreadBoundVibrisRuntimeAdapter adapter = new ThreadBoundVibrisRuntimeAdapter(new CaptureHost(), frames);
		CapturePlan bundle = new CapturePlan(List.of(
			target(FINAL_FRAMEBUFFER, "beauty", PNG, "beauty"),
			target(TEXTURE, "colortex0", BIN, "colortex0-main"),
			target(TEXTURE, "depthtex0", BIN, "depthtex0"),
			target(BUFFER, "iris_ssbo_6", BIN, "ssbo-6")));
		var capture = adapter.capture(bundle, new DirectorySink(output), CancellationToken.none())
			.toCompletableFuture();
		frames.renderedFrame();
		assertEquals(List.of(1L), capture.join().groups().stream()
			.map(CaptureResult.ArtifactGroup::resource)
			.map(ResourceCatalog.ResourceDescriptor::frameId).distinct().toList());
		assertTrue(Files.exists(output.resolve("depthtex0.bin")));

		CapturePlan missing = new CapturePlan(List.of(target(TEXTURE, "missing", BIN, "missing")));
		var failed = adapter.capture(missing, new DirectorySink(output), CancellationToken.none())
			.toCompletableFuture();
		frames.renderedFrame();
		CompletionException failure = assertThrows(CompletionException.class, failed::join);
		assertInstanceOf(CaptureResourceNotFoundException.class, failure.getCause());
		assertFalse(Files.exists(output.resolve("missing.bin")));
	}

	private static CapturePlan.Target target(
		ResourceCatalog.ResourceKind kind,
		String logicalName,
		CapturePlan.ArtifactFormat format,
		String artifactName
	) {
		CapturePlan.ArtifactOutputSpec primary = new CapturePlan.ArtifactOutputSpec(
			artifactName + "." + format.name().toLowerCase(java.util.Locale.ROOT), format, PRIMARY, null);
		List<CapturePlan.ArtifactOutputSpec> outputs = kind == FINAL_FRAMEBUFFER
			? List.of(primary)
			: List.of(primary, new CapturePlan.ArtifactOutputSpec(artifactName + ".json", JSON, METADATA, null));
		ResourceCatalog.TextureView view = kind == TEXTURE ? ResourceCatalog.TextureView.MAIN : null;
		return new CapturePlan.Target(
			new CapturePlan.ResourceSelector(kind, logicalName, view, 0, 0), format, artifactName, outputs);
	}

	private record DirectorySink(Path root) implements ArtifactSink {
		@Override
		public OutputStream open(String artifactName) throws IOException {
			return Files.newOutputStream(root.resolve(artifactName));
		}
	}

	private static final class CaptureHost implements VibrisRuntimeHost {
		@Override
		public boolean isClientThread() {
			return true;
		}

		@Override
		public void executeOnClient(Runnable task) {
			task.run();
		}

		@Override
		public RuntimeEnvironment runtimeEnvironment() {
			return environment();
		}

		@Override
		public RuntimeStatus status() {
			return new RuntimeStatus(true, "save", "minecraft:overworld", "source");
		}

		@Override
		public CompletionStage<ContextApplyResult> applyContext(
			SceneContext context,
			CancellationToken cancellation
		) {
			return java.util.concurrent.CompletableFuture.completedFuture(ContextApplyResult.success(context));
		}

		@Override
		public ReloadResult reload(Map<String, String> config, CancellationToken cancellation) {
			return ReloadResult.success(EffectiveShaderSettings.empty(), List.of());
		}

		@Override
		public TemporalResetResult resetTemporal(CancellationToken cancellation) {
			return new TemporalResetResult(true);
		}

		@Override
		public CompletionStage<DeterministicTemporalCaptureOutcome> captureDeterministicTemporalPhase(
			DeterministicTemporalCaptureRequest request,
			DeterministicTemporalCapturePlanner planner,
			ArtifactSink sink,
			DeterministicTemporalCaptureScheduler scheduler,
			CancellationToken cancellation
		) {
			return java.util.concurrent.CompletableFuture.failedFuture(
				new UnsupportedOperationException("No deterministic capture phase in this capture fixture"));
		}

		@Override
		public void beginDeterministicSequence(CancellationToken cancellation) {
			cancellation.throwIfCancellationRequested();
		}

		@Override
		public void endDeterministicSequence(CancellationToken cancellation) {
			cancellation.throwIfCancellationRequested();
		}

		@Override
		public CompileCatalog compileCatalog(CancellationToken cancellation) {
			return CompileCatalog.empty(0);
		}

		@Override
		public ResourceCatalog resourceCatalog(long frameId) {
			return ResourceCatalog.empty();
		}

		@Override
		public CaptureResult capture(
			CapturePlan plan,
			ArtifactSink sink,
			long frameId,
			CancellationToken cancellation
		) {
			if (plan.targets().stream().anyMatch(target -> target.resource().logicalName().equals("missing"))) {
				throw new CaptureResourceNotFoundException("missing");
			}
			List<CaptureResult.ArtifactGroup> groups = new java.util.ArrayList<>();
			for (CapturePlan.Target target : plan.targets()) {
				write(target, sink);
				long bytes = target.format() == PNG ? 16 : target.resource().kind() == BUFFER ? 16 : 32;
				ResourceCatalog.ResourceDescriptor resource = ResourceCatalog.ResourceDescriptor.of(
					target.resource().logicalName(), target.resource().kind(),
					target.resource().kind() == TEXTURE ? List.of(ResourceCatalog.TextureView.MAIN) : List.of(),
					2, 2, 1, target.resource().kind() == TEXTURE ? 1 : 0,
					target.resource().kind() == TEXTURE ? 1 : 0, "test", 4,
					ResourceCatalog.ScalarType.FLOAT32, bytes, frameId, target.resource().logicalName(),
					"test", "texture_2d", "RGBA", "float", 32, "RGBA", "FLOAT");
				List<CaptureResult.CapturedArtifact> artifacts = target.outputs().stream()
					.map(output -> new CaptureResult.CapturedArtifact(output.fileName(), output.format(),
						output.role(), output.subresourceIndex()))
					.toList();
				groups.add(new CaptureResult.ArtifactGroup(target.artifactName(), resource, artifacts));
			}
			return new CaptureResult(frameId, groups);
		}

		@Override
		public CompletionStage<CapturePlan.AfterPassReceipt> captureAfterPass(
			CapturePlan.AfterPassRequest request,
			ArtifactSink sink,
			CancellationToken cancellation
		) {
			return java.util.concurrent.CompletableFuture.failedFuture(
				new UnsupportedOperationException("No pass boundary in this capture fixture"));
		}

		private static void write(CapturePlan.Target target, ArtifactSink sink) {
			int bytes = target.resource().kind() == BUFFER ? 16 : 32;
			try (OutputStream output = sink.open(target.outputs().getFirst().fileName())) {
				if (target.format() == PNG) {
					BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
					ImageIO.write(image, "png", output);
				} else {
					output.write(new byte[bytes]);
				}
				if (target.resource().kind() != FINAL_FRAMEBUFFER) {
					try (OutputStream metadata = sink.open(target.metadataFileName())) {
						metadata.write(("{\"byte_size\":" + bytes + "}")
							.getBytes(java.nio.charset.StandardCharsets.UTF_8));
					}
				}
			} catch (IOException exception) {
				throw new java.io.UncheckedIOException(exception);
			}
		}

		@Override
		public void close() {
		}
	}

	private static RuntimeEnvironment environment() {
		return new RuntimeEnvironment(
			"test-minecraft", "test-iris", "test-vibris", "test-java", "test-os",
			"test-vendor", "test-renderer", "test-opengl", "test-driver");
	}
}