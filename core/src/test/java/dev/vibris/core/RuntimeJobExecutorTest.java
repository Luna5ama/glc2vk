package dev.vibris.core;

import dev.vibris.api.EffectiveShaderSettings;
import dev.vibris.api.CompileCatalog;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.protocol.v2.Action;
import dev.vibris.protocol.v2.ActionKind;
import dev.vibris.protocol.v2.ActionSequence;
import dev.vibris.protocol.v2.ActivateSource;
import dev.vibris.protocol.v2.ErrorCode;
import dev.vibris.protocol.v2.CompileValidationCase;
import dev.vibris.protocol.v2.CompileValidationRequest;
import dev.vibris.protocol.v2.JobSpec;
import dev.vibris.protocol.v2.InspectShader;
import dev.vibris.protocol.v2.LoadShader;
import dev.vibris.protocol.v2.PreparedSourceRef;
import dev.vibris.protocol.v2.ReceiptStatus;
import dev.vibris.protocol.v2.ResetTemporalState;
import dev.vibris.protocol.v2.SceneContext;
import dev.vibris.protocol.v2.ShaderConfig;
import dev.vibris.protocol.v2.WaitFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeJobExecutorTest {
    private static final String WORKSPACE_ID = "11111111-1111-4111-8111-111111111111";

    @TempDir
    Path pending;

    @Test
    void loadAppliesContextResetsTemporalStateAndReturnsTypedReceipts() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        EffectiveShaderSettings runtimeSettings = EffectiveShaderSettings.of(List.of(
            new EffectiveShaderSettings.Setting(
                "SETTING_SAMPLE_COUNT",
                "64",
                "16",
                EffectiveShaderSettings.Origin.PRESET
            )
        ));
        fixture.runtime.reloads.add(ReloadResult.success(runtimeSettings, List.of()));
        CoreJob job = fixture.job(source, ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setLoadShader(LoadShader.newBuilder()
                .setSourceUuid(source.uuid)
                .setSourceId("candidate")
                .setConfigId("quality")
                .setConfig(ShaderConfig.newBuilder().putValues("SETTING_SAMPLE_COUNT", "32"))))
            .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(3)))
            .build());

        TerminalResult terminal = fixture.executor.execute(job, ignored -> {});

        assertEquals(List.of("link:A", "reload", "context", "reset", "compile_catalog", "frames"),
            fixture.runtime.events);
        assertEquals("32", fixture.runtime.lastShaderConfig.get("SETTING_SAMPLE_COUNT"));
        assertEquals(2, terminal.completed().getResult().getActionReceiptsCount());
        assertEquals(ActionKind.ACTION_KIND_LOAD_SHADER,
            terminal.completed().getResult().getActionReceipts(0).getKind());
        assertEquals(source.uuid,
            terminal.completed().getResult().getActionReceipts(0).getRuntimeMutation().getSourceUuid());
        assertFalse(terminal.completed().getResult().getActionReceipts(0)
            .getRuntimeMutation().getSourceSha256().isBlank());
        assertFalse(terminal.completed().getResult().getActionReceipts(0)
            .getRuntimeMutation().getSceneSha256().isBlank());
        var effective = terminal.completed().getResult().getActionReceipts(0)
            .getRuntimeMutation().getEffectiveSettings();
        assertEquals(runtimeSettings.settingsSha256(), effective.getSettingsSha256());
        assertEquals("64", effective.getSettings(0).getValue());
        assertEquals(dev.vibris.protocol.v2.ShaderSettingOrigin.SHADER_SETTING_ORIGIN_PRESET,
            effective.getSettings(0).getOrigin());
        assertTrue(effective.getSettings(0).getChangedFromDefault());
        assertEquals(runtimeSettings.settingsSha256(),
            terminal.completed().getResult().getRestoration().getActualSettingsSha256());
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK,
            terminal.completed().getResult().getActionReceipts(1).getStatus());
        assertEquals(3,
            terminal.completed().getResult().getActionReceipts(1).getWaitFrames().getCompletedFrames());
        assertEquals(0,
            terminal.completed().getResult().getActionReceipts(1).getWaitFrames().getStartFrame());
        assertEquals(3,
            terminal.completed().getResult().getActionReceipts(1).getWaitFrames().getEndFrame());
        var provenance = terminal.completed().getResult().getProvenance();
        assertEquals(source.uuid, provenance.getActiveSourceUuid());
        assertEquals(runtimeSettings.settingsSha256(), provenance.getConfigSha256());
        assertFalse(provenance.getSceneSha256().isBlank());
        assertEquals(fixture.runtime.compileCatalog.mappingSha256(), provenance.getPassMappingSha256());
        assertTrue(provenance.getShaderLoadedAtUnixMs() > 0);
        assertFalse(provenance.getEnvironment().getJavaVersion().isBlank());
    }

    @Test
    void failedCandidateReloadRestoresPreviousSourceAndKeepsTypedCompileFailure() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("baseline");
        fixture.activate(baseline);
        Source candidate = fixture.source("candidate");
        fixture.runtime.reloads.add(ReloadResult.failure(List.of(error("candidate failed"))));
        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.activateJob(candidate), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_SHADER_COMPILE_FAILED, failure.code);
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
        assertTrue(fixture.activator.ready());
        assertEquals(List.of("link:baseline", "link:candidate", "reload", "link:baseline", "reload"),
            fixture.runtime.events);
        assertEquals(1, failure.artifacts.size());
        assertTrue(Files.readString(Path.of(failure.artifacts.getFirst().getRelativePath()))
            .contains("candidate failed"));
    }

    @Test
    void resetFailureStopsBeforeWaitFrames() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        fixture.runtime.reset = new TemporalResetResult(false);
        CoreJob job = fixture.job(source, ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setActivateSource(ActivateSource.newBuilder()
                .setSourceUuid(source.uuid)))
            .addActions(Action.newBuilder().setResetTemporalState(ResetTemporalState.getDefaultInstance()))
            .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(3)))
            .build());

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(job, ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_INTERNAL, failure.code);
        assertFalse(fixture.runtime.events.contains("frames"));
        assertEquals(List.of(0, 1, 2),
            failure.actionReceipts.stream().map(receipt -> receipt.getActionIndex()).toList());
        assertEquals(List.of(
                ReceiptStatus.RECEIPT_STATUS_OK,
                ReceiptStatus.RECEIPT_STATUS_FAILED,
                ReceiptStatus.RECEIPT_STATUS_CANCELLED),
            failure.actionReceipts.stream().map(receipt -> receipt.getStatus()).toList());
        assertTrue(failure.actionReceipts.get(1).hasError());
        assertEquals(ErrorCode.ERROR_CODE_INTERNAL, failure.actionReceipts.get(1).getError().getCode());
        assertTrue(failure.actionReceipts.get(2).hasError());
        assertEquals(ErrorCode.ERROR_CODE_CANCELLED, failure.actionReceipts.get(2).getError().getCode());
        assertTrue(failure.preludeReceipts.isEmpty());

        var terminal = ProtocolMessages.failure(
            job.submission.getJobId(),
            job.requestId,
            failure.code,
            failure.getMessage(),
            failure.artifacts,
            failure.restoration,
            failure.actionReceipts,
            failure.preludeReceipts);
        assertEquals(3, terminal.failed().getActionReceiptsCount());
    }

    @Test
    void routesSynthesizedLoadToPreludeAndKeepsUserIndicesContiguous() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        CoreJob job = fixture.job(source, ActionSequence.newBuilder()
            .addActions(Action.newBuilder()
                .setPrelude(true)
                .setLoadShader(LoadShader.newBuilder()
                    .setSourceUuid(source.uuid)
                    .setSourceId("candidate")
                    .setConfigId("preserve")
                    .setConfig(ShaderConfig.newBuilder().setPreserveCurrent(true))))
            .addActions(Action.newBuilder().setResetTemporalState(ResetTemporalState.getDefaultInstance()))
            .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(2)))
            .build());

        TerminalResult terminal = fixture.executor.execute(job, ignored -> {});
        var result = terminal.completed().getResult();

        assertEquals(1, result.getPreludeReceiptsCount());
        assertEquals(3, result.getPreludeReceiptsCount() + result.getActionReceiptsCount());
        assertEquals(0, result.getPreludeReceipts(0).getActionIndex());
        assertEquals(ActionKind.ACTION_KIND_LOAD_SHADER, result.getPreludeReceipts(0).getKind());
        assertEquals(List.of(0, 1),
            result.getActionReceiptsList().stream().map(receipt -> receipt.getActionIndex()).toList());
        assertEquals(ActionKind.ACTION_KIND_RESET_TEMPORAL_STATE, result.getActionReceipts(0).getKind());
        assertTrue(result.getActionReceipts(0).hasResetTemporal());
        assertTrue(result.getActionReceipts(0).getResetTemporal().getCompletedAtUnixMs() > 0);
        assertEquals(ActionKind.ACTION_KIND_WAIT_FRAMES, result.getActionReceipts(1).getKind());
    }

    @Test
    void shaderInspectionReturnsTheCanonicalTypedCatalog() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        var diagnostic = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.ERROR, "final.fsh", 9, 2, "link failed"
        );
        fixture.runtime.compileCatalog = CompileCatalog.of(List.of(
            CompileCatalog.ProgramEntry.of(
                "final", "final", List.of(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
                CompileCatalog.CompileState.SUCCEEDED, CompileCatalog.CompileState.FAILED,
                "a".repeat(64), List.of(diagnostic)
            )
        ), 12);
        CoreJob job = fixture.job(source, ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setInspectShader(InspectShader.getDefaultInstance()))
            .build());

        TerminalResult terminal = fixture.executor.execute(job, ignored -> {});
        var receipt = terminal.completed().getResult().getActionReceipts(0);
        var catalog = receipt.getShaderInspection().getCatalog();

        assertEquals(List.of("compile_catalog"), fixture.runtime.events);
        assertEquals(ActionKind.ACTION_KIND_INSPECT_SHADER, receipt.getKind());
        assertEquals(fixture.runtime.compileCatalog.mappingSha256(), catalog.getMappingSha256());
        assertEquals(12, catalog.getShaderGeneration());
        assertEquals(dev.vibris.protocol.v2.CompileState.COMPILE_STATE_FAILED,
            catalog.getPrograms(0).getLinkState());
        assertEquals(diagnostic.fingerprintSha256(),
            catalog.getPrograms(0).getDiagnostics(0).getFingerprintSha256());
    }

    @Test
    void compileValidationReturnsCompleteCatalogDiffAndRestoresWithoutRendering() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("baseline");
        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        fixture.executor.execute(fixture.loadJob(baseline), ignored -> {});
        fixture.runtime.events.clear();

        Source candidate = fixture.source("candidate");
        var unchanged = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.WARNING, "common.glsl", 4, 1, "shared warning");
        var added = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.ERROR, "candidate.fsh", 9, 2, "candidate failed");
        var resolved = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.WARNING, "baseline.glsl", 7, 1, "baseline warning");
        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        fixture.runtime.reloads.add(ReloadResult.failure(List.of(error("candidate failed"))));
        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        fixture.runtime.compileCatalogs.add(catalog(List.of(unchanged, resolved), false));
        fixture.runtime.compileCatalogs.add(catalog(List.of(unchanged, added), true));

        CompileValidationRequest validation = CompileValidationRequest.newBuilder()
            .setBaseline(compileCase("baseline", baseline, "base"))
            .addCases(compileCase("candidate", candidate, "quality"))
            .build();
        TerminalResult terminal = fixture.executor.execute(
            fixture.compileJob(List.of(baseline, candidate), validation), ignored -> {});
        var result = terminal.completed().getResult();
        var compile = result.getCompileValidation().getCases(0);

        assertEquals(1, compile.getAddedDiagnosticsCount());
        assertEquals(1, compile.getResolvedDiagnosticsCount());
        assertEquals(1, compile.getUnchangedDiagnosticsCount());
        assertEquals(added.fingerprintSha256(), compile.getAddedDiagnostics(0).getFingerprintSha256());
        assertEquals(resolved.fingerprintSha256(), compile.getResolvedDiagnostics(0).getFingerprintSha256());
        assertEquals(CompileCatalogProtocol.INSTANCE.toProtocol(catalog(List.of(unchanged, added), true)),
            compile.getCatalog());
        assertEquals(candidate.uuid, compile.getProvenance().getActiveSourceUuid());
        assertEquals(candidate.uuid, result.getProvenance().getActiveSourceUuid());
        assertEquals(ReceiptStatus.RECEIPT_STATUS_OK, result.getRestoration().getStatus());
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
        assertFalse(fixture.runtime.events.contains("frames"));
        assertEquals(1, fixture.runtime.events.stream().filter("context"::equals).count());
        assertEquals(2, fixture.runtime.events.stream().filter("compile_catalog"::equals).count());
    }

    @Test
    void compileValidationFailsClosedWhenBaselineDoesNotCompile() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("baseline");
        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        fixture.executor.execute(fixture.loadJob(baseline), ignored -> {});
        fixture.runtime.events.clear();

        Source candidate = fixture.source("candidate");
        var baselineError = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.ERROR, "baseline.fsh", 3, 1, "baseline failed");
        fixture.runtime.reloads.add(ReloadResult.failure(List.of(error("baseline failed"))));
        fixture.runtime.reloads.add(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
        fixture.runtime.compileCatalogs.add(catalog(baselineError, true));
        CompileValidationRequest validation = CompileValidationRequest.newBuilder()
            .setBaseline(compileCase("baseline", baseline, "base"))
            .addCases(compileCase("candidate", candidate, "quality"))
            .build();

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.compileJob(List.of(baseline, candidate), validation), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_SHADER_COMPILE_FAILED, failure.code);
        assertEquals(1, fixture.runtime.events.stream().filter("compile_catalog"::equals).count());
        assertFalse(fixture.runtime.events.contains("frames"));
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
    }

    @Test
    void actionRejectsSourceOutsidePreparedSetBeforeActivation() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        CoreJob job = fixture.job(source, ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setActivateSource(ActivateSource.newBuilder()
                .setSourceUuid(UUID.randomUUID().toString())))
            .build());

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(job, ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_INVALID_SOURCE, failure.code);
        assertTrue(fixture.runtime.events.isEmpty());
    }

    @Test
    void sourceIdentityChangedDuringReloadDoesNotDeleteReplacement() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        Path original = source.path;
        Path moved = pending.resolve("moved-source");
        fixture.runtime.beforeReloadResult = () -> {
            try {
                Files.move(original, moved);
                Files.createDirectory(original);
                Files.writeString(original.resolve("sentinel.txt"), "external");
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        };

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.activateJob(source), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, failure.code);
        assertTrue(Files.isRegularFile(original.resolve("sentinel.txt")));
        assertTrue(fixture.activator.ready());
    }

    @Test
    void activeLinkTamperFailsAtFinalBoundaryAndMarksCoreNotReady() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        fixture.link.tampered = true;

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(fixture.activateJob(source), ignored -> {}));

        assertEquals(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, failure.code);
        assertFalse(fixture.activator.ready());
    }

    private static ReloadResult.Diagnostic error(String marker) {
        return new ReloadResult.Diagnostic(ReloadResult.Severity.ERROR, "composite.fsh", 17, marker);
    }

    private static CompileCatalog catalog(CompileCatalog.Diagnostic diagnostic, boolean failed) {
        return catalog(List.of(diagnostic), failed);
    }

    private static CompileCatalog catalog(List<CompileCatalog.Diagnostic> diagnostics, boolean failed) {
        return CompileCatalog.of(List.of(CompileCatalog.ProgramEntry.of(
            "final", "final", List.of(CompileCatalog.ShaderStage.FRAGMENT),
            failed ? CompileCatalog.CompileState.FAILED : CompileCatalog.CompileState.SUCCEEDED,
            failed ? CompileCatalog.CompileState.NOT_APPLICABLE : CompileCatalog.CompileState.SUCCEEDED,
            "a".repeat(64), diagnostics)), 8);
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
                .setSourceUuid(uuid)
                .setRequestedRevision("workspace")
                .setResolvedRevision("a".repeat(40))
                .setOrigin(dev.vibris.protocol.v2.SourceOrigin.newBuilder()
                    .setWorkspace(dev.vibris.protocol.v2.WorkspaceOrigin.newBuilder().setDisplayName("fixture")))
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

        CoreJob activateJob(Source source) {
            return job(source, ActionSequence.newBuilder()
                .addActions(Action.newBuilder().setActivateSource(ActivateSource.newBuilder()
                    .setSourceUuid(source.uuid)))
                .build());
        }

        CoreJob loadJob(Source source) {
            return job(source, ActionSequence.newBuilder()
                .addActions(Action.newBuilder().setLoadShader(LoadShader.newBuilder()
                    .setSourceUuid(source.uuid)
                    .setSourceId("source")
                    .setConfigId("config")
                    .setConfig(ShaderConfig.newBuilder().setPreserveCurrent(true))))
                .build());
        }

        CompileValidationCase compileCase(String id, Source source, String configId) {
            return RuntimeJobExecutorTest.compileCase(id, source, configId);
        }

        CoreJob compileJob(List<Source> sources, CompileValidationRequest validation) {
            JobSpec.Builder spec = JobSpec.newBuilder()
                .setJobId("job-" + UUID.randomUUID())
                .setContext(SceneContext.newBuilder()
                    .setSaveId("save")
                    .setDimensionId("minecraft:overworld")
                    .setFov(70.0))
                .setCompileValidation(validation);
            sources.forEach(source -> spec.addSources(source.lease.reference()));
            CoreJob job = new CoreJob(spec.build(), spec.getJobId(), WORKSPACE_ID, "message", null);
            job.initialize(sources.stream().map(Source::lease).toList());
            return job;
        }

        CoreJob job(Source source, ActionSequence actions) {
            JobSpec spec = JobSpec.newBuilder()
                .setJobId("job-" + UUID.randomUUID())
                .setContext(SceneContext.newBuilder()
                    .setSaveId("save")
                    .setDimensionId("minecraft:overworld")
                    .setFov(70.0))
                .addSources(source.lease.reference())
                .setActionSequence(actions)
                .build();
            CoreJob job = new CoreJob(spec, spec.getJobId(), WORKSPACE_ID, "message", null);
            job.initialize(List.of(source.lease));
            return job;
        }
    }

    private static CompileValidationCase compileCase(String id, Source source, String configId) {
        return CompileValidationCase.newBuilder()
            .setCaseId(id)
            .setSourceId(source.uuid)
            .setConfigId(configId)
            .setConfig(ShaderConfig.newBuilder().setPreserveCurrent(true))
            .build();
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
