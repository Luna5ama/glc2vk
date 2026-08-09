package dev.vibris.core;

import dev.vibris.api.ReloadResult;
import dev.vibris.api.RuntimeAction;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.protocol.v1.Action;
import dev.vibris.protocol.v1.ActionSequence;
import dev.vibris.protocol.v1.ActivateSource;
import dev.vibris.protocol.v1.BenchmarkCase;
import dev.vibris.protocol.v1.BenchmarkBarrierStage;
import dev.vibris.protocol.v1.BenchmarkProvenance;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.GetGpuMetrics;
import dev.vibris.protocol.v1.JobStage;
import dev.vibris.protocol.v1.JobTimeouts;
import dev.vibris.protocol.v1.LoadShader;
import dev.vibris.protocol.v1.NamedShaderConfig;
import dev.vibris.protocol.v1.PreparedSourceRef;
import dev.vibris.protocol.v1.ResultArtifactOptions;
import dev.vibris.protocol.v1.SceneContext;
import dev.vibris.protocol.v1.ShaderConfig;
import dev.vibris.protocol.v1.SubmitJob;
import dev.vibris.protocol.v1.WaitFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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
    void loadResetsTemporalStateAfterApplyingContext() throws Exception {
        Fixture fixture = new Fixture();
        Source source = fixture.source("A");
        List<JobStage> progress = new ArrayList<>();

        fixture.executor.execute(jobWithLoad(source), progress::add);
        fixture.activator.release(List.of(source.lease));

        assertEquals(List.of("link:A", "reload", "context", "reset", "action:InspectShader", "frames"),
            fixture.runtime.events);
        assertEquals(List.of(
            JobStage.JOB_STAGE_ACTIVATING_SOURCE,
            JobStage.JOB_STAGE_RELOADING_SHADERS,
            JobStage.JOB_STAGE_LOADING_WORLD,
            JobStage.JOB_STAGE_APPLYING_CONTEXT,
            JobStage.JOB_STAGE_RESETTING_TEMPORAL_STATE,
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
    void benchmarkCaseHashIsStableAcrossRetryAndChangesWithEffectiveConfig() throws Exception {
        Fixture fixture = new Fixture();
        Source firstSource = fixture.source("same");
        Source retrySource = fixture.source("same");
        String inspection = "{\"status\":\"ok\",\"patched_shader\":{" +
            "\"available\":true,\"sha256\":\"" + "b".repeat(64) + "\",\"generation\":7}}";
        fixture.runtime.actionResponses.add(inspection);
        fixture.runtime.actionResponses.add(inspection.replace("\"generation\":7", "\"generation\":8"));
        fixture.runtime.actionResponses.add(inspection);

        String first = loadActionJson(fixture.executor.execute(jobWithLoad(firstSource, "32"), ignored -> {}));
        String retry = loadActionJson(fixture.executor.execute(jobWithLoad(retrySource, "32"), ignored -> {}));
        String changed = loadActionJson(fixture.executor.execute(jobWithLoad(retrySource, "64"), ignored -> {}));

        assertEquals(jsonString(first, "case_hash"), jsonString(retry, "case_hash"));
        assertFalse(jsonString(first, "case_hash").equals(jsonString(changed, "case_hash")));
        assertTrue(first.contains("\"complete\":true"));
        assertTrue(first.contains("\"active_source_uuid\":\"" + firstSource.uuid + "\""));
        assertTrue(retry.contains("\"active_source_uuid\":\"" + retrySource.uuid + "\""));
    }

    @Test
    void matrixLoadFailureIsRecordedAndLaterCasesContinue() throws Exception {
        Fixture fixture = new Fixture();
        Source sourceA = fixture.source("A");
        Source sourceB = fixture.source("B");
        fixture.runtime.reloads.add(ReloadResult.failurePreservingActiveState(List.of(error("bad config"))));
        fixture.runtime.reloads.add(ReloadResult.success(List.of()));
        fixture.runtime.reloads.add(ReloadResult.success(List.of()));
        fixture.runtime.actionResponses.add(
            "{\"status\":\"ok\",\"pack_loaded\":true,\"shaderpack\":\"vibris\",\"errors\":[]}");
        fixture.runtime.actionResponses.add(
            "{\"status\":\"ok\",\"pack_loaded\":true,\"shaderpack\":\"vibris\",\"errors\":[]}");
        SubmitJob submission = SubmitJob.newBuilder()
            .setRequestId("matrix-request")
            .setWorkspaceId("11111111-1111-4111-8111-111111111111")
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
            "link:A", "reload", "context", "reset", "action:InspectShader", "frames",
            "link:B", "reload", "context", "reset", "action:InspectShader", "frames"), fixture.runtime.events);
        var actionResults = terminal.completed().getResult().getActionResultsList();
        assertEquals(3, actionResults.size());
        assertTrue(actionResults.get(0).getJson().contains("\"success\":false"));
        assertTrue(actionResults.get(0).getJson().contains("SHADER_COMPILE_FAILED"));
        assertTrue(actionResults.get(0).getJson().contains("bad config"));
        assertTrue(actionResults.get(1).getJson().contains("\"success\":true"));
        assertTrue(actionResults.get(1).getJson().contains("\"pack_loaded\":true"));
        assertTrue(actionResults.get(1).getJson().contains("\"diagnostics\":[]"));
        assertTrue(actionResults.get(2).getJson().contains("source-b--good"));
        assertEquals(sourceB.uuid, fixture.registry.activeUuid());
    }

    @Test
    void isolatedCaseSnapshotsPreserveConfigAndRestoresSourceConfigAndScene() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("baseline");
        fixture.executor.execute(jobWithLoad(baseline, "32"), ignored -> {});
        fixture.activator.release(List.of(baseline.lease));
        Source candidate = fixture.source("candidate");
        fixture.runtime.events.clear();
        fixture.runtime.shaderConfigs.clear();
        fixture.runtime.contexts.clear();
        fixture.runtime.actionResponses.add(shaderInspection(11));
        fixture.runtime.actionResponses.add(gpuMetrics(512));

        TerminalResult terminal = fixture.executor.execute(
            isolatedJob(candidate, "candidate--preserve", null), ignored -> {});
        fixture.activator.release(List.of(candidate.lease));

        assertEquals(List.of(
            "link:candidate", "reload", "context", "reset", "action:InspectShader", "frames",
            "action:GpuMetrics", "link:baseline", "reload", "context", "reset"), fixture.runtime.events);
        assertEquals(List.of(
            Map.of("SETTING_SAMPLE_COUNT", "32"),
            Map.of("SETTING_SAMPLE_COUNT", "32")), fixture.runtime.shaderConfigs);
        assertEquals("matrix-save", fixture.runtime.contexts.get(0).saveId());
        assertEquals("save", fixture.runtime.contexts.get(1).saveId());
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
        assertFalse(Files.exists(candidate.path));
        assertEquals(
            List.of(
                BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SOURCE_PUBLISHED,
                BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_CONFIG_APPLIED,
                BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SHADER_RELOADED,
                BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SHADER_GENERATION_CONFIRMED,
                BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_WARMUP_STARTED,
                BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_WARMUP_COMPLETED,
                BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SAMPLE_STARTED,
                BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_SAMPLE_COMPLETED,
                BenchmarkBarrierStage.BENCHMARK_BARRIER_STAGE_STATE_RESTORED),
            terminal.completed().getResult().getBenchmarkBarriersList().stream()
                .map(receipt -> receipt.getStage())
                .toList());
        assertTrue(terminal.completed().getResult().getActionResultsList().stream()
            .allMatch(result -> result.getCaseId().equals("candidate--preserve")));
    }

    @Test
    void delayedReloadCannotCrossWarmupOrSampleBarrier() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("baseline");
        fixture.executor.execute(jobWithLoad(baseline, "32"), ignored -> {});
        fixture.activator.release(List.of(baseline.lease));
        Source candidate = fixture.source("candidate");
        fixture.runtime.events.clear();
        CompletableFuture<ReloadResult> delayed = new CompletableFuture<>();
        fixture.runtime.reloadStages.add(delayed);
        fixture.runtime.actionResponses.add(shaderInspection(12));
        fixture.runtime.actionResponses.add(gpuMetrics(512));
        CountDownLatch reloadEntered = new CountDownLatch(1);
        fixture.runtime.beforeReloadResult = reloadEntered::countDown;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread execution = new Thread(() -> {
            try {
                fixture.executor.execute(isolatedJob(candidate, "candidate--512", "512"), ignored -> {});
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        execution.start();
        assertTrue(reloadEntered.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("link:candidate", "reload"), fixture.runtime.events);
        delayed.complete(ReloadResult.success(List.of()));
        execution.join(5_000);
        fixture.activator.release(List.of(candidate.lease));

        assertFalse(execution.isAlive());
        assertEquals(null, failure.get());
        assertTrue(fixture.runtime.events.indexOf("frames") > fixture.runtime.events.indexOf("reload"));
        assertTrue(fixture.runtime.events.indexOf("action:GpuMetrics") > fixture.runtime.events.indexOf("frames"));
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
    }

    @Test
    void executionTimeoutWaitsForReloadAndRollbackSafePoints() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("baseline");
        fixture.activate(baseline);
        Source candidate = fixture.source("candidate");
        TimedGetFailsFuture<ReloadResult> candidateReload = new TimedGetFailsFuture<>();
        TimedGetFailsFuture<ReloadResult> baselineReload = new TimedGetFailsFuture<>();
        fixture.runtime.reloadStages.add(candidateReload);
        fixture.runtime.reloadStages.add(baselineReload);
        CoreJob job = job(candidate.lease);
        job = new CoreJob(job.submission.toBuilder()
            .setTimeouts(JobTimeouts.newBuilder().setExecutionTimeoutMs(5_000))
            .build(), job.messageId, null);
        job.initialize(List.of(candidate.lease));
        AtomicReference<RuntimeJobExecutor.Failure> failure = new AtomicReference<>();
        CoreJob timedJob = job;
        Thread execution = new Thread(() -> {
            try {
                fixture.executor.execute(timedJob, ignored -> {});
            } catch (RuntimeJobExecutor.Failure expected) {
                failure.set(expected);
            }
        });

        execution.start();
        assertTrue(candidateReload.safePointAwaitEntered.await(2, TimeUnit.SECONDS));
        assertTrue(execution.isAlive());
        candidateReload.complete(ReloadResult.success(List.of()));
        assertTrue(baselineReload.safePointAwaitEntered.await(2, TimeUnit.SECONDS));
        assertTrue(execution.isAlive());
        baselineReload.complete(ReloadResult.success(List.of()));
        execution.join(5_000);
        fixture.activator.release(List.of(candidate.lease));

        assertFalse(execution.isAlive());
        assertEquals(ErrorCode.EXECUTION_TIMEOUT, failure.get().code);
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
        assertTrue(fixture.activator.ready());
    }

    @Test
    void benchmarkRestoreWaitsForLongShaderReload() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("baseline");
        fixture.executor.execute(jobWithLoad(baseline, "32"), ignored -> {});
        fixture.activator.release(List.of(baseline.lease));
        Source candidate = fixture.source("candidate");
        TimedGetFailsFuture<ReloadResult> baselineReload = new TimedGetFailsFuture<>();
        fixture.runtime.reloadStages.add(CompletableFuture.completedFuture(ReloadResult.success(List.of())));
        fixture.runtime.reloadStages.add(baselineReload);
        fixture.runtime.actionResponses.add(shaderInspection(14));
        fixture.runtime.actionResponses.add(gpuMetrics(512));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread execution = new Thread(() -> {
            try {
                fixture.executor.execute(isolatedJob(candidate, "candidate--long-restore", "512"), ignored -> {});
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        execution.start();
        assertTrue(baselineReload.safePointAwaitEntered.await(2, TimeUnit.SECONDS));
        assertTrue(execution.isAlive());
        baselineReload.complete(ReloadResult.success(List.of()));
        execution.join(5_000);
        fixture.activator.release(List.of(candidate.lease));

        assertFalse(execution.isAlive());
        assertEquals(null, failure.get());
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
        assertTrue(fixture.activator.ready());
    }

    @Test
    void cancellationRestoresBaselineBeforeReturningFailure() throws Exception {
        Fixture fixture = new Fixture();
        Source baseline = fixture.source("baseline");
        fixture.executor.execute(jobWithLoad(baseline, "32"), ignored -> {});
        fixture.activator.release(List.of(baseline.lease));
        Source candidate = fixture.source("candidate");
        CoreJob job = isolatedJob(candidate, "candidate--1024", "1024");
        CompletableFuture<String> metrics = new CompletableFuture<>();
        fixture.runtime.actionStages.add(CompletableFuture.completedFuture(shaderInspection(13)));
        fixture.runtime.actionStages.add(metrics);
        CountDownLatch sampling = new CountDownLatch(1);
        fixture.runtime.actionObserver = action -> {
            if (action instanceof RuntimeAction.GpuMetrics) sampling.countDown();
        };
        AtomicReference<RuntimeJobExecutor.Failure> failure = new AtomicReference<>();
        Thread execution = new Thread(() -> {
            try {
                fixture.executor.execute(job, ignored -> {});
            } catch (RuntimeJobExecutor.Failure expected) {
                failure.set(expected);
            }
        });

        execution.start();
        assertTrue(sampling.await(2, TimeUnit.SECONDS));
        job.cancellation.cancel();
        metrics.completeExceptionally(new CancellationException("cancelled fixture sample"));
        execution.join(5_000);
        fixture.activator.release(List.of(candidate.lease));

        assertFalse(execution.isAlive());
        assertEquals(ErrorCode.CANCELLED, failure.get().code);
        assertEquals(baseline.uuid, fixture.registry.activeUuid());
        assertEquals(List.of("link:baseline", "reload", "context", "reset"),
            fixture.runtime.events.subList(fixture.runtime.events.size() - 4, fixture.runtime.events.size()));
    }

    @Test
    void isolatedCaseFailsClosedWithoutRestorableBaseline() throws Exception {
        Fixture fixture = new Fixture();
        Source candidate = fixture.source("candidate");

        RuntimeJobExecutor.Failure failure = assertThrows(RuntimeJobExecutor.Failure.class,
            () -> fixture.executor.execute(isolatedJob(candidate, "candidate--512", "512"), ignored -> {}));
        fixture.activator.release(List.of(candidate.lease));

        assertEquals(ErrorCode.BENCHMARK_STATE_UNAVAILABLE, failure.code);
        assertTrue(fixture.runtime.events.isEmpty());
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
            .setWorkspaceId("11111111-1111-4111-8111-111111111111")
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

    private static CoreJob jobWithLoad(Source source) {
        return jobWithLoad(source, "32");
    }

    private static CoreJob jobWithLoad(Source source, String sampleCount) {
        SubmitJob submission = SubmitJob.newBuilder()
            .setRequestId("request")
            .setWorkspaceId("11111111-1111-4111-8111-111111111111")
            .setContext(SceneContext.newBuilder()
                .setSaveId("save")
                .setDimensionId("minecraft:overworld")
                .setTimePresetId("noon")
                .setWeatherPresetId("clear")
                .setCameraPresetId("spawn")
                .setSettingsPresetId("quality")
                .setResolution(dev.vibris.protocol.v1.Resolution.newBuilder().setWidth(1920).setHeight(1080))
                .setFov(70.0))
            .setBenchmarkProvenance(BenchmarkProvenance.newBuilder()
                .setPresetId("spawn").setPresetVersion("2").setPresetDisplayName("Spawn"))
            .addShaderConfigs(NamedShaderConfig.newBuilder().setId("config")
                .setConfig(ShaderConfig.newBuilder().putValues("SETTING_SAMPLE_COUNT", sampleCount)))
            .setActions(ActionSequence.newBuilder()
                .addActions(load(source, "source", "config", "source--config", false))
                .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(3))))
            .build();
        CoreJob job = new CoreJob(submission, "message", null);
        job.initialize(List.of(source.lease));
        return job;
    }

    private static CoreJob isolatedJob(Source source, String caseId, String sampleCount) {
        NamedShaderConfig.Builder config = NamedShaderConfig.newBuilder().setId("config");
        if (sampleCount == null) {
            config.setPreserve(true);
        } else {
            config.setConfig(ShaderConfig.newBuilder().putValues("SETTING_SAMPLE_COUNT", sampleCount));
        }
        SubmitJob submission = SubmitJob.newBuilder()
            .setRequestId("matrix-request-" + caseId)
            .setWorkspaceId("11111111-1111-4111-8111-111111111111")
            .setContext(SceneContext.newBuilder()
                .setSaveId("matrix-save")
                .setDimensionId("minecraft:the_nether")
                .setTimePresetId("midnight")
                .setWeatherPresetId("rain")
                .setCameraPresetId("matrix-camera")
                .setSettingsPresetId("quality")
                .setResolution(dev.vibris.protocol.v1.Resolution.newBuilder().setWidth(1280).setHeight(720))
                .setFov(80.0))
            .setBenchmarkProvenance(BenchmarkProvenance.newBuilder()
                .setPresetId("matrix").setPresetVersion("2").setPresetDisplayName("Matrix"))
            .setBenchmarkCase(BenchmarkCase.newBuilder()
                .setWorkflowId("33333333-3333-4333-8333-333333333333")
                .setCaseId(caseId))
            .setResultArtifacts(ResultArtifactOptions.newBuilder()
                .setJson(true).setKind("profile_matrix").setAttempt(1))
            .addShaderConfigs(config)
            .setActions(ActionSequence.newBuilder()
                .addActions(load(source, "candidate", "config", caseId, false))
                .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(2)))
                .addActions(Action.newBuilder().setGetGpuMetrics(GetGpuMetrics.newBuilder().setFrames(3))))
            .build();
        CoreJob job = new CoreJob(submission, "message-" + caseId, null);
        job.initialize(List.of(source.lease));
        return job;
    }

    private static String shaderInspection(long generation) {
        return "{\"status\":\"ok\",\"patched_shader\":{" +
            "\"available\":true,\"sha256\":\"" + "b".repeat(64) +
            "\",\"generation\":" + generation + "}}";
    }

    private static String gpuMetrics(long average) {
        return "{\"gpuTimings\":{\"composite_total\":{\"avg\":" + average +
            ",\"p5\":" + average + ",\"p50\":" + average + ",\"p95\":" + average + "}}}";
    }

    private static String loadActionJson(TerminalResult terminal) {
        return terminal.completed().getResult().getActionResults(0).getJson();
    }

    private static String jsonString(String json, String field) {
        String prefix = "\"" + field + "\":\"";
        int start = json.indexOf(prefix);
        if (start < 0) throw new AssertionError("Missing JSON field: " + field);
        start += prefix.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
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
                .setRequestedRevision("workspace")
                .setResolvedRevision("a".repeat(40))
                .setOrigin(dev.vibris.protocol.v1.SourceOrigin.newBuilder()
                    .setWorkspace(dev.vibris.protocol.v1.WorkspaceOrigin.newBuilder().setDisplayName("fixture")))
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

    private static final class TimedGetFailsFuture<T> extends CompletableFuture<T> {
        private final CountDownLatch safePointAwaitEntered = new CountDownLatch(1);

        @Override
        public T join() {
            safePointAwaitEntered.countDown();
            return super.join();
        }

        @Override
        public T get(long timeout, TimeUnit unit)
            throws InterruptedException, java.util.concurrent.ExecutionException, TimeoutException {
            throw new TimeoutException("timed wait rejected by fixture");
        }
    }

    private record Source(String uuid, Path path, SourceRegistry.Lease lease) {
    }
}