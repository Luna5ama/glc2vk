package dev.luna5ama.vibris.capture

import dev.vibris.api.RuntimeAction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class CaptureActionExecutor(
    gameDirectory: Path,
    private val captureManager: CaptureManager,
    private val shader: ShaderDebugControl,
) {
    private val gameDirectory = gameDirectory.toAbsolutePath().normalize()

    fun execute(action: RuntimeAction): CompletionStage<String> = when (action) {
        is RuntimeAction.GpuMetrics -> shader.captureMetrics(action.frames).thenApply { it.toString() }
        else -> CompletableFuture.completedFuture(executeImmediately(action).toString())
    }

    private fun executeImmediately(action: RuntimeAction) = when (action) {
        RuntimeAction.CaptureStatus -> captureStatus()
        is RuntimeAction.ReloadShader -> shader.reload()
        is RuntimeAction.CapturePass -> queuePass(action)
        is RuntimeAction.CaptureMulti -> queueMulti(action)
        RuntimeAction.ShaderStatus -> shader.status()
        RuntimeAction.ShaderErrors -> shader.errorsJson()
        is RuntimeAction.ScheduleScreenshot -> buildJsonObject {
            shader.scheduleScreenshot(action.frames)
            put("scheduled", true)
            put("frames", action.frames)
        }
        RuntimeAction.ScreenshotResult -> shader.screenshotResult()
        is RuntimeAction.GpuMetrics -> error("GPU metrics are asynchronous")
        RuntimeAction.ListSsbos -> shader.storageBuffersJson()
        is RuntimeAction.DumpSsbo -> shader.dumpStorageBuffer(action.index)
        RuntimeAction.ListTextures -> shader.texturesJson()
        is RuntimeAction.DumpTexture -> shader.dumpTexture(action.name, action.id, action.raw)
        RuntimeAction.ListPatchedShaders -> shader.patchedShadersJson()
    }

    private fun captureStatus() = captureManager.status().let { status ->
        buildJsonObject {
            put("pending", status.pending)
            put("active", status.active)
            put("saving", status.saving)
            put("lastOutputPath", status.lastOutputPath?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("lastError", status.lastError?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun queuePass(command: RuntimeAction.CapturePass) = buildJsonObject {
        val path = capturePath(command.path, command.pass)
        captureManager.prepareSingleCapture(path, command.pass)
        put("ok", true)
        put("path", path.toString())
    }

    private fun queueMulti(command: RuntimeAction.CaptureMulti) = buildJsonObject {
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
