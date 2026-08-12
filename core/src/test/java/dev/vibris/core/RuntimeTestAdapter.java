package dev.vibris.core;

import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.CompileCatalog;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.DeterministicTemporalCaptureOutcome;
import dev.vibris.api.DeterministicTemporalCapturePlanner;
import dev.vibris.api.DeterministicTemporalCapturePlanning;
import dev.vibris.api.DeterministicTemporalCaptureReloaded;
import dev.vibris.api.DeterministicTemporalCaptureRequest;
import dev.vibris.api.EffectiveShaderSettings;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.RuntimeStatus;
import dev.vibris.api.RuntimeAction;
import dev.vibris.api.RuntimeEnvironment;
import dev.vibris.api.SceneContext;
import dev.vibris.api.ScenePreset;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.api.VibrisRuntimeAdapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

final class RuntimeTestAdapter implements VibrisRuntimeAdapter {
    final List<String> events = new ArrayList<>();
    final ArrayDeque<ReloadResult> reloads = new ArrayDeque<>();
    final ArrayDeque<CompletionStage<ReloadResult>> reloadStages = new ArrayDeque<>();
    final ArrayDeque<CompileCatalog> compileCatalogs = new ArrayDeque<>();
    final ArrayDeque<String> actionResponses = new ArrayDeque<>();
    final ArrayDeque<CompletionStage<String>> actionStages = new ArrayDeque<>();
    final ArrayDeque<Function<CancellationToken, CompletionStage<Long>>> waitOperations = new ArrayDeque<>();
    final ArrayDeque<DeterministicCaptureOperation> deterministicCaptureOperations = new ArrayDeque<>();
    final ArrayDeque<CompletionStage<DeterministicTemporalCaptureOutcome>> deterministicCaptureStages =
        new ArrayDeque<>();
    final ArrayDeque<DeterministicTemporalCaptureOutcome> deterministicCaptureOutcomes = new ArrayDeque<>();
    final ArrayDeque<ResourceCatalog> deterministicResourceCatalogs = new ArrayDeque<>();
    final ArrayDeque<CompileCatalog> deterministicCompileCatalogs = new ArrayDeque<>();
    final ArrayDeque<Long> deterministicReloadCompletedAtUnixMs = new ArrayDeque<>();
    final ArrayDeque<RuntimeException> deterministicCaptureFailures = new ArrayDeque<>();
    final ArrayDeque<RuntimeException> deterministicCaptureFailuresAfterWrite = new ArrayDeque<>();
    final ArrayDeque<RuntimeException> captureFailures = new ArrayDeque<>();
    final ArrayDeque<RuntimeException> captureFailuresAfterWrite = new ArrayDeque<>();
    final ArrayDeque<Map<String, byte[]>> captureFileBatches = new ArrayDeque<>();
    RuntimeStatus status = new RuntimeStatus(true, "save", "minecraft:overworld", "");
    CompletionStage<RuntimeStatus> statusStage;
    int statusCalls;
    TemporalResetResult reset = new TemporalResetResult(true);
    ResourceCatalog catalog = ResourceCatalog.empty();
    CaptureResult captureResult = new CaptureResult(0, List.of());
    CaptureResult deterministicCaptureResult;
    CompileCatalog compileCatalog = CompileCatalog.empty(0);
    final Map<String, byte[]> captureFiles = new LinkedHashMap<>();
    final Map<String, byte[]> deterministicCaptureFiles = new LinkedHashMap<>();
    final ArrayDeque<Map<String, byte[]>> deterministicCaptureFileBatches = new ArrayDeque<>();
    CaptureResult patchedShaderResult = new CaptureResult(0, List.of());
    final Map<String, byte[]> patchedShaderFiles = new LinkedHashMap<>();
    AfterPassOperation afterPassOperation;
    Map<String, String> lastShaderConfig;
    final List<Map<String, String>> shaderConfigs = new ArrayList<>();
    SceneContext lastContext;
    final List<SceneContext> contexts = new ArrayList<>();
    List<ScenePreset> presets = List.of();
    Runnable beforeReloadResult = () -> {};
    Runnable beforeCompileCatalogResult = () -> {};
    RuntimeException closeFailure;
    int closeCount;
    int deterministicCaptureCalls;
    int deterministicPlannerCalls;
    long deterministicFrame;
    volatile boolean deterministicPhaseActive;
    DeterministicTemporalCaptureRequest lastDeterministicCaptureRequest;
    final List<ResourceCatalog> deterministicPlannerCatalogs = new ArrayList<>();
    final List<CapturePlan> deterministicPlans = new ArrayList<>();

