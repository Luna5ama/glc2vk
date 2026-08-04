package dev.vibris.core;

import dev.vibris.api.CaptureResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.protocol.v1.Action;
import dev.vibris.protocol.v1.ActionSequence;
import dev.vibris.protocol.v1.ActivateSource;
import dev.vibris.protocol.v1.ArtifactFormat;
import dev.vibris.protocol.v1.ArtifactKind;
import dev.vibris.protocol.v1.CaptureScreenshot;
import dev.vibris.protocol.v1.CaptureBuffer;
import dev.vibris.protocol.v1.CaptureTexture;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.EmptyAction;
import dev.vibris.protocol.v1.GetGpuMetrics;
import dev.vibris.protocol.v1.JobActionKind;
import dev.vibris.protocol.v1.LoadShader;
import dev.vibris.protocol.v1.NamedShaderConfig;
import dev.vibris.protocol.v1.PreparedSourceRef;
import dev.vibris.protocol.v1.SceneContext;
import dev.vibris.protocol.v1.ShaderConfig;
import dev.vibris.protocol.v1.SubmitJob;
import dev.vibris.protocol.v1.WaitFrames;
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
    @TempDir
    Path temp;

    @Test
    void capturesOneBundleIntoOneCommittedJob() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 77, 16);
        ResourceCatalog.ResourceDescriptor texture = resource(
            "colortex0", ResourceCatalog.ResourceKind.TEXTURE, 77, 16);
        ResourceCatalog.ResourceDescriptor buffer = resource(
            "radiance_cache", ResourceCatalog.ResourceKind.BUFFER, 77, 8);
        fixture.runtime.catalog = new ResourceCatalog(List.of(screenshot, texture, buffer));
        fixture.runtime.captureResult = new CaptureResult(77, Map.of(
            "screenshot", screenshot, "colortex0", texture, "radiance_cache", buffer));
        fixture.runtime.captureFiles.put("screenshot.png", new byte[]{1, 2, 3});
        fixture.runtime.captureFiles.put("colortex0.raw", new byte[]{4, 5, 6, 7});
        fixture.runtime.captureFiles.put("colortex0.json", "{}".getBytes(StandardCharsets.UTF_8));
        fixture.runtime.captureFiles.put("radiance_cache.bin", new byte[]{8, 9});
        fixture.runtime.captureFiles.put("radiance_cache.json", "{}".getBytes(StandardCharsets.UTF_8));

        TerminalResult terminal = fixture.executor.execute(fixture.job(bundleActions()), ignored -> {});

        var result = terminal.completed().getResult();
        assertEquals(List.of("link:A", "reload", "context", "frames", "capture"),
            fixture.runtime.events);
        assertEquals(List.of(77L), result.getFrameIdsList());
        assertEquals(5, result.getArtifactsCount());
        assertEquals(List.of(
            ArtifactKind.ARTIFACT_KIND_SCREENSHOT,
            ArtifactKind.ARTIFACT_KIND_TEXTURE,
            ArtifactKind.ARTIFACT_KIND_BUFFER,
            ArtifactKind.ARTIFACT_KIND_SHADER_COMPILE_LOG,
            ArtifactKind.ARTIFACT_KIND_MANIFEST),
            result.getArtifactsList().stream().map(artifact -> artifact.getKind()).toList());
        Path manifest = Path.of(result.getManifestPath());
        assertTrue(Files.isRegularFile(manifest));
        assertTrue(Files.isRegularFile(manifest.resolveSibling("shader.log")));
        assertTrue(Files.isRegularFile(manifest.resolveSibling("colortex0.json")));
        assertTrue(Files.isRegularFile(manifest.resolveSibling("radiance_cache.json")));
        assertEquals(1, Files.list(fixture.artifactRoot).count(), "one workspace owns the committed job");
    }

    @Test
    void unknownResourceFailsBeforeOpeningArtifactJob() throws Exception {
        Fixture fixture = new Fixture();
        fixture.runtime.catalog = new ResourceCatalog(List.of(
            resource("final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 1, 16)));
        ActionSequence actions = ActionSequence.newBuilder().addActions(Action.newBuilder().setCaptureTexture(
            CaptureTexture.newBuilder().setLogicalName("missing").setFormat(ArtifactFormat.ARTIFACT_FORMAT_RAW)
                .setArtifactName("missing"))).build();

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(actions), ignored -> {}));

        assertEquals(ErrorCode.CAPTURE_RESOURCE_NOT_FOUND, failure.code);
        assertFalse(fixture.runtime.events.contains("capture"));
        try (var files = Files.walk(fixture.artifactRoot)) {
            assertEquals(1, files.count(), "catalog rejection must not create a temp or final job directory");
        }
    }

    @Test
    void runtimeQuotaOverrunUsesTypedFailure() throws Exception {
        Fixture fixture = new Fixture(64);
        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 1, 0);
        fixture.runtime.catalog = new ResourceCatalog(List.of(screenshot));
        fixture.runtime.captureResult = new CaptureResult(1, Map.of("screenshot", screenshot));
        fixture.runtime.captureFiles.put("screenshot.png", new byte[128]);
        ActionSequence actions = ActionSequence.newBuilder().addActions(Action.newBuilder().setCaptureScreenshot(
            CaptureScreenshot.newBuilder().setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)
                .setArtifactName("screenshot"))).build();

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(actions), ignored -> {}));

        assertEquals(ErrorCode.ARTIFACT_JOB_TOO_LARGE, failure.code);
        try (var files = Files.walk(fixture.artifactRoot)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp") ||
                path.getFileName().toString().equals("manifest.json")),
                "quota failure must remove the temporary artifact job");
        }
    }

    @Test
    void missingRuntimeArtifactFailsBeforeManifestRename() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 1, 16);
        fixture.runtime.catalog = new ResourceCatalog(List.of(screenshot));
        fixture.runtime.captureResult = new CaptureResult(1, Map.of("screenshot", screenshot));
        ActionSequence actions = ActionSequence.newBuilder().addActions(Action.newBuilder().setCaptureScreenshot(
            CaptureScreenshot.newBuilder().setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)
                .setArtifactName("screenshot"))).build();

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(actions), ignored -> {}));

        assertEquals(ErrorCode.CAPTURE_FAILED, failure.code);
        try (var files = Files.walk(fixture.artifactRoot)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp") ||
                path.getFileName().toString().equals("manifest.json")),
                "missing expected output must fail before a finalized directory is visible");
        }
    }

    @Test
    void rejectsMoreThanExpandedOverlayLimit() throws Exception {
        Fixture fixture = new Fixture();
        ActionSequence.Builder actions = ActionSequence.newBuilder();
        for (int index = 0; index < 137; index++) {
            actions.addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(0)));
        }

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.job(actions.build()), ignored -> {}));

        assertEquals(ErrorCode.CAPTURE_FAILED, failure.code);
        assertFalse(fixture.runtime.events.contains("capture"));
    }

    @Test
    void executesRuntimeActionsInOrderAndReturnsTypedResults() throws Exception {
        Fixture fixture = new Fixture();
        fixture.runtime.actionResponses.add("{\"loaded\":true}");
        fixture.runtime.actionResponses.add("{\"p50\":1.25}");
        ActionSequence actions = ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setGetShaderStatus(EmptyAction.getDefaultInstance()))
            .addActions(Action.newBuilder().setGetGpuMetrics(GetGpuMetrics.newBuilder().setFrames(8)))
            .build();

        TerminalResult terminal = fixture.executor.execute(fixture.job(actions), ignored -> {});

        assertEquals(List.of("link:A", "reload", "context", "action:ShaderStatus", "action:GpuMetrics"),
            fixture.runtime.events);
        assertEquals(List.of(1, 2), terminal.completed().getResult().getActionResultsList().stream()
            .map(result -> result.getActionIndex()).toList());
        assertEquals(List.of(JobActionKind.JOB_ACTION_KIND_GET_SHADER_STATUS,
                JobActionKind.JOB_ACTION_KIND_GET_GPU_METRICS),
            terminal.completed().getResult().getActionResultsList().stream()
                .map(result -> result.getKind()).toList());
        assertEquals(List.of("{\"loaded\":true}", "{\"p50\":1.25}"),
            terminal.completed().getResult().getActionResultsList().stream()
                .map(result -> result.getJson()).toList());
    }

    @Test
    void failedMatrixCaptureDoesNotDiscardLaterCaseArtifacts() throws Exception {
        Fixture fixture = new Fixture();
        ResourceCatalog.ResourceDescriptor screenshot = resource(
            "final", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 91, 16);
        fixture.runtime.catalog = new ResourceCatalog(List.of(screenshot));
        fixture.runtime.captureResult = new CaptureResult(91, Map.of("case-b--screenshot", screenshot));
        fixture.runtime.captureFailuresAfterWrite.add(new IllegalStateException("first case failed"));
        fixture.runtime.captureFileBatches.add(Map.of("case-a--screenshot.png", new byte[]{9}));
        fixture.runtime.captureFileBatches.add(Map.of("case-b--screenshot.png", new byte[]{1, 2, 3}));
        ActionSequence actions = ActionSequence.newBuilder()
            .addActions(load(fixture.source, "config-a", "case-a"))
            .addActions(Action.newBuilder().setCaptureScreenshot(CaptureScreenshot.newBuilder()
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG).setArtifactName("case-a--screenshot")))
            .addActions(load(fixture.source, "config-b", "case-b"))
            .addActions(Action.newBuilder().setCaptureScreenshot(CaptureScreenshot.newBuilder()
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG).setArtifactName("case-b--screenshot")))
            .build();

        TerminalResult terminal = fixture.executor.execute(fixture.matrixJob(actions), ignored -> {});

        var result = terminal.completed().getResult();
        assertTrue(result.getActionResultsList().stream()
            .anyMatch(action -> action.getJson().contains("\"case_id\":\"case-a\"") &&
                action.getJson().contains("\"success\":false")));
        assertTrue(result.getArtifactsList().stream()
            .anyMatch(artifact -> artifact.getFileName().equals("case-b--screenshot.png")));
        assertFalse(result.getArtifactsList().stream()
            .anyMatch(artifact -> artifact.getFileName().equals("case-a--screenshot.png")));
    }

    private static ActionSequence bundleActions() {
        return ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(2)))
            .addActions(Action.newBuilder().setCaptureScreenshot(CaptureScreenshot.newBuilder()
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG).setArtifactName("screenshot")))
            .addActions(Action.newBuilder().setCaptureTexture(CaptureTexture.newBuilder().setLogicalName("colortex0")
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_RAW).setArtifactName("colortex0")))
            .addActions(Action.newBuilder().setCaptureBuffer(CaptureBuffer.newBuilder().setLogicalName("radiance_cache")
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_BIN).setArtifactName("radiance_cache")))
            .build();
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
            SubmitJob submission = SubmitJob.newBuilder().setRequestId("request").setWorkspaceId("workspace")
                .setContext(SceneContext.newBuilder().setSaveId("save")
                    .setDimensionId("minecraft:overworld").setFov(70.0))
                .setActions(sequence).build();
            CoreJob job = new CoreJob(submission, "message", null);
            job.initialize(List.of(source));
            return job;
        }

        CoreJob matrixJob(ActionSequence actions) {
            SubmitJob submission = SubmitJob.newBuilder().setRequestId("matrix").setWorkspaceId("workspace")
                .setContext(SceneContext.newBuilder().setSaveId("save")
                    .setDimensionId("minecraft:overworld").setFov(70.0))
                .addShaderConfigs(NamedShaderConfig.newBuilder().setId("config-a")
                    .setConfig(ShaderConfig.newBuilder().putValues("MODE", "a")))
                .addShaderConfigs(NamedShaderConfig.newBuilder().setId("config-b")
                    .setConfig(ShaderConfig.newBuilder().putValues("MODE", "b")))
                .setActions(actions)
                .build();
            CoreJob job = new CoreJob(submission, "message", null);
            job.initialize(List.of(source));
            return job;
        }

        private SourceRegistry.Lease source() {
            try {
                String uuid = UUID.randomUUID().toString();
                Path directory = Files.createDirectory(pending.resolve(uuid));
                Path file = Files.writeString(directory.resolve("main.glsl"), "A");
                PreparedSourceRef reference = PreparedSourceRef.newBuilder().setUuid(uuid).setFileCount(1)
                    .setTotalBytes(Files.size(file)).build();
                List<SourceRegistry.Lease> leases = registry.reserve(registry.validate(List.of(reference)));
                registry.accept(leases);
                return leases.getFirst();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static Action.Builder load(SourceRegistry.Lease source, String config, String caseId) {
        return Action.newBuilder().setLoadShader(LoadShader.newBuilder()
            .setSourceUuid(source.uuid()).setSourceId("source").setConfigId(config)
            .setCaseId(caseId).setContinueOnFailure(true));
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
        }

        @Override
        public boolean retainsActiveSource() {
            return true;
        }
    }
}
