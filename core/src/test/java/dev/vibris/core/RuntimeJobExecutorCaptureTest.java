package dev.vibris.core;

import dev.vibris.api.CaptureResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.protocol.v2.Action;
import dev.vibris.protocol.v2.ActionKind;
import dev.vibris.protocol.v2.ActionSequence;
import dev.vibris.protocol.v2.ActivateSource;
import dev.vibris.protocol.v2.ArtifactFormat;
import dev.vibris.protocol.v2.ArtifactKind;
import dev.vibris.protocol.v2.DumpBuffer;
import dev.vibris.protocol.v2.DumpTexture;
import dev.vibris.protocol.v2.ErrorCode;
import dev.vibris.protocol.v2.GetPatchedShaders;
import dev.vibris.protocol.v2.JobSpec;
import dev.vibris.protocol.v2.PreparedSourceRef;
import dev.vibris.protocol.v2.ReceiptStatus;
import dev.vibris.protocol.v2.SceneContext;
import dev.vibris.protocol.v2.TakeScreenshot;
import dev.vibris.protocol.v2.TextureSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeJobExecutorCaptureTest {
    private static final String WORKSPACE_ID = "11111111-1111-4111-8111-111111111111";

    @TempDir
    Path temp;

    @Test
    void capturesScreenshotTextureAndBufferIntoStrictV2Artifacts() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 77, 16);
        ResourceCatalog.ResourceDescriptor texture = resource(
            "colortex0", ResourceCatalog.ResourceKind.TEXTURE, 77, 16);
        ResourceCatalog.ResourceDescriptor buffer = resource(
            "radiance_cache", ResourceCatalog.ResourceKind.BUFFER, 77, 8);
        fixture.runtime.catalog = new ResourceCatalog(List.of(screenshot, texture, buffer));
        LinkedHashMap<String, ResourceCatalog.ResourceDescriptor> captured = new LinkedHashMap<>();
        captured.put("screenshot", screenshot);
        captured.put("colortex0", texture);
        captured.put("radiance_cache", buffer);
        fixture.runtime.captureResult = captureResult(77, captured);
        fixture.runtime.captureFiles.put("screenshot.png", new byte[]{1, 2, 3});
        fixture.runtime.captureFiles.put("colortex0.bin", new byte[]{4, 5, 6, 7});
        fixture.runtime.captureFiles.put("colortex0.json", "{}".getBytes(StandardCharsets.UTF_8));
        fixture.runtime.captureFiles.put("radiance_cache.bin", new byte[]{8, 9});
        fixture.runtime.captureFiles.put("radiance_cache.json", "{}".getBytes(StandardCharsets.UTF_8));

        TerminalResult terminal = fixture.executor.execute(fixture.job(ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setTakeScreenshot(TakeScreenshot.newBuilder()
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG).setArtifactName("screenshot")))
            .addActions(Action.newBuilder().setDumpTexture(DumpTexture.newBuilder()
                .setResource(TextureSelector.newBuilder().setLogicalName("colortex0"))
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_BIN).setArtifactName("colortex0")))
            .addActions(Action.newBuilder().setDumpBuffer(DumpBuffer.newBuilder()
                .setLogicalName("radiance_cache").setArtifactName("radiance_cache")))
            .build()), ignored -> {});

        var result = terminal.completed().getResult();
        assertEquals(List.of("link:A", "reload", "context", "capture"), fixture.runtime.events);
        assertEquals(7, result.getArtifactsCount());
        assertEquals(List.of(
                ArtifactKind.ARTIFACT_KIND_SCREENSHOT,
                ArtifactKind.ARTIFACT_KIND_TEXTURE,
                ArtifactKind.ARTIFACT_KIND_TEXTURE,
                ArtifactKind.ARTIFACT_KIND_BUFFER,
                ArtifactKind.ARTIFACT_KIND_BUFFER,
                ArtifactKind.ARTIFACT_KIND_SHADER_COMPILE_LOG,
                ArtifactKind.ARTIFACT_KIND_MANIFEST),
            result.getArtifactsList().stream().map(artifact -> artifact.getKind()).toList());
        assertEquals(List.of(
                ActionKind.ACTION_KIND_ACTIVATE_SOURCE,
                ActionKind.ACTION_KIND_TAKE_SCREENSHOT,
                ActionKind.ACTION_KIND_DUMP_TEXTURE,
                ActionKind.ACTION_KIND_DUMP_BUFFER),
            result.getActionReceiptsList().stream().map(receipt -> receipt.getKind()).toList());
        assertEquals(List.of(0, 1, 2, 3),
            result.getActionReceiptsList().stream().map(receipt -> receipt.getActionIndex()).toList());
        assertTrue(result.getActionReceiptsList().stream()
            .allMatch(receipt -> receipt.getStatus() == ReceiptStatus.RECEIPT_STATUS_OK));
        assertEquals(1, result.getActionReceipts(1).getCapture().getArtifactsCount());
        assertEquals("final", result.getActionReceipts(1).getCapture().getResource().getLogicalName());
        assertEquals(2, result.getActionReceipts(2).getCapture().getArtifactsCount());
        assertEquals("colortex0", result.getActionReceipts(2).getCapture().getResource().getLogicalName());
        assertEquals(2, result.getActionReceipts(3).getCapture().getArtifactsCount());
        assertEquals("radiance_cache", result.getActionReceipts(3).getCapture().getResource().getLogicalName());
        assertTrue(result.getArtifactsList().stream()
            .map(artifact -> Path.of(artifact.getRelativePath()))
            .allMatch(Files::isRegularFile));
        assertFalse(result.getResultManifestId().isBlank());
    }

    @Test
    void keepsScreenshotFrameDelayInsideItsSingleCaptureReceipt() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 2, 16);
        fixture.runtime.catalog = new ResourceCatalog(List.of(screenshot));
        fixture.runtime.captureResult = captureResult(2, Map.of("delayed", screenshot));
        fixture.runtime.captureFiles.put("delayed.png", new byte[]{1, 2, 3});

        TerminalResult terminal = fixture.executor.execute(fixture.job(ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setTakeScreenshot(TakeScreenshot.newBuilder()
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)
                .setArtifactName("delayed")
                .setAfterFrames(2)))
            .build()), ignored -> {});

        var result = terminal.completed().getResult();
        assertEquals(List.of("link:A", "reload", "context", "frames", "capture"), fixture.runtime.events);
        assertEquals(2, result.getActionReceiptsCount());
        var screenshotReceipt = result.getActionReceipts(1);
        assertEquals(1, screenshotReceipt.getActionIndex());
        assertEquals(ActionKind.ACTION_KIND_TAKE_SCREENSHOT, screenshotReceipt.getKind());
        assertTrue(screenshotReceipt.hasCapture());
        assertTrue(screenshotReceipt.getCapture().hasInternalWait());
        assertEquals(2, screenshotReceipt.getCapture().getInternalWait().getRequestedFrames());
        assertEquals(0, screenshotReceipt.getCapture().getInternalWait().getStartFrame());
        assertEquals(2, screenshotReceipt.getCapture().getInternalWait().getEndFrame());
    }

    @Test
    void unknownResourceFailsBeforeOpeningArtifactJob() throws Exception {
        Fixture fixture = new Fixture();
        fixture.runtime.catalog = new ResourceCatalog(List.of(
            resource("final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 1, 16)));
        ActionSequence actions = ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setDumpTexture(DumpTexture.newBuilder()
                .setResource(TextureSelector.newBuilder().setLogicalName("missing"))
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_BIN).setArtifactName("missing")))
            .build();

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(actions), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_RESOURCE_NOT_FOUND, failure.code);
        assertFalse(fixture.runtime.events.contains("capture"));
        try (var files = Files.walk(fixture.artifactRoot)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void runtimeQuotaOverrunUsesTypedFailureAndRollsBack() throws Exception {
        Fixture fixture = new Fixture(64);
        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 1, 0);
        fixture.runtime.catalog = new ResourceCatalog(List.of(screenshot));
        fixture.runtime.captureResult = captureResult(1, Map.of("screenshot", screenshot));
        fixture.runtime.captureFiles.put("screenshot.png", new byte[128]);

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(screenshotActions()), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_ARTIFACT_TOO_LARGE, failure.code);
        assertNoTemporaryOrManifest(fixture.artifactRoot);
    }

    @Test
    void missingRuntimeArtifactFailsBeforeManifestCommit() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 1, 16);
        fixture.runtime.catalog = new ResourceCatalog(List.of(screenshot));
        fixture.runtime.captureResult = captureResult(1, Map.of("screenshot", screenshot));

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(screenshotActions()), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_CAPTURE_FAILED, failure.code);
        assertNoTemporaryOrManifest(fixture.artifactRoot);
    }

    @Test
    void capturesPatchedShadersWithStrictV2ArtifactMetadata() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.ResourceDescriptor resource = resource(
            "patched_shaders", ResourceCatalog.ResourceKind.PATCHED_SHADERS, 81, 12);
        fixture.runtime.patchedShaderFiles.put("patched.001_begin.vsh",
            "vertex".getBytes(StandardCharsets.UTF_8));
        fixture.runtime.patchedShaderFiles.put("patched.002_begin.json",
            "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        fixture.runtime.patchedShaderResult = new CaptureResult(81, List.of(new CaptureResult.ArtifactGroup(
            "patched", resource, List.of(
                new CaptureResult.CapturedArtifact("patched.001_begin.vsh",
                    dev.vibris.api.CapturePlan.ArtifactFormat.TEXT,
                    dev.vibris.api.CapturePlan.ArtifactRole.SUBRESOURCE, 0),
                new CaptureResult.CapturedArtifact("patched.002_begin.json",
                    dev.vibris.api.CapturePlan.ArtifactFormat.JSON,
                    dev.vibris.api.CapturePlan.ArtifactRole.METADATA, 0)))));

        TerminalResult terminal = fixture.executor.execute(fixture.job(ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setGetPatchedShaders(GetPatchedShaders.newBuilder()
                .setArtifactName("patched")))
            .build()), ignored -> {});

        var result = terminal.completed().getResult();
        assertEquals(ActionKind.ACTION_KIND_GET_PATCHED_SHADERS,
            result.getActionReceipts(1).getKind());
        assertTrue(result.getActionReceipts(1).hasPatchedShaders());
        assertEquals(81, result.getActionReceipts(1).getPatchedShaders().getShaderGeneration());
        assertEquals(2, result.getActionReceipts(1).getPatchedShaders().getArtifactsCount());
        assertEquals(2, result.getArtifactsList().stream()
            .filter(artifact -> artifact.getKind() == ArtifactKind.ARTIFACT_KIND_PATCHED_SHADER)
            .count());
        assertTrue(result.getArtifactsList().stream()
            .filter(artifact -> artifact.getKind() == ArtifactKind.ARTIFACT_KIND_PATCHED_SHADER)
            .allMatch(artifact -> Files.isRegularFile(Path.of(artifact.getRelativePath()))));
    }

    private static ActionSequence screenshotActions() {
        return ActionSequence.newBuilder().addActions(Action.newBuilder().setTakeScreenshot(
            TakeScreenshot.newBuilder().setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)
                .setArtifactName("screenshot"))).build();
    }

    private static void assertNoTemporaryOrManifest(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp") ||
                path.getFileName().toString().equals("manifest.json")));
        }
    }

    private static CaptureResult captureResult(
        long frameId, Map<String, ResourceCatalog.ResourceDescriptor> resources) {
        return new CaptureResult(frameId, resources.entrySet().stream().map(entry -> {
            String name = entry.getKey();
            ResourceCatalog.ResourceDescriptor resource = entry.getValue();
            dev.vibris.api.CapturePlan.ArtifactFormat format =
                resource.kind() == ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER
                    ? dev.vibris.api.CapturePlan.ArtifactFormat.PNG
                    : dev.vibris.api.CapturePlan.ArtifactFormat.BIN;
            var files = new java.util.ArrayList<CaptureResult.CapturedArtifact>();
            files.add(new CaptureResult.CapturedArtifact(name + "." + format.name().toLowerCase(), format,
                dev.vibris.api.CapturePlan.ArtifactRole.PRIMARY, null));
            if (resource.kind() != ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER) {
                files.add(new CaptureResult.CapturedArtifact(name + ".json",
                    dev.vibris.api.CapturePlan.ArtifactFormat.JSON,
                    dev.vibris.api.CapturePlan.ArtifactRole.METADATA, null));
            }
            return new CaptureResult.ArtifactGroup(name, resource, files);
        }).toList());
    }

    private static ResourceCatalog.ResourceDescriptor resource(
        String name, ResourceCatalog.ResourceKind kind, long frame, long bytes) {
        return new ResourceCatalog.ResourceDescriptor(name, kind, 2, 2, 1, 1, 1, "RGBA8", 4,
            ResourceCatalog.ScalarType.UINT8, bytes, frame, name);
    }

    private final class Fixture {
        final Path pending = Files.createDirectory(temp.resolve("pending"));
        final Path artifactRoot = temp.resolve("artifacts");
        final RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        final SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        final SourceActivator activator = new SourceActivator(registry, new RecordingLink(runtime.events));
        final ArtifactManager artifacts;
        final RuntimeJobExecutor executor;
        final SourceRegistry.Lease source = source();

        Fixture() throws Exception {
            this(ArtifactManager.DEFAULT_QUOTA_BYTES);
        }

        Fixture(long quotaBytes) throws Exception {
            artifacts = new ArtifactManager(artifactRoot, quotaBytes);
            executor = new RuntimeJobExecutor(runtime, new CoreProbe(), activator, artifacts);
        }

        CoreJob job(ActionSequence actions) {
            ActionSequence sequence = ActionSequence.newBuilder()
                .addActions(Action.newBuilder().setActivateSource(
                    ActivateSource.newBuilder().setSourceUuid(source.uuid())))
                .addAllActions(actions.getActionsList())
                .build();
            JobSpec spec = JobSpec.newBuilder()
                .setJobId("job-" + UUID.randomUUID())
                .setContext(SceneContext.newBuilder().setSaveId("save")
                    .setDimensionId("minecraft:overworld").setFov(70.0))
                .addSources(source.reference())
                .setActionSequence(sequence)
                .build();
            CoreJob job = new CoreJob(spec, spec.getJobId(), WORKSPACE_ID, "message", null);
            job.initialize(List.of(source));
            return job;
        }

        private SourceRegistry.Lease source() {
            try {
                String uuid = UUID.randomUUID().toString();
                Path directory = Files.createDirectory(pending.resolve(uuid));
                Path file = Files.writeString(directory.resolve("main.glsl"), "A");
                PreparedSourceRef reference = PreparedSourceRef.newBuilder()
                    .setSourceUuid(uuid)
                    .setFileCount(1)
                    .setTotalBytes(Files.size(file))
                    .build();
                List<SourceRegistry.Lease> leases = registry.reserve(registry.validate(List.of(reference)));
                registry.accept(leases);
                return leases.getFirst();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static final class RecordingLink implements ShaderLink {
        private final List<String> events;

        RecordingLink(List<String> events) {
            this.events = events;
        }

        @Override
        public void switchTo(SourceRegistry.Lease source, OwnershipCheck ownership) throws Failure {
            ownership.verify();
            events.add("link:A");
        }

        @Override
        public void detach() {
            events.add("detach");
        }

        @Override
        public boolean retainsActiveSource() {
            return true;
        }
    }
}
