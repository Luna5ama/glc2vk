package dev.vibris.api

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

interface VibrisRuntimeAdapter : AutoCloseable {
    fun listPresets(): CompletionStage<List<ScenePreset>> {
        val context = SceneContext(
            "test-save",
            "minecraft:overworld",
            "noon",
            "clear",
            "origin",
            70.0,
            SceneContext.Resolution(640, 360),
            "default",
        )
        return CompletableFuture.completedFuture(listOf(ScenePreset("default", "Default", context)))
    }

    fun validateContext(context: SceneContext): CompletionStage<ContextValidationResult> =
        CompletableFuture.completedFuture(ContextValidationResult.accepted())

    fun getStatus(): CompletionStage<RuntimeStatus>

    fun debugControl(command: DebugControlCommand): CompletionStage<String> =
        CompletableFuture.failedFuture(UnsupportedOperationException("Debug control is unavailable"))

    fun ensureWorldAndContext(
        context: SceneContext,
        cancellation: CancellationToken,
    ): CompletionStage<ContextApplyResult>

    fun reloadVibrisShaderpack(
        config: Map<String, String>?,
        cancellation: CancellationToken,
    ): CompletionStage<ReloadResult>

    fun resetTemporalState(cancellation: CancellationToken): CompletionStage<TemporalResetResult>

    fun waitRenderedFrames(frameCount: Int, cancellation: CancellationToken): CompletionStage<Long>

    fun getResourceCatalog(): ResourceCatalog

    fun capture(
        plan: CapturePlan,
        sink: ArtifactSink,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult>

    override fun close()
}
