package dev.vibris.core

import dev.vibris.api.RuntimeAction
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ActionKind

internal object RuntimeActionProtocol {
    fun isRuntime(action: Action): Boolean = when (action.actionCase) {
        Action.ActionCase.GET_CAPTURE_STATUS,
        Action.ActionCase.CAPTURE_PASS,
        Action.ActionCase.CAPTURE_MULTI,
        Action.ActionCase.GET_GPU_METRICS,
        -> true
        else -> false
    }

    fun toApi(action: Action): RuntimeAction = when (action.actionCase) {
        Action.ActionCase.GET_CAPTURE_STATUS -> RuntimeAction.CaptureStatus
        Action.ActionCase.CAPTURE_PASS -> action.capturePass.let { command ->
            RuntimeAction.CapturePass(
                command.passId.requireText("pass_id"),
                command.artifactName.takeIf(String::isNotBlank),
            )
        }
        Action.ActionCase.CAPTURE_MULTI -> action.captureMulti.let { command ->
            require(command.captureType in captureTypes) { "capture_type is unsupported" }
            RuntimeAction.CaptureMulti(
                command.captureType,
                command.artifactName.takeIf(String::isNotBlank),
            )
        }
        Action.ActionCase.GET_GPU_METRICS ->
            RuntimeAction.GpuMetrics(action.getGpuMetrics.frames.requireRange("frames", 1, 10_000))
        else -> throw IllegalArgumentException("Action is not a runtime action")
    }

    fun kind(action: Action): ActionKind = when (action.actionCase) {
        Action.ActionCase.RESET_TEMPORAL_STATE -> ActionKind.ACTION_KIND_RESET_TEMPORAL_STATE
        Action.ActionCase.WAIT_FRAMES -> ActionKind.ACTION_KIND_WAIT_FRAMES
        Action.ActionCase.TAKE_SCREENSHOT -> ActionKind.ACTION_KIND_TAKE_SCREENSHOT
        Action.ActionCase.ACTIVATE_SOURCE -> ActionKind.ACTION_KIND_ACTIVATE_SOURCE
        Action.ActionCase.COMPARE_CAPTURES -> ActionKind.ACTION_KIND_COMPARE_CAPTURES
        Action.ActionCase.GET_CAPTURE_STATUS -> ActionKind.ACTION_KIND_GET_CAPTURE_STATUS
        Action.ActionCase.CAPTURE_PASS -> ActionKind.ACTION_KIND_CAPTURE_PASS
        Action.ActionCase.CAPTURE_MULTI -> ActionKind.ACTION_KIND_CAPTURE_MULTI
        Action.ActionCase.INSPECT_SHADER -> ActionKind.ACTION_KIND_INSPECT_SHADER
        Action.ActionCase.GET_GPU_METRICS -> ActionKind.ACTION_KIND_GET_GPU_METRICS
        Action.ActionCase.LOAD_SHADER -> ActionKind.ACTION_KIND_LOAD_SHADER
        Action.ActionCase.LIST_RESOURCES -> ActionKind.ACTION_KIND_LIST_RESOURCES
        Action.ActionCase.DUMP_TEXTURE -> ActionKind.ACTION_KIND_DUMP_TEXTURE
        Action.ActionCase.DUMP_BUFFER -> ActionKind.ACTION_KIND_DUMP_BUFFER
        Action.ActionCase.GET_PATCHED_SHADERS -> ActionKind.ACTION_KIND_GET_PATCHED_SHADERS
        Action.ActionCase.DUMP_TEXTURE_AFTER_PASS -> ActionKind.ACTION_KIND_DUMP_TEXTURE_AFTER_PASS
        Action.ActionCase.DUMP_BUFFER_AFTER_PASS -> ActionKind.ACTION_KIND_DUMP_BUFFER_AFTER_PASS
        else -> throw IllegalArgumentException("Action kind is unsupported")
    }

    private fun String.requireText(field: String): String = apply {
        require(isNotBlank()) { "$field must not be blank" }
    }

    private fun Int.requireRange(field: String, minimum: Int, maximum: Int): Int = apply {
        require(this in minimum..maximum) { "$field must be between $minimum and $maximum" }
    }

    private val captureTypes = setOf("prepare", "begin", "deferred", "composite", "final", "shadow_composite")
}
