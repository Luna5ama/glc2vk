package dev.vibris.core;

import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.RuntimeStatus;
import dev.vibris.api.RuntimeAction;
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
import java.util.function.Consumer;

final class RuntimeTestAdapter implements VibrisRuntimeAdapter {
    final List<String> events = new ArrayList<>();
    final ArrayDeque<ReloadResult> reloads = new ArrayDeque<>();
    final ArrayDeque<CompletionStage<ReloadResult>> reloadStages = new ArrayDeque<>();
    final ArrayDeque<String> actionResponses = new ArrayDeque<>();
    final ArrayDeque<CompletionStage<String>> actionStages = new ArrayDeque<>();
    final ArrayDeque<RuntimeException> captureFailures = new ArrayDeque<>();
    final ArrayDeque<RuntimeException> captureFailuresAfterWrite = new ArrayDeque<>();
    final ArrayDeque<Map<String, byte[]>> captureFileBatches = new ArrayDeque<>();
    RuntimeStatus status = new RuntimeStatus(true, "save", "minecraft:overworld", "");
    CompletionStage<RuntimeStatus> statusStage;
    int statusCalls;
    TemporalResetResult reset = new TemporalResetResult(true);
    ResourceCatalog catalog = ResourceCatalog.empty();
    CaptureResult captureResult = new CaptureResult(0, List.of());
    final Map<String, byte[]> captureFiles = new LinkedHashMap<>();
    CaptureResult patchedShaderResult = new CaptureResult(0, List.of());
    final Map<String, byte[]> patchedShaderFiles = new LinkedHashMap<>();
    Map<String, String> lastShaderConfig;
    final List<Map<String, String>> shaderConfigs = new ArrayList<>();
    SceneContext lastContext;
    final List<SceneContext> contexts = new ArrayList<>();
    List<ScenePreset> presets = List.of();
    Runnable beforeReloadResult = () -> {};
    Consumer<RuntimeAction> actionObserver = ignored -> {};
    RuntimeException closeFailure;
    int closeCount;

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
        ReloadResult result = reloads.isEmpty() ? ReloadResult.success(List.of()) : reloads.removeFirst();
        return completed(cancellation, result);
    }

    @Override
    public CompletionStage<TemporalResetResult> resetTemporalState(CancellationToken cancellation) {
        events.add("reset");
        return completed(cancellation, reset);
    }

    @Override
    public CompletionStage<Long> waitRenderedFrames(int frameCount, CancellationToken cancellation) {
        events.add("frames");
        return completed(cancellation, (long) frameCount);
    }

    @Override
    public ResourceCatalog getResourceCatalog() {
        return catalog;
    }

    @Override
    public CompletionStage<String> executeAction(RuntimeAction action) {
        events.add("action:" + action.getClass().getSimpleName());
        actionObserver.accept(action);
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
}