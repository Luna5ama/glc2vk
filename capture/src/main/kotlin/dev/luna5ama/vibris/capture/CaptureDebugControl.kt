package dev.luna5ama.vibris.capture

import dev.vibris.api.DebugControlCommand
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

class CaptureDebugControl(
    gameDirectory: Path,
    private val captureManager: CaptureManager,
    private val shader: ShaderDebugControl,
) {
    private val gameDirectory = gameDirectory.toAbsolutePath().normalize()

    fun execute(command: DebugControlCommand): String = when (command) {
        DebugControlCommand.CaptureStatus -> captureStatus()
        is DebugControlCommand.ReloadShader -> shader.reload()
        is DebugControlCommand.CapturePass -> queuePass(command)
        is DebugControlCommand.CaptureMulti -> queueMulti(command)
        DebugControlCommand.ShaderStatus -> shader.status()
        DebugControlCommand.ShaderErrors -> shader.errorsJson()
        is DebugControlCommand.ScheduleScreenshot -> buildJsonObject {
            shader.scheduleScreenshot(command.frames)
            put("scheduled", true)
            put("frames", command.frames)
        }
        DebugControlCommand.ScreenshotResult -> shader.screenshotResult()
        DebugControlCommand.GpuMetrics -> shader.metricsJson()
        DebugControlCommand.ListSsbos -> shader.storageBuffersJson()
        is DebugControlCommand.DumpSsbo -> shader.dumpStorageBuffer(command.index)
        DebugControlCommand.ListTextures -> shader.texturesJson()
        is DebugControlCommand.DumpTexture -> shader.dumpTexture(command.name, command.id, command.raw)
        DebugControlCommand.ListPatchedShaders -> shader.patchedShadersJson()
    }.toString()

    private fun captureStatus() = captureManager.status().let { status ->
        buildJsonObject {
            put("pending", status.pending)
            put("active", status.active)
            put("saving", status.saving)
            put("lastOutputPath", status.lastOutputPath?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("lastError", status.lastError?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun queuePass(command: DebugControlCommand.CapturePass) = buildJsonObject {
        val path = capturePath(command.path, command.pass)
        captureManager.prepareSingleCapture(path, command.pass)
        put("ok", true)
        put("path", path.toString())
    }

    private fun queueMulti(command: DebugControlCommand.CaptureMulti) = buildJsonObject {
        val path = capturePath(command.path, command.type)
        captureManager.prepareMultiCapture(path, command.type)
        put("ok", true)
        put("path", path.toString())
    }

    private fun capturePath(path: String?, name: String): Path {
        val requested = path?.let(Path::of) ?: CaptureManager.defaultOutputPath(name)
        val resolved = gameDirectory.resolve(requested).toAbsolutePath().normalize()
        require(resolved.startsWith(gameDirectory)) { "Capture path must remain inside the game directory" }
        return resolved
    }
}
