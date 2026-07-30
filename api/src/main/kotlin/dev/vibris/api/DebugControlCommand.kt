package dev.vibris.api

sealed interface DebugControlCommand {
    data object CaptureStatus : DebugControlCommand
    data class ReloadShader(val config: Map<String, String>?) : DebugControlCommand
    data class CapturePass(val pass: String, val path: String?) : DebugControlCommand
    data class CaptureMulti(val type: String, val path: String?) : DebugControlCommand
    data object ShaderStatus : DebugControlCommand
    data object ShaderErrors : DebugControlCommand
    data class ScheduleScreenshot(val frames: Int) : DebugControlCommand
    data object ScreenshotResult : DebugControlCommand
    data object GpuMetrics : DebugControlCommand
    data object ListSsbos : DebugControlCommand
    data class DumpSsbo(val index: Int) : DebugControlCommand
    data object ListTextures : DebugControlCommand
    data class DumpTexture(val name: String?, val id: Int?, val raw: Boolean) : DebugControlCommand
    data object ListPatchedShaders : DebugControlCommand
}
