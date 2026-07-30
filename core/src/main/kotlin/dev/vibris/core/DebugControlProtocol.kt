package dev.vibris.core

import dev.vibris.api.DebugControlCommand
import dev.vibris.protocol.v1.DebugCaptureMulti
import dev.vibris.protocol.v1.DebugCapturePass
import dev.vibris.protocol.v1.DebugControlRequest
import dev.vibris.protocol.v1.DebugDumpTexture

internal object DebugControlProtocol {
    fun toApi(request: DebugControlRequest): DebugControlCommand = when (request.commandCase) {
        DebugControlRequest.CommandCase.CAPTURE_STATUS -> DebugControlCommand.CaptureStatus
        DebugControlRequest.CommandCase.RELOAD_SHADER -> DebugControlCommand.ReloadShader(
            if (request.reloadShader.hasConfig()) request.reloadShader.config.valuesMap else null,
        )
        DebugControlRequest.CommandCase.CAPTURE_PASS -> request.capturePass.let { command ->
            DebugControlCommand.CapturePass(
                command.pass.requireText("pass"),
                command.pathOrNull(),
            )
        }
        DebugControlRequest.CommandCase.CAPTURE_MULTI -> request.captureMulti.let { command ->
            require(command.type in captureTypes) { "type is unsupported" }
            DebugControlCommand.CaptureMulti(command.type, command.pathOrNull())
        }
        DebugControlRequest.CommandCase.SHADER_STATUS -> DebugControlCommand.ShaderStatus
        DebugControlRequest.CommandCase.SHADER_ERRORS -> DebugControlCommand.ShaderErrors
        DebugControlRequest.CommandCase.SCHEDULE_SCREENSHOT ->
            DebugControlCommand.ScheduleScreenshot(request.scheduleScreenshot.frames.requirePositive("frames"))
        DebugControlRequest.CommandCase.SCREENSHOT_RESULT -> DebugControlCommand.ScreenshotResult
        DebugControlRequest.CommandCase.GPU_METRICS ->
            DebugControlCommand.GpuMetrics(request.gpuMetrics.frames.requireRange("frames", 1, 10_000))
        DebugControlRequest.CommandCase.LIST_SSBOS -> DebugControlCommand.ListSsbos
        DebugControlRequest.CommandCase.DUMP_SSBO -> DebugControlCommand.DumpSsbo(request.dumpSsbo.index)
        DebugControlRequest.CommandCase.LIST_TEXTURES -> DebugControlCommand.ListTextures
        DebugControlRequest.CommandCase.DUMP_TEXTURE -> request.dumpTexture.let { command ->
            when (command.selectorCase) {
                DebugDumpTexture.SelectorCase.NAME ->
                    DebugControlCommand.DumpTexture(command.name.requireText("name"), null, command.raw)
                DebugDumpTexture.SelectorCase.ID ->
                    DebugControlCommand.DumpTexture(null, command.id, command.raw)
                DebugDumpTexture.SelectorCase.SELECTOR_NOT_SET ->
                    throw IllegalArgumentException("texture selector is required")
            }
        }
        DebugControlRequest.CommandCase.LIST_PATCHED_SHADERS -> DebugControlCommand.ListPatchedShaders
        DebugControlRequest.CommandCase.COMMAND_NOT_SET -> throw IllegalArgumentException("Debug command is required")
    }

    private fun DebugCapturePass.pathOrNull(): String? =
        if (hasPath()) path.requireText("path") else null

    private fun DebugCaptureMulti.pathOrNull(): String? =
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