    RuntimeEnvironment environment = new RuntimeEnvironment(
        "test-minecraft", "test-iris", "test-vibris", "test-java", "test-os",
        "test-gpu-vendor", "test-gpu-renderer", "test-opengl", "test-driver"
    );

    @Override
    public CompletionStage<RuntimeEnvironment> getRuntimeEnvironment() {
        return CompletableFuture.completedFuture(environment);
    }

    @Override
    public CompletionStage<RuntimeStatus> getStatus() {
        statusCalls++;
        return statusStage == null ? CompletableFuture.completedFuture(status) : statusStage;
    }

    @Override
    public CompletionStage<List<ScenePreset>> listPresets() {
        return CompletableFuture.completedFuture(presets);
    }

    @Override
    public CompletionStage<ContextApplyResult> ensureWorldAndContext(
        SceneContext context,
        CancellationToken cancellation
    ) {
        events.add("context");
        lastContext = context;
        contexts.add(context);
        return completed(cancellation, ContextApplyResult.success(context));
    }

    @Override
    public CompletionStage<ReloadResult> reloadVibrisShaderpack(
        Map<String, String> config, CancellationToken cancellation
    ) {
        events.add("reload");
        lastShaderConfig = config;
        shaderConfigs.add(config == null ? null : Map.copyOf(config));
        beforeReloadResult.run();
        if (!reloadStages.isEmpty()) return reloadStages.removeFirst();
        ReloadResult result = reloads.isEmpty()
            ? ReloadResult.success(EffectiveShaderSettings.empty(), List.of())
            : reloads.removeFirst();
        return completed(cancellation, result);
    }

    @Override
    public CompletionStage<TemporalResetResult> resetTemporalState(CancellationToken cancellation) {
        events.add("reset");
        return completed(cancellation, reset);
    }

    @Override
    public CompletionStage<CompileCatalog> getCompileCatalog(CancellationToken cancellation) {
        events.add("compile_catalog");
        beforeCompileCatalogResult.run();
        return CompletableFuture.completedFuture(
            compileCatalogs.isEmpty() ? compileCatalog : compileCatalogs.removeFirst());
    }

    @Override
    public CompletionStage<Long> waitRenderedFrames(int frameCount, CancellationToken cancellation) {
        events.add("frames");
        if (!waitOperations.isEmpty()) return waitOperations.removeFirst().apply(cancellation);
        return completed(cancellation, (long) frameCount);
    }

    @Override
    public ResourceCatalog getResourceCatalog() {
        return catalog;
    }

