package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.CompletionStage

class ShaderDebugControl @JvmOverloads constructor(
    private val host: ShaderDebugHost,
    private val dumper: ShaderDebugResourceDumper = GlShaderDebugResourceDumper
) {
    private val errorLock = Any()
    private val errors = ArrayDeque<ShaderDebugError>()
    private val screenshotLock = Any()
    private var screenshotFrames = 0
    @Volatile private var lastScreenshotPath: Path? = null
    private val passStack: ThreadLocal<ArrayDeque<String>> = ThreadLocal.withInitial { ArrayDeque() }
    private val timingStack: ThreadLocal<ArrayDeque<Boolean>> = ThreadLocal.withInitial { ArrayDeque() }
    private val metrics = GpuTimingMetrics()

    fun status(): JsonObject = try {
        val pack = host.shaderPackName()
        buildJsonObject {
            put("status", "ok")
            put("pack_loaded", pack != null)
            put("shaderpack", pack?.let(::JsonPrimitive) ?: JsonNull)
        }
    } catch (_: Exception) {
        buildJsonObject {
            put("status", "ok")
            put("pack_loaded", false)
            put("shaderpack", "unknown")
        }
    }

    fun reload(): JsonObject {
        clearErrors()
        val failure = try {
            host.reloadShaders()
            null
        } catch (exception: Exception) {
            exception
        }
        if (failure != null && errorSnapshot().isEmpty()) {
            recordError(
                failure.javaClass.simpleName,
                "",
                failure.message.orEmpty(),
                failure.stackTraceToString(),
                System.currentTimeMillis()
            )
        }
        return buildJsonObject {
            put("success", failure == null)
            put("errors", errorJson())
        }
    }

    fun recordError(
        type: String,
        filename: String,
        message: String,
        stackTrace: String,
        timestamp: Long = System.currentTimeMillis()
    ) = synchronized(errorLock) {
        while (errors.size >= MAX_ERRORS) errors.removeFirst()
        errors.addLast(ShaderDebugError(type, filename, message, stackTrace, timestamp))
    }

    fun clearErrors() = synchronized(errorLock) { errors.clear() }

    fun errorsJson(): JsonObject = buildJsonObject { put("errors", errorJson()) }

    fun errorList(): List<ShaderDebugError> = errorSnapshot()

    fun scheduleScreenshot(frames: Int) {
        require(frames > 0) { "frames must be positive" }
        synchronized(screenshotLock) { screenshotFrames = frames }
    }

    fun tickFrame() {
        val capture = synchronized(screenshotLock) {
            if (screenshotFrames == 0) false else {
                screenshotFrames--
                screenshotFrames == 0
            }
        }
        if (capture) host.captureScreenshot { lastScreenshotPath = it }
        metrics.finishFrame()
    }

    fun screenshotResult(): JsonObject = buildJsonObject {
        put("path", lastScreenshotPath?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    }

    fun captureMetrics(frames: Int): CompletionStage<JsonObject> =
        metrics.capture(frames).thenApply(::metricsJson)

    private fun metricsJson(values: Map<String, GpuTimingStats>): JsonObject = buildJsonObject {
        put("gpuTimings", buildJsonObject {
            values.forEach { (name, stats) ->
                put(name, buildJsonObject {
                    put("avg", stats.average)
                    put("p5", stats.p5)
                    put("p95", stats.p95)
                    put("p50", stats.p50)
                })
            }
        })
    }

    fun storageBuffersJson(): JsonObject = buildJsonObject {
        put("buffers", buildJsonArray {
            host.storageBuffers().forEach { buffer ->
                add(buildJsonObject {
                    put("index", buffer.index)
                    put("glId", buffer.glId)
                })
            }
        })
    }

    fun dumpStorageBuffer(index: Int): JsonObject {
        val buffer = host.storageBuffers().firstOrNull { it.index == index }
            ?: throw IllegalArgumentException("No SSBO found at index $index")
        return dumper.dumpStorageBuffer(buffer, host.gameDirectory().resolve("ssbo_dumps/ssbo_$index.bin"))
    }

    fun texturesJson(): JsonObject {
        val catalog = host.textureCatalog()
        return buildJsonObject {
            put("colortex", textureArray(catalog.colortex))
            put("custom", textureArray(catalog.custom))
        }
    }

    fun dumpTexture(name: String?, id: Int?, raw: Boolean): JsonObject {
        val textureId = if (name != null) {
            host.resolveTexture(name) ?: throw IllegalArgumentException("Unknown texture: $name")
        } else {
            id ?: 0
        }
        val fileName = safeFileName(name ?: "texture_$textureId")
        val extension = if (raw) "bin" else "png"
        val result = dumper.dumpTexture(
            textureId,
            host.gameDirectory().resolve("texture_dumps/$fileName.$extension"),
            raw
        )
        return if (name == null) result else JsonObject(result + ("name" to JsonPrimitive(name)))
    }

    fun patchedShadersJson(): JsonObject {
        val directory = host.gameDirectory().resolve("patched_shaders")
        val files = if (Files.isDirectory(directory)) {
            Files.list(directory).use { stream ->
                stream.map { it.fileName.toString() }.sorted().toList()
            }
        } else {
            emptyList()
        }
        return buildJsonObject {
            put("debugEnabled", host.debugShadersEnabled())
            put("path", directory.toString())
            put("files", JsonArray(files.map(::JsonPrimitive)))
        }
    }

    fun pushPass(name: String) {
        passStack.get().addLast(name)
        metrics.begin("${name}_total")
    }

    fun popPass() {
        val stack = passStack.get()
        if (stack.isNotEmpty()) {
            metrics.end()
            stack.removeLast()
        }
        if (stack.isEmpty()) passStack.remove()
    }

    fun beginDraw() = beginTiming("draw")

    fun endDraw() = endTiming()

    fun beginCompute() = beginTiming("compute")

    fun endCompute() = endTiming()

    private fun beginTiming(kind: String) {
        val pass = currentPass()
        timingStack.get().addLast(pass != null)
        if (pass != null) metrics.begin("${pass}_$kind")
    }

    private fun endTiming() {
        val stack = timingStack.get()
        if (stack.isNotEmpty() && stack.removeLast()) metrics.end()
        if (stack.isEmpty()) timingStack.remove()
    }

    private fun currentPass() = passStack.get().peekLast()

    private fun safeFileName(value: String): String {
        val sanitized = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return sanitized.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "texture"
    }

    private fun errorSnapshot() = synchronized(errorLock) { errors.toList() }

    private fun errorJson() = buildJsonArray {
        errorSnapshot().forEach { error ->
            add(buildJsonObject {
                put("type", error.type)
                put("filename", error.filename)
                put("message", error.message)
                put("stackTrace", error.stackTrace)
                put("timestamp", error.timestamp)
            })
        }
    }

    private fun textureArray(textures: List<TextureInfo>) = buildJsonArray {
        textures.forEach { texture ->
            add(buildJsonObject {
                put("name", texture.name)
                put("textureId", texture.textureId)
                texture.width?.let { put("width", it) }
                texture.height?.let { put("height", it) }
            })
        }
    }

    private companion object {
        const val MAX_ERRORS = 100
    }
}
