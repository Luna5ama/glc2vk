package dev.vibris.core

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ContextApplyResult
import dev.vibris.api.ContextValidationResult
import dev.vibris.api.DebugControlCommand
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.api.RuntimeStatus
import dev.vibris.api.SceneContext
import dev.vibris.api.ScenePreset
import dev.vibris.api.TemporalResetResult
import java.util.concurrent.CompletionStage

interface VibrisRuntimeHost : AutoCloseable {
    fun isClientThread(): Boolean

    fun executeOnClient(task: Runnable)

    fun status(): RuntimeStatus

    fun debugControl(command: DebugControlCommand): String =
        throw UnsupportedOperationException("Debug control is unavailable")

    fun presets(): List<ScenePreset> = java.util.List.of()

    fun validateContext(context: SceneContext): ContextValidationResult = ContextValidationResult.accepted()

    fun applyContext(context: SceneContext, cancellation: CancellationToken): CompletionStage<ContextApplyResult>

    fun reload(cancellation: CancellationToken): ReloadResult

    fun resetTemporal(cancellation: CancellationToken): TemporalResetResult

    fun resourceCatalog(frameId: Long): ResourceCatalog

    fun capture(
        plan: CapturePlan,
        sink: ArtifactSink,
        frameId: Long,
        cancellation: CancellationToken,
    ): CaptureResult

    override fun close()
}
