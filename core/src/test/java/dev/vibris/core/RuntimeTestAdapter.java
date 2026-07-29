package dev.vibris.core;

import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.RuntimeStatus;
import dev.vibris.api.SceneContext;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.api.VibrisRuntimeAdapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class RuntimeTestAdapter implements VibrisRuntimeAdapter {
    final List<String> events = new ArrayList<>();
    final ArrayDeque<ReloadResult> reloads = new ArrayDeque<>();
    RuntimeStatus status = new RuntimeStatus(true, "save", "minecraft:overworld", "");
    TemporalResetResult reset = new TemporalResetResult(true);
    Runnable beforeReloadResult = () -> {};
    RuntimeException closeFailure;
    int closeCount;

    @Override
    public CompletionStage<RuntimeStatus> getStatus() {
        return CompletableFuture.completedFuture(status);
    }

    @Override
    public CompletionStage<ContextApplyResult> ensureWorldAndContext(
        SceneContext context,
        CancellationToken cancellation
    ) {
        events.add("context");
        return completed(cancellation, ContextApplyResult.success(context));
    }

    @Override
    public CompletionStage<ReloadResult> reloadVibrisShaderpack(CancellationToken cancellation) {
        events.add("reload");
        beforeReloadResult.run();
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
        return ResourceCatalog.empty();
    }

    @Override
    public CompletionStage<CaptureResult> capture(
        CapturePlan plan,
        ArtifactSink sink,
        CancellationToken cancellation
    ) {
        events.add("capture");
        return completed(cancellation, new CaptureResult(0, Map.of()));
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