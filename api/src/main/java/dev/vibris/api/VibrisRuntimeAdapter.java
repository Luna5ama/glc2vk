package dev.vibris.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface VibrisRuntimeAdapter extends AutoCloseable {
    default CompletionStage<List<ScenePreset>> listPresets() {
        SceneContext context = new SceneContext("test-save", "minecraft:overworld", "noon", "clear", "origin",
            70.0, new SceneContext.Resolution(640, 360), "default");
        return CompletableFuture.completedFuture(List.of(new ScenePreset("default", "Default", context)));
    }

    default CompletionStage<ContextValidationResult> validateContext(SceneContext context) {
        return CompletableFuture.completedFuture(ContextValidationResult.accepted());
    }

    CompletionStage<RuntimeStatus> getStatus();

    CompletionStage<ContextApplyResult> ensureWorldAndContext(
        SceneContext context,
        CancellationToken cancellation
    );

    CompletionStage<ReloadResult> reloadVibrisShaderpack(CancellationToken cancellation);

    CompletionStage<TemporalResetResult> resetTemporalState(CancellationToken cancellation);

    CompletionStage<Long> waitRenderedFrames(int frameCount, CancellationToken cancellation);

    ResourceCatalog getResourceCatalog();

    CompletionStage<CaptureResult> capture(
        CapturePlan plan,
        ArtifactSink sink,
        CancellationToken cancellation
    );

    @Override
    void close();
}