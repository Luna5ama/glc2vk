package dev.vibris.core;

import dev.vibris.api.ReloadResult;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.protocol.v1.Action;
import dev.vibris.protocol.v1.ActionSequence;
import dev.vibris.protocol.v1.ActivateSource;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobStage;
import dev.vibris.protocol.v1.LoadShader;
import dev.vibris.protocol.v1.NamedShaderConfig;
import dev.vibris.protocol.v1.PreparedSourceRef;
import dev.vibris.protocol.v1.SceneContext;
import dev.vibris.protocol.v1.ShaderConfig;
import dev.vibris.protocol.v1.SubmitJob;
import dev.vibris.protocol.v1.WaitFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeJobExecutorTest {
    @TempDir
    Path pending;

    @Test
    void activatesAndReloadsBeforeContextAndExplicitFrames() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        List<JobStage> progress = new ArrayList<>();

        fixture.executor.execute(job(source.lease), progress::add);
        fixture.activator.release(List.of(source.lease));

        assertEquals(List.of("link:A", "reload", "context", "frames"), fixture.runtime.events);
        assertEquals(List.of(
            JobStage.JOB_STAGE_ACTIVATING_SOURCE,
            JobStage.JOB_STAGE_RELOADING_SHADERS,
            JobStage.JOB_STAGE_LOADING_WORLD,
            JobStage.JOB_STAGE_APPLYING_CONTEXT,
            JobStage.JOB_STAGE_WARMING_UP), progress);
        assertEquals(source.uuid, fixture.registry.activeUuid());
        assertEquals(Map.of("SETTING_SAMPLE_COUNT", "32"), fixture.runtime.lastShaderConfig);
        assertTrue(Files.isDirectory(source.path));
    }

    @Test
    void failedCandidateReloadRestoresAndReloadsPreviousSource() throws Exception {
        Fixture fixture = new Fixture();
        Source sourceA = fixture.source("A");
        fixture.activate(sourceA);
        Source sourceB = fixture.source("B");
        fixture.runtime.events.clear();
        fixture.runtime.reloads.add(ReloadResult.failure(List.of(error("VIBRIS_AUTOMATION_ROLLBACK"))));
        fixture.runtime.reloads.add(ReloadResult.success(List.of()));

        RuntimeJobExecutor.Failure failure = assertThrows(
            RuntimeJobExecutor.Failure.class, () -> fixture.executor.execute(job(sourceB.lease), ignored -> {}));
        fixture.activator.release(List.of(sourceB.lease));

        assertEquals(ErrorCode.SHADER_COMPILE_FAILED, failure.code);
        assertShaderLog(failure, "VIBRIS_AUTOMATION_ROLLBACK");
        assertEquals(List.of("link:B", "reload", "link:A", "reload"), fixture.runtime.events);
        assertEquals(sourceA.uuid, fixture.registry.activeUuid());
        assertTrue(fixture.activator.ready());
        assertTrue(Files.isDirectory(sourceA.path));
        assertFalse(Files.exists(sourceB.path));
    }

    @Test
    void preservedActiveStateSkipsRollbackReload() throws Exception {
        Fixture fixture = new Fixture();
        Source sourceA = fixture.source("A");
        fixture.activate(sourceA);
        Source sourceB = fixture.source("B");
        fixture.runtime.events.clear();
        fixture.runtime.reloads.add(ReloadResult.failurePreservingActiveState(
            List.of(error("VIBRIS_AUTOMATION_ROLLBACK"))));

        RuntimeJobExecutor.Failure failure = assertThrows(
            RuntimeJobExecutor.Failure.class, () -> fixture.executor.execute(job(sourceB.lease), ignored -> {}));
        fixture.activator.release(List.of(sourceB.lease));

        assertEquals(ErrorCode.SHADER_COMPILE_FAILED, failure.code);
        assertShaderLog(failure, "VIBRIS_AUTOMATION_ROLLBACK");
        assertEquals(List.of("link:B", "reload", "link:A"), fixture.runtime.events);
        assertEquals(sourceA.uuid, fixture.registry.activeUuid());
        assertTrue(fixture.activator.ready());
        assertTrue(Files.isDirectory(sourceA.path));
        assertFalse(Files.exists(sourceB.path));
    }

    @Test
    void rollbackReloadFailureKeepsOriginalFailureAndMarksServerNotReady() throws Exception {
        Fixture fixture = new Fixture();
        Source sourceA = fixture.source("A");
        fixture.activate(sourceA);
        Source sourceB = fixture.source("B");
        fixture.runtime.events.clear();
        fixture.runtime.reloads.add(ReloadResult.failure(List.of(error("VIBRIS_AUTOMATION_ORIGINAL"))));
        fixture.runtime.reloads.add(ReloadResult.failure(List.of()));

        RuntimeJobExecutor.Failure failure = assertThrows(
            RuntimeJobExecutor.Failure.class, () -> fixture.executor.execute(job(sourceB.lease), ignored -> {}));

        assertEquals(ErrorCode.SHADER_COMPILE_FAILED, failure.code);
        assertShaderLog(failure, "VIBRIS_AUTOMATION_ORIGINAL");
        assertEquals(List.of("link:B", "reload", "link:A", "reload"), fixture.runtime.events);
        assertFalse(fixture.activator.ready());
        assertEquals(sourceA.uuid, fixture.registry.activeUuid());
    }

    @Test
    void resetFailureStopsBeforeFrames() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        fixture.runtime.reset = new TemporalResetResult(false);

        RuntimeJobExecutor.Failure failure = assertThrows(
            RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(jobWithReset(source.lease), ignored -> {}));

        assertEquals(ErrorCode.INTERNAL_ERROR, failure.code);
        assertEquals(List.of("link:A", "reload", "context", "reset"), fixture.runtime.events);
    }

    @Test
    void matrixLoadFailureIsRecordedAndLaterCasesContinue() throws Exception {
        Fixture fixture = new Fixture();
        Source sourceA = fixture.source("A");
        Source sourceB = fixture.source("B");
        fixture.runtime.reloads.add(ReloadResult.failurePreservingActiveState(List.of(error("bad config"))));
        fixture.runtime.reloads.add(ReloadResult.success(List.of()));
        fixture.runtime.reloads.add(ReloadResult.success(List.of()));
        SubmitJob submission = SubmitJob.newBuilder()
            .setRequestId("matrix-request")
            .setWorkspaceId("workspace")
            .setContext(SceneContext.newBuilder().setSaveId("save")
                .setDimensionId("minecraft:overworld").setFov(70.0))
            .addShaderConfigs(NamedShaderConfig.newBuilder().setId("bad")
                .setConfig(ShaderConfig.newBuilder().putValues("SETTING_SAMPLE_COUNT", "0")))
            .addShaderConfigs(NamedShaderConfig.newBuilder().setId("good")
                .setConfig(ShaderConfig.newBuilder().putValues("SETTING_SAMPLE_COUNT", "32")))
            .setActions(ActionSequence.newBuilder()
                .addActions(load(sourceA, "source-a", "bad", "source-a--bad", true))
                .addActions(load(sourceA, "source-a", "good", "source-a--good", true))
                .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(2)))
                .addActions(load(sourceB, "source-b", "good", "source-b--good", true))
                .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(2))))
            .build();
        CoreJob job = new CoreJob(submission, "message", null);
        job.initialize(List.of(sourceA.lease, sourceB.lease));

        TerminalResult terminal = fixture.executor.execute(job, ignored -> {});

        assertEquals(List.of(
            "link:A", "reload", "detach",
            "link:A", "reload", "context", "frames",
            "link:B", "reload", "context", "frames"), fixture.runtime.events);
        var actionResults = terminal.completed().getResult().getActionResultsList();
        assertEquals(3, actionResults.size());
        assertTrue(actionResults.get(0).getJson().contains("\"success\":false"));
        assertTrue(actionResults.get(0).getJson().contains("SHADER_COMPILE_FAILED"));
        assertTrue(actionResults.get(1).getJson().contains("\"success\":true"));
        assertTrue(actionResults.get(2).getJson().contains("source-b--good"));
        assertEquals(sourceB.uuid, fixture.registry.activeUuid());
    }

    @Test
    void actionRejectsMismatchedPreparedSourceUuidBeforeActivation() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        SubmitJob submission = job(source.lease).submission.toBuilder()
            .setActions(ActionSequence.newBuilder().addActions(Action.newBuilder()
                .setActivateSource(ActivateSource.newBuilder().setSourceUuid(UUID.randomUUID().toString()))))
            .build();
        CoreJob job = new CoreJob(submission, "message", null);
        job.initialize(List.of(source.lease));

        RuntimeJobExecutor.Failure failure = assertThrows(
            RuntimeJobExecutor.Failure.class, () -> fixture.executor.execute(job, ignored -> {}));

        assertEquals(ErrorCode.INVALID_SOURCE_UUID, failure.code);
        assertTrue(fixture.runtime.events.isEmpty());
    }

    @Test
    void sourceIdentityChangedDuringReloadRollsBackWithoutDeletingReplacement() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        Path replacement = pending.resolve("replacement");
        fixture.runtime.beforeReloadResult = () -> {
            try {
                Files.move(source.path, replacement);
                Files.createDirectory(source.path);
                Files.writeString(source.path.resolve("sentinel.txt"), "external");
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        };

        RuntimeJobExecutor.Failure failure = assertThrows(
            RuntimeJobExecutor.Failure.class, () -> fixture.executor.execute(job(source.lease), ignored -> {}));
        fixture.activator.release(List.of(source.lease));

        assertEquals(ErrorCode.SOURCE_ACTIVATION_FAILED, failure.code);
        assertTrue(Files.isRegularFile(source.path.resolve("sentinel.txt")));
        assertTrue(fixture.activator.ready());
    }

    @Test
    void sourceIdentityChangedBeforeActivationIsReportedWithoutInvalidRetry() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        Path replacement = pending.resolve("replacement-before-activation");
        Files.move(source.path, replacement);
        Files.createDirectory(source.path);
        Files.writeString(source.path.resolve("sentinel.txt"), "external");

        RuntimeJobExecutor.Failure failure = assertThrows(
            RuntimeJobExecutor.Failure.class, () -> fixture.executor.execute(job(source.lease), ignored -> {}));

        assertEquals(ErrorCode.SOURCE_ACTIVATION_FAILED, failure.code);
        assertTrue(Files.isRegularFile(source.path.resolve("sentinel.txt")));
        assertTrue(fixture.activator.ready());
    }

    @Test
    void activeLinkTamperFailsAtFinalBoundaryAndMarksServerNotReady() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        fixture.link.tampered = true;

        RuntimeJobExecutor.Failure failure = assertThrows(
            RuntimeJobExecutor.Failure.class, () -> fixture.executor.execute(job(source.lease), ignored -> { }));

        assertEquals(ErrorCode.SYMLINK_SWITCH_FAILED, failure.code);
        assertFalse(fixture.activator.ready());
    }

    private static CoreJob job(SourceRegistry.Lease source) {
        SubmitJob submission = SubmitJob.newBuilder()
            .setRequestId("request")
            .setWorkspaceId("workspace")
            .setContext(SceneContext.newBuilder()
                .setSaveId("save")
                .setDimensionId("minecraft:overworld")
                .setFov(70.0))
            .setShaderConfig(ShaderConfig.newBuilder().putValues("SETTING_SAMPLE_COUNT", "32"))
            .setActions(ActionSequence.newBuilder()
                .addActions(Action.newBuilder().setActivateSource(
                    ActivateSource.newBuilder().setSourceUuid(source.uuid())))
                .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(3))))
            .build();
        CoreJob job = new CoreJob(submission, "message", null);
        job.initialize(List.of(source));
        return job;
    }

    private static CoreJob jobWithReset(SourceRegistry.Lease source) {
        SubmitJob submission = job(source).submission.toBuilder().setActions(ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setActivateSource(
                ActivateSource.newBuilder().setSourceUuid(source.uuid())))
            .addActions(Action.newBuilder().setResetTemporalState(
                dev.vibris.protocol.v1.ResetTemporalState.getDefaultInstance()))
            .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(3)))).build();
        CoreJob job = new CoreJob(submission, "message", null);
        job.initialize(List.of(source));
        return job;
    }

    private static Action.Builder load(
        Source source,
        String sourceId,
        String configId,
        String caseId,
        boolean continueOnFailure
    ) {
        return Action.newBuilder().setLoadShader(LoadShader.newBuilder()
            .setSourceUuid(source.uuid)
            .setSourceId(sourceId)
            .setConfigId(configId)
            .setCaseId(caseId)
            .setContinueOnFailure(continueOnFailure));
    }

    private static ReloadResult.Diagnostic error(String marker) {
        return new ReloadResult.Diagnostic(ReloadResult.Severity.ERROR, "composite.fsh", 17, marker);
    }

    private static void assertShaderLog(RuntimeJobExecutor.Failure failure, String marker) throws Exception {
        assertEquals(1, failure.artifacts.size());
        var artifact = failure.artifacts.getFirst();
        assertEquals("shader.log", artifact.getFileName());
        assertEquals(dev.vibris.protocol.v1.ArtifactKind.ARTIFACT_KIND_SHADER_COMPILE_LOG, artifact.getKind());
        assertEquals(dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_TEXT, artifact.getFormat());
        assertTrue(Files.readString(Path.of(artifact.getPath())).contains(marker));
        TerminalResult terminal = ProtocolMessages.failure(
            "request", failure.code, failure.getMessage(), failure.artifacts);
        assertEquals(artifact.getPath(), terminal.failed().getError().getLogPath());
        assertEquals(artifact, terminal.failed().getArtifacts(0));
    }

    private final class Fixture {
        final RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        final SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        final RecordingLink link = new RecordingLink(runtime.events);
        final SourceActivator activator = new SourceActivator(registry, link);
        final ArtifactManager artifacts = new ArtifactManager(pending.resolveSibling("artifacts"));
        final RuntimeJobExecutor executor = new RuntimeJobExecutor(runtime, new CoreProbe(), activator, artifacts);

        Source source(String marker) throws Exception {
            String uuid = UUID.randomUUID().toString();
            Path path = Files.createDirectory(pending.resolve(uuid));
            Path file = Files.writeString(path.resolve("main.glsl"), marker);
            PreparedSourceRef reference = PreparedSourceRef.newBuilder()
                .setUuid(uuid)
                .setFileCount(1)
                .setTotalBytes(Files.size(file))
                .build();
            List<SourceRegistry.Lease> leases = registry.reserve(registry.validate(List.of(reference)));
            registry.accept(leases);
            return new Source(uuid, path, leases.getFirst());
        }

        void activate(Source source) throws Exception {
            SourceActivator.Activation activation = activator.begin(source.lease);
            activator.commit(activation);
            activator.release(List.of(source.lease));
        }
    }

    private static final class RecordingLink implements ShaderLink {
        private final List<String> events;
        private boolean tampered;

        RecordingLink(List<String> events) {
            this.events = events;
        }

        @Override
        public void switchTo(SourceRegistry.Lease source, OwnershipCheck ownership) throws Failure {
            ownership.verify();
            try {
                events.add("link:" + Files.readString(source.directory().resolve("main.glsl")));
            } catch (java.io.IOException exception) {
                throw new Failure("test source could not be read", true, exception);
            }
        }

        @Override
        public void detach() {
            events.add("detach");
        }

        @Override
        public boolean retainsActiveSource() throws Failure {
            if (tampered) throw new Failure("active shader link changed", false);
            return true;
        }
    }

    private record Source(String uuid, Path path, SourceRegistry.Lease lease) {
    }
}
