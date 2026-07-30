package dev.vibris.core

import dev.vibris.api.DebugControlCommand
import dev.vibris.protocol.v1.DebugControlRequest
import dev.vibris.protocol.v1.DebugOperation

internal object DebugControlProtocol {
    fun toApi(request: DebugControlRequest): DebugControlCommand = when (request.operation) {
        DebugOperation.DEBUG_OPERATION_CAPTURE_STATUS -> DebugControlCommand.CaptureStatus
        DebugOperation.DEBUG_OPERATION_RELOAD_SHADER -> DebugControlCommand.ReloadShader
        DebugOperation.DEBUG_OPERATION_CAPTURE_PASS -> DebugControlCommand.CapturePass(request.pass, request.path())
        DebugOperation.DEBUG_OPERATION_CAPTURE_MULTI ->
            DebugControlCommand.CaptureMulti(request.captureType, request.path())
        DebugOperation.DEBUG_OPERATION_SHADER_STATUS -> DebugControlCommand.ShaderStatus
        DebugOperation.DEBUG_OPERATION_SHADER_ERRORS -> DebugControlCommand.ShaderErrors
        DebugOperation.DEBUG_OPERATION_SCHEDULE_SCREENSHOT -> DebugControlCommand.ScheduleScreenshot(request.frames)
        DebugOperation.DEBUG_OPERATION_SCREENSHOT_RESULT -> DebugControlCommand.ScreenshotResult
        DebugOperation.DEBUG_OPERATION_GPU_METRICS -> DebugControlCommand.GpuMetrics
        DebugOperation.DEBUG_OPERATION_LIST_SSBOS -> DebugControlCommand.ListSsbos
        DebugOperation.DEBUG_OPERATION_DUMP_SSBO -> DebugControlCommand.DumpSsbo(request.index)
        DebugOperation.DEBUG_OPERATION_LIST_TEXTURES -> DebugControlCommand.ListTextures
        DebugOperation.DEBUG_OPERATION_DUMP_TEXTURE -> DebugControlCommand.DumpTexture(
            request.textureName.takeIf { request.hasTextureName() },
            request.textureId.takeIf { request.hasTextureId() },
            request.raw,
        )
        DebugOperation.DEBUG_OPERATION_LIST_PATCHED_SHADERS -> DebugControlCommand.ListPatchedShaders
        DebugOperation.DEBUG_OPERATION_UNSPECIFIED,
        DebugOperation.UNRECOGNIZED,
        -> throw IllegalArgumentException("Unsupported debug operation")
    }

    private fun DebugControlRequest.path(): String? = path.takeUnless(String::isBlank)
}