    @Override
    public CompletionStage<String> executeAction(RuntimeAction action) {
        events.add("action:" + action.getClass().getSimpleName());
        if (!actionStages.isEmpty()) return actionStages.removeFirst();
        String response = actionResponses.isEmpty() ? "{}" : actionResponses.removeFirst();
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletionStage<CaptureResult> capture(
        CapturePlan plan,
        ArtifactSink sink,
        CancellationToken cancellation
    ) {
        events.add("capture");
        if (!captureFailures.isEmpty()) {
            return CompletableFuture.failedFuture(captureFailures.removeFirst());
        }
        try {
            cancellation.throwIfCancellationRequested();
            Map<String, byte[]> files = captureFileBatches.isEmpty() ? captureFiles : captureFileBatches.removeFirst();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                try (var output = sink.open(file.getKey())) {
                    output.write(file.getValue());
                }
            }
            if (!captureFailuresAfterWrite.isEmpty()) {
                return CompletableFuture.failedFuture(captureFailuresAfterWrite.removeFirst());
            }
            return CompletableFuture.completedFuture(captureResult);
        } catch (java.io.IOException | RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletionStage<DeterministicTemporalCaptureOutcome> captureDeterministicTemporalPhase(
        DeterministicTemporalCaptureRequest request,
        DeterministicTemporalCapturePlanner planner,
        ArtifactSink sink,
        CancellationToken cancellation
    ) {
        events.add("deterministic_capture");
        deterministicCaptureCalls++;
        lastDeterministicCaptureRequest = request;
        lastContext = request.context();
        contexts.add(request.context());
        lastShaderConfig = request.preserveCurrentSettings() ? null : request.settings();
        shaderConfigs.add(lastShaderConfig == null ? null : Map.copyOf(lastShaderConfig));
        try {
            cancellation.throwIfCancellationRequested();
            deterministicPhaseActive = true;
            CompletionStage<DeterministicTemporalCaptureOutcome> stage;
            if (!deterministicCaptureOperations.isEmpty()) {
                stage = deterministicCaptureOperations.removeFirst().capture(request, planner, sink, cancellation);
            } else if (!deterministicCaptureStages.isEmpty()) {
                stage = deterministicCaptureStages.removeFirst();
            } else if (!deterministicCaptureFailures.isEmpty()) {
                stage = CompletableFuture.failedFuture(deterministicCaptureFailures.removeFirst());
            } else {
                DeterministicTemporalCaptureOutcome outcome = deterministicCaptureOutcomes.isEmpty()
                    ? defaultDeterministicOutcome(request, planner)
                    : prepareInjectedOutcome(deterministicCaptureOutcomes.removeFirst(), planner);
                if (outcome instanceof DeterministicTemporalCaptureOutcome.Captured captured) {
                    writeDeterministicCaptureFiles(captured.plan(), sink, cancellation);
                    if (!deterministicCaptureFailuresAfterWrite.isEmpty()) {
                        stage = CompletableFuture.failedFuture(deterministicCaptureFailuresAfterWrite.removeFirst());
                    } else {
                        stage = CompletableFuture.completedFuture(outcome);
                    }
                } else {
                    stage = CompletableFuture.completedFuture(outcome);
                }
            }
            return stage.whenComplete((ignored, failure) -> deterministicPhaseActive = false);
        } catch (java.io.IOException | RuntimeException exception) {
            deterministicPhaseActive = false;
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletionStage<CapturePlan.AfterPassReceipt> captureAfterPass(
        CapturePlan.AfterPassRequest request,
        ArtifactSink sink,
        CancellationToken cancellation
    ) {
        events.add("capture_after_pass:" + request.target().artifactName());
        if (afterPassOperation == null) {
            return CompletableFuture.failedFuture(
                new UnsupportedOperationException("after-pass capture is not configured"));
        }
        return afterPassOperation.capture(request, sink, cancellation);
    }

    @Override
    public CompletionStage<CaptureResult> capturePatchedShaders(
        String artifactName,
        ArtifactSink sink,
        CancellationToken cancellation
    ) {
        events.add("capture_patched_shaders");
        try {
            cancellation.throwIfCancellationRequested();
            for (Map.Entry<String, byte[]> file : patchedShaderFiles.entrySet()) {
                try (var output = sink.open(file.getKey())) {
                    output.write(file.getValue());
                }
            }
            return CompletableFuture.completedFuture(patchedShaderResult);
        } catch (java.io.IOException | RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public void close() {
        events.add("close");
        closeCount++;
        if (closeFailure != null) throw closeFailure;
    }

    private static <T> CompletionStage<T> completed(CancellationToken cancellation, T value) {
        cancellation.throwIfCancellationRequested();
        return CompletableFuture.completedFuture(value);
    }

    private DeterministicTemporalCaptureOutcome defaultDeterministicOutcome(
        DeterministicTemporalCaptureRequest request,
        DeterministicTemporalCapturePlanner planner
    ) {
        ContextApplyResult context = ContextApplyResult.success(request.context());
        ReloadResult reload = reloads.isEmpty()
            ? ReloadResult.success(EffectiveShaderSettings.empty(), List.of())
            : reloads.removeFirst();
        if (!reload.successful()) {
            return new DeterministicTemporalCaptureOutcome.ReloadRejected(
                context,
                reload,
                failure(DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED, "reload rejected")
            );
        }
        ResourceCatalog phaseCatalog = deterministicResourceCatalogs.isEmpty()
            ? catalog
            : deterministicResourceCatalogs.removeFirst();
        CompileCatalog phaseCompileCatalog = deterministicCompileCatalogs.isEmpty()
            ? compileCatalog
            : deterministicCompileCatalogs.removeFirst();
        long reloadedAt = deterministicReloadCompletedAtUnixMs.isEmpty()
            ? Math.max(1L, System.currentTimeMillis())
            : deterministicReloadCompletedAtUnixMs.removeFirst();
        DeterministicTemporalCaptureReloaded reloaded = new DeterministicTemporalCaptureReloaded(
            context,
            reload,
            reloadedAt,
            phaseCatalog,
            phaseCompileCatalog
        );
        deterministicPlannerCalls++;
        deterministicPlannerCatalogs.add(phaseCatalog);
        DeterministicTemporalCapturePlanning planning = planner.plan(phaseCatalog, phaseCompileCatalog);
        if (planning instanceof DeterministicTemporalCapturePlanning.Rejected rejected) {
            return new DeterministicTemporalCaptureOutcome.PlanningRejected(reloaded, rejected.failure());
        }
        CapturePlan plan = ((DeterministicTemporalCapturePlanning.Planned) planning).plan();
        deterministicPlans.add(plan);
        if (!reset.successful()) {
            return new DeterministicTemporalCaptureOutcome.ResetRejected(
                reloaded,
                plan,
                reset,
                failure(DeterministicTemporalCaptureOutcome.FailureKind.OPERATION_FAILED, "reset rejected")
            );
        }
        long anchor = deterministicFrame;
        long warmupEnd = Math.addExact(anchor, request.warmupFrames());
        long captureFrame = Math.addExact(warmupEnd, 1L);
        CaptureResult captured = deterministicCaptureResult == null
            ? deterministicCaptureResult(plan, captureFrame)
            : deterministicCaptureResult;
        deterministicFrame = captureFrame;
        return new DeterministicTemporalCaptureOutcome.Captured(
            reloaded,
            plan,
            reset,
            Math.max(1L, System.currentTimeMillis()),
            request.warmupFrames(),
            anchor,
            warmupEnd,
            captured
        );
    }

    private DeterministicTemporalCaptureOutcome prepareInjectedOutcome(
        DeterministicTemporalCaptureOutcome outcome,
        DeterministicTemporalCapturePlanner planner
    ) {
        DeterministicTemporalCaptureReloaded reloaded;
        CapturePlan expected;
        if (outcome instanceof DeterministicTemporalCaptureOutcome.ContextRejected ||
            outcome instanceof DeterministicTemporalCaptureOutcome.ReloadRejected) {
            return outcome;
        } else if (outcome instanceof DeterministicTemporalCaptureOutcome.PlanningRejected rejected) {
            reloaded = rejected.reloaded();
            expected = null;
        } else if (outcome instanceof DeterministicTemporalCaptureOutcome.ResetRejected rejected) {
            reloaded = rejected.reloaded();
            expected = rejected.plan();
        } else if (outcome instanceof DeterministicTemporalCaptureOutcome.WarmupRejected rejected) {
            reloaded = rejected.reloaded();
            expected = rejected.plan();
        } else if (outcome instanceof DeterministicTemporalCaptureOutcome.CaptureRejected rejected) {
            reloaded = rejected.reloaded();
            expected = rejected.plan();
        } else {
            DeterministicTemporalCaptureOutcome.Captured captured =
                (DeterministicTemporalCaptureOutcome.Captured) outcome;
            reloaded = captured.reloaded();
            expected = captured.plan();
        }
        deterministicPlannerCalls++;
        deterministicPlannerCatalogs.add(reloaded.resourceCatalog());
        DeterministicTemporalCapturePlanning planning = planner.plan(
            reloaded.resourceCatalog(),
            reloaded.compileCatalog()
        );
        if (planning instanceof DeterministicTemporalCapturePlanning.Rejected rejected) {
            return new DeterministicTemporalCaptureOutcome.PlanningRejected(reloaded, rejected.failure());
        }
        if (outcome instanceof DeterministicTemporalCaptureOutcome.PlanningRejected) {
            if (planning instanceof DeterministicTemporalCapturePlanning.Planned) {
                throw new IllegalStateException("Injected planning rejection did not match the planner result.");
            }
            return outcome;
        }
        CapturePlan actual = ((DeterministicTemporalCapturePlanning.Planned) planning).plan();
        if (!actual.equals(expected)) {
            throw new IllegalStateException("Injected deterministic plan did not match the planner result.");
        }
        deterministicPlans.add(actual);
        return outcome;
    }

    private static DeterministicTemporalCaptureOutcome.Failure failure(
        DeterministicTemporalCaptureOutcome.FailureKind kind,
        String message
    ) {
        return new DeterministicTemporalCaptureOutcome.Failure(kind, message);
    }

    private CaptureResult deterministicCaptureResult(CapturePlan plan, long frameId) {
        List<CaptureResult.ArtifactGroup> groups = plan.targets().stream().map(target -> {
            List<CapturePlan.ArtifactOutputSpec> outputs = target.outputs();
            List<CaptureResult.CapturedArtifact> artifacts = outputs.stream()
                .map(output -> new CaptureResult.CapturedArtifact(
                    output.fileName(),
                    output.format(),
                    output.role(),
                    output.subresourceIndex()
                ))
                .toList();
            boolean texture = target.resource().kind() == ResourceCatalog.ResourceKind.TEXTURE;
            ResourceCatalog.ResourceDescriptor resource = ResourceCatalog.ResourceDescriptor.of(
                target.resource().logicalName(),
                target.resource().kind(),
                texture ? List.of(target.resource().textureView()) : List.of(),
                0,
                0,
                0,
                texture ? 1 : 0,
                texture ? 1 : 0,
                "fake",
                0,
                ResourceCatalog.ScalarType.UNSPECIFIED,
                0,
                frameId,
                target.resource().logicalName(),
                "fake",
                "",
                "",
                "",
                0,
                "",
                ""
            );
            return new CaptureResult.ArtifactGroup(target.artifactName(), resource, artifacts);
        }).toList();
        return new CaptureResult(frameId, groups);
    }

    private void writeDeterministicCaptureFiles(
        CapturePlan plan,
        ArtifactSink sink,
        CancellationToken cancellation
    )
        throws java.io.IOException {
        Map<String, byte[]> files;
        if (!deterministicCaptureFileBatches.isEmpty()) {
            files = deterministicCaptureFileBatches.removeFirst();
        } else if (!deterministicCaptureFiles.isEmpty()) {
            files = deterministicCaptureFiles;
        } else {
            files = new LinkedHashMap<>();
            for (CapturePlan.Target target : plan.targets()) {
                List<CapturePlan.ArtifactOutputSpec> outputs = target.outputs();
                for (CapturePlan.ArtifactOutputSpec output : outputs) {
                    files.put(output.fileName(), ("fake:" + target.resource().logicalName() + "\n").getBytes(
                        java.nio.charset.StandardCharsets.UTF_8
                    ));
                }
            }
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            cancellation.throwIfCancellationRequested();
            try (var output = sink.open(file.getKey())) {
                output.write(file.getValue());
            }
        }
    }

    @FunctionalInterface
    interface AfterPassOperation {
        CompletionStage<CapturePlan.AfterPassReceipt> capture(
            CapturePlan.AfterPassRequest request,
            ArtifactSink sink,
            CancellationToken cancellation
        );
    }

    @FunctionalInterface
    interface DeterministicCaptureOperation {
        CompletionStage<DeterministicTemporalCaptureOutcome> capture(
            DeterministicTemporalCaptureRequest request,
            DeterministicTemporalCapturePlanner planner,
            ArtifactSink sink,
            CancellationToken cancellation
        );
    }
}