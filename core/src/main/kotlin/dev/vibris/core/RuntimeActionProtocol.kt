package dev.vibris.core

import dev.vibris.api.RuntimeAction
import dev.vibris.protocol.v1.Action
import dev.vibris.protocol.v1.CaptureMulti
import dev.vibris.protocol.v1.CapturePass
import dev.vibris.protocol.v1.JobActionKind

internal object RuntimeActionProtocol {
    fun isRuntime(action: Action): Boolean = when (action.actionCase) {
        Action.ActionCase.GET_CAPTURE_STATUS,
        Action.ActionCase.CAPTURE_PASS,
        Action.ActionCase.CAPTURE_MULTI,
        Action.ActionCase.INSPECT_SHADER,
        Action.ActionCase.GET_GPU_METRICS,
        Action.ActionCase.LIST_TEXTURES_V2,
        Action.ActionCase.LIST_BUFFERS,
        -> true
        else -> false
    }

    fun toApi(action: Action): RuntimeAction = when (action.actionCase) {
        Action.ActionCase.GET_CAPTURE_STATUS -> RuntimeAction.CaptureStatus
        Action.ActionCase.CAPTURE_PASS -> action.capturePass.let { command ->
            RuntimeAction.CapturePass(
                command.pass.requireText("pass"),
                command.pathOrNull(),
            )
        }
        Action.ActionCase.CAPTURE_MULTI -> action.captureMulti.let { command ->
            require(command.type in captureTypes) { "type is unsupported" }
            RuntimeAction.CaptureMulti(command.type, command.pathOrNull())
        }
        Action.ActionCase.INSPECT_SHADER -> RuntimeAction.InspectShader
        Action.ActionCase.GET_GPU_METRICS ->
            RuntimeAction.GpuMetrics(action.getGpuMetrics.frames.requireRange("frames", 1, 10_000))
        Action.ActionCase.LIST_TEXTURES_V2 -> RuntimeAction.ListTextures
        Action.ActionCase.LIST_BUFFERS -> RuntimeAction.ListBuffers
        else -> throw IllegalArgumentException("Action is not a runtime action")
    }

    fun kind(action: Action): JobActionKind = when (action.actionCase) {
        Action.ActionCase.RESET_TEMPORAL_STATE -> JobActionKind.JOB_ACTION_KIND_RESET_TEMPORAL_STATE
        Action.ActionCase.WAIT_FRAMES -> JobActionKind.JOB_ACTION_KIND_WAIT_FRAMES
        Action.ActionCase.TAKE_SCREENSHOT -> JobActionKind.JOB_ACTION_KIND_TAKE_SCREENSHOT
        Action.ActionCase.ACTIVATE_SOURCE -> JobActionKind.JOB_ACTION_KIND_ACTIVATE_SOURCE
        Action.ActionCase.COMPARE_CAPTURES -> JobActionKind.JOB_ACTION_KIND_COMPARE_CAPTURES
        Action.ActionCase.GET_CAPTURE_STATUS -> JobActionKind.JOB_ACTION_KIND_GET_CAPTURE_STATUS
        Action.ActionCase.CAPTURE_PASS -> JobActionKind.JOB_ACTION_KIND_CAPTURE_PASS
        Action.ActionCase.CAPTURE_MULTI -> JobActionKind.JOB_ACTION_KIND_CAPTURE_MULTI
        Action.ActionCase.INSPECT_SHADER -> JobActionKind.JOB_ACTION_KIND_INSPECT_SHADER
        Action.ActionCase.GET_GPU_METRICS -> JobActionKind.JOB_ACTION_KIND_GET_GPU_METRICS
        Action.ActionCase.LIST_TEXTURES_V2 -> JobActionKind.JOB_ACTION_KIND_LIST_TEXTURES_V2
        Action.ActionCase.DUMP_TEXTURE_V2 -> JobActionKind.JOB_ACTION_KIND_DUMP_TEXTURE_V2
        Action.ActionCase.LIST_BUFFERS -> JobActionKind.JOB_ACTION_KIND_LIST_BUFFERS
        Action.ActionCase.DUMP_BUFFER -> JobActionKind.JOB_ACTION_KIND_DUMP_BUFFER
        Action.ActionCase.GET_PATCHED_SHADERS -> JobActionKind.JOB_ACTION_KIND_GET_PATCHED_SHADERS
        Action.ActionCase.LOAD_SHADER -> JobActionKind.JOB_ACTION_KIND_LOAD_SHADER
        else -> throw IllegalArgumentException("Action kind is unsupported")
    }

    private fun CapturePass.pathOrNull(): String? =
        if (hasPath()) path.requireText("path") else null

    private fun CaptureMulti.pathOrNull(): String? =
        if (hasPath()) path.requireText("path") else null

    private fun String.requireText(field: String): String = apply {
        require(isNotBlank()) { "$field must not be blank" }
    }

    private fun Int.requirePositive(field: String): Int = apply {
        require(this > 0) { "$field must be positive" }
    }

    private fun Int.requireRange(field: String, minimum: Int, maximum: Int): Int = apply {
        require(this in minimum..maximum) { "$field must be between $minimum and $maximum" }
    }

    private val captureTypes = setOf("prepare", "begin", "deferred", "composite")
}
