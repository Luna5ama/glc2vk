package dev.vibris.integration;

import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.CompileCatalog;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.RuntimeStatus;
import dev.vibris.api.SceneContext;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.api.VibrisRuntimeAdapter;
import dev.vibris.core.VibrisBootstrap;
import dev.vibris.protocol.v1.Action;
import dev.vibris.protocol.v1.ActionSequence;
import dev.vibris.protocol.v1.ActivateSource;
import dev.vibris.protocol.v1.ArtifactFormat;
import dev.vibris.protocol.v1.ArtifactMetadata;
import dev.vibris.protocol.v1.TakeScreenshot;
import dev.vibris.protocol.v1.DumpBuffer;
import dev.vibris.protocol.v1.DumpTextureV2;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobCompleted;
import dev.vibris.protocol.v1.JobTimeouts;
import dev.vibris.protocol.v1.PreparedSourceRef;
import dev.vibris.protocol.v1.SubmitJob;
import dev.vibris.protocol.v1.WaitFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisCaptureTest {
    private static final String WORKSPACE = "44444444-4444-4444-8444-444444444444";
    private static final long FRAME_ID = 77;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    @TempDir
    Path temporaryDirectory;

    @Test
    void screenshotTextureBufferReadable() throws Exception {
        Fixture fixture = new Fixture();
        try (VibrisBootstrap bootstrap = fixture.start();
             IntegrationHarness.Client client = new IntegrationHarness.Client(bootstrap.port(), WORKSPACE)) {
            PreparedSourceRef source = IntegrationHarness.createSource(fixture.pendingRoot, "readable").reference();

            client.submit(job("readable", source, List.of("colortex0.main")));
            JobCompleted completed = client.awaitCompleted("readable");

            assertEquals(List.of(FRAME_ID), completed.getResult().getFrameIdsList());
            assertEquals(2, completed.getResult().getArtifactsCount());
            assertEquals(3, completed.getResult().getArtifactGroupsCount());
            Path beauty = artifact(completed, "beauty.png");
            Path texture = artifact(completed, "colortex0.main.bin");
            Path buffer = artifact(completed, "iris_ssbo_6.bin");
            assertArrayEquals(PNG_SIGNATURE, prefix(beauty, PNG_SIGNATURE.length));
            BufferedImage image = ImageIO.read(beauty.toFile());
            assertEquals(2, image.getWidth());
            assertEquals(2, image.getHeight());
            assertEquals(16, Files.size(texture));
            assertEquals(64, Files.size(buffer));
            assertTrue(Files.isRegularFile(beauty.resolveSibling("colortex0.main.json")));
            assertTrue(Files.isRegularFile(buffer.resolveSibling("iris_ssbo_6.json")));
            assertTrue(Files.isRegularFile(artifact(completed, "shader.log")));
            assertTrue(Files.isRegularFile(artifact(completed, "manifest.json")));
        }
    }

    @Test
    void sameFrameBundleAndUnknownResource() throws Exception {
        Fixture fixture = new Fixture();
        try (VibrisBootstrap bootstrap = fixture.start();
             IntegrationHarness.Client client = new IntegrationHarness.Client(bootstrap.port(), WORKSPACE)) {
            PreparedSourceRef valid = IntegrationHarness.createSource(fixture.pendingRoot, "bundle").reference();

            client.submit(job("bundle", valid, List.of("colortex0.main", "depthtex0")));
            JobCompleted completed = client.awaitCompleted("bundle");

            assertEquals(2, completed.getResult().getArtifactsCount());
            assertEquals(4, completed.getResult().getArtifactGroupsCount());
            assertEquals(List.of(FRAME_ID), completed.getResult().getFrameIdsList());
            for (String name : List.of("beauty.png", "colortex0.main.bin", "depthtex0.bin", "iris_ssbo_6.bin")) {
                ArtifactMetadata artifact = metadata(completed, name);
                assertEquals(FRAME_ID, artifact.getResource().getFrameId());
                assertTrue(Files.isReadable(Path.of(artifact.getPath())));
            }
            Path manifest = artifact(completed, "manifest.json");
            assertTrue(Files.isRegularFile(manifest.resolveSibling("colortex0.main.json")));
            assertTrue(Files.isRegularFile(manifest.resolveSibling("depthtex0.json")));
            assertTrue(Files.isRegularFile(manifest.resolveSibling("iris_ssbo_6.json")));
            List<String> beforeFailure = artifactTree(fixture.artifactRoot);
            PreparedSourceRef missing = IntegrationHarness.createSource(fixture.pendingRoot, "missing").reference();

            client.submit(unknownJob(missing));
            var failed = client.awaitFailed("missing", ErrorCode.CAPTURE_RESOURCE_NOT_FOUND);

            assertEquals(0, failed.getArtifactsCount());
            assertEquals(beforeFailure, artifactTree(fixture.artifactRoot));
            assertFalse(beforeFailure.stream().anyMatch(path -> path.endsWith(".tmp")));
        }
    }

    private static SubmitJob job(String requestId, PreparedSourceRef source, List<String> textures) {
        ActionSequence.Builder actions = ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setActivateSource(
                ActivateSource.newBuilder().setSourceUuid(source.getUuid())))
            .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(2)))
            .addActions(Action.newBuilder().setTakeScreenshot(TakeScreenshot.newBuilder()
                .setArtifactName("beauty").setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)));
        textures.forEach(name -> actions.addActions(Action.newBuilder().setDumpTextureV2(DumpTextureV2.newBuilder()
            .setLogicalName(name).setArtifactName(name).setFormat(ArtifactFormat.ARTIFACT_FORMAT_BIN))));
        actions.addActions(Action.newBuilder().setDumpBuffer(DumpBuffer.newBuilder()
            .setLogicalName("iris_ssbo_6").setArtifactName("iris_ssbo_6")));
        return submission(requestId, source).setActions(actions).build();
    }

    private static SubmitJob unknownJob(PreparedSourceRef source) {
        ActionSequence actions = ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setActivateSource(
                ActivateSource.newBuilder().setSourceUuid(source.getUuid())))
            .addActions(Action.newBuilder().setDumpTextureV2(
                DumpTextureV2.newBuilder().setLogicalName("missing_resource").setArtifactName("missing_resource")
                    .setFormat(ArtifactFormat.ARTIFACT_FORMAT_BIN))).build();
        return submission("missing", source).setActions(actions).build();
    }

    private static SubmitJob.Builder submission(String requestId, PreparedSourceRef source) {
        return SubmitJob.newBuilder().setRequestId(requestId).setWorkspaceId(WORKSPACE)
            .setContext(IntegrationHarness.context(requestId)).addSources(source)
            .setTimeouts(JobTimeouts.newBuilder().setExecutionTimeoutMs(5_000).setTotalTimeoutMs(10_000));
    }

    private static ArtifactMetadata metadata(JobCompleted completed, String fileName) {
        return java.util.stream.Stream.concat(
                completed.getResult().getArtifactsList().stream(),
                completed.getResult().getArtifactGroupsList().stream()
                    .flatMap(group -> group.getArtifactsList().stream()))
            .filter(artifact -> artifact.getFileName().equals(fileName)).findFirst().orElseThrow();
    }

    private static Path artifact(JobCompleted completed, String fileName) {
        Path path = Path.of(metadata(completed, fileName).getPath());
        assertTrue(path.isAbsolute());
        assertTrue(Files.isReadable(path));
        return path;
    }

    private static byte[] prefix(Path path, int length) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return java.util.Arrays.copyOf(bytes, length);
    }

    private static List<String> artifactTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> !path.equals(root)).map(root::relativize).map(Path::toString).sorted().toList();
        }
    }

    private final class Fixture {
        private final Path pendingRoot = temporaryDirectory.resolve("pending");
        private final Path artifactRoot = temporaryDirectory.resolve("artifacts");
        private final CaptureRuntime runtime = new CaptureRuntime();

        VibrisBootstrap start() throws VibrisBootstrap.Failure {
            return VibrisBootstrap.start(new VibrisBootstrap.Config(
                0, pendingRoot, artifactRoot, temporaryDirectory.resolve("shaderpacks/vibris")), runtime);
        }
    }

    private static final class CaptureRuntime implements VibrisRuntimeAdapter {
        private final Map<String, ResourceCatalog.ResourceDescriptor> resources = resources();

        @Override
        public CompletionStage<RuntimeStatus> getStatus() {
            return CompletableFuture.completedFuture(new RuntimeStatus(true, "test-save", "minecraft:overworld", ""));
        }

        @Override
        public CompletionStage<ContextApplyResult> ensureWorldAndContext(
            SceneContext context, CancellationToken cancellation
        ) {
            return CompletableFuture.completedFuture(ContextApplyResult.success(context));
        }

        @Override
        public CompletionStage<ReloadResult> reloadVibrisShaderpack(
            Map<String, String> config, CancellationToken cancellation
        ) {
            return CompletableFuture.completedFuture(ReloadResult.success(List.of()));
        }

        @Override
        public CompletionStage<TemporalResetResult> resetTemporalState(CancellationToken cancellation) {
            return CompletableFuture.completedFuture(new TemporalResetResult(true));
        }

        @Override
        public CompletionStage<CompileCatalog> getCompileCatalog(CancellationToken cancellation) {
            return CompletableFuture.completedFuture(CompileCatalog.empty(0));
        }

        @Override
        public CompletionStage<Long> waitRenderedFrames(int frameCount, CancellationToken cancellation) {
            return CompletableFuture.completedFuture(FRAME_ID);
        }

        @Override
        public ResourceCatalog getResourceCatalog() {
            return new ResourceCatalog(List.copyOf(resources.values()));
        }

        @Override
        public CompletionStage<CaptureResult> capture(
            CapturePlan plan, ArtifactSink sink, CancellationToken cancellation
        ) {
            try {
                List<CaptureResult.ArtifactGroup> captured = new java.util.ArrayList<>();
                for (CapturePlan.Target target : plan.targets()) {
                    ResourceCatalog.ResourceDescriptor resource = resources.get(target.logicalName());
                    writeCapture(sink, target, resource);
                    captured.add(new CaptureResult.ArtifactGroup(target.artifactName(), resource,
                        target.outputs().stream().map(output -> new CaptureResult.CapturedArtifact(
                            output.fileName(), output.format(), output.role(), output.subresourceIndex())).toList()));
                }
                return CompletableFuture.completedFuture(new CaptureResult(FRAME_ID, captured));
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }

        @Override
        public void close() {
        }

        private static void writeCapture(
            ArtifactSink sink, CapturePlan.Target target, ResourceCatalog.ResourceDescriptor resource
        ) throws IOException {
            for (CapturePlan.ArtifactOutputSpec spec : target.outputs()) {
                try (OutputStream output = sink.open(spec.fileName())) {
                    if (spec.role() == CapturePlan.ArtifactRole.METADATA) {
                        output.write(("{\"frame_id\":" + FRAME_ID + "}").getBytes(StandardCharsets.UTF_8));
                    } else if (spec.format() == CapturePlan.ArtifactFormat.PNG) {
                        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", output);
                    } else {
                        output.write(new byte[Math.toIntExact(resource.byteSize())]);
                    }
                }
            }
        }

        private static Map<String, ResourceCatalog.ResourceDescriptor> resources() {
            Map<String, ResourceCatalog.ResourceDescriptor> resources = new LinkedHashMap<>();
            resources.put("beauty", resource("beauty", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 4, 16));
            resources.put("colortex0.main", resource("colortex0.main", ResourceCatalog.ResourceKind.TEXTURE, 4, 16));
            resources.put("depthtex0", resource("depthtex0", ResourceCatalog.ResourceKind.TEXTURE, 1, 16));
            resources.put("iris_ssbo_6", resource(
                "iris_ssbo_6", ResourceCatalog.ResourceKind.BUFFER, 0, 64));
            return Map.copyOf(resources);
        }

        private static ResourceCatalog.ResourceDescriptor resource(
            String name, ResourceCatalog.ResourceKind kind, int channels, long bytes
        ) {
            int size = kind == ResourceCatalog.ResourceKind.BUFFER ? 0 : 2;
            return new ResourceCatalog.ResourceDescriptor(name, kind, size, size, size == 0 ? 0 : 1, 1, 1,
                kind == ResourceCatalog.ResourceKind.BUFFER ? "binary" : "RGBA8", channels,
                ResourceCatalog.ScalarType.UINT8, bytes, FRAME_ID, name);
        }
    }
}
