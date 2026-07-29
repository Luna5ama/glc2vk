package dev.vibris.api;

import java.util.concurrent.CompletionStage;

public interface VibrisRuntimeAdapter extends AutoCloseable {
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