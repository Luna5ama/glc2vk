package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.ArrayDeque
import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CaptureResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class ShaderDebugControl constructor(
    private val host: ShaderDebugHost,
) {
    private val errorLock = Any()
    private val errors = ArrayDeque<ShaderDebugError>()
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

    fun inspect(): JsonObject = JsonObject(status() + ("errors" to errorJson()))

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

    fun errorList(): List<ShaderDebugError> = errorSnapshot()

    fun tickFrame() = metrics.finishFrame()

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

    fun buffersJson(): JsonObject = buildJsonObject {
        put("buffers", buildJsonArray {
            host.storageBuffers().forEach { buffer ->
                add(buildJsonObject {
                    put("name", buffer.name)
                    put("category", buffer.category)
                    put("size_bytes", buffer.sizeBytes)
                })
            }
        })
    }

    fun texturesJson(): JsonObject {
        val catalog = host.textureCatalog()
        return buildJsonObject {
            put("textures", textureArray(catalog.textures))
        }
    }

    fun capturePatchedShaders(
        artifactName: String,
        sink: ArtifactSink,
        frameId: Long,
        cancellation: CancellationToken,
    ): CompletionStage<CaptureResult> = CompletableFuture.supplyAsync {
        PatchedShaderCapture.capture(host, artifactName, sink, frameId, cancellation)
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
                put("category", texture.category)
                put("target", texture.target)
                put("width", texture.width)
                put("height", texture.height)
                put("depth", texture.depth)
                put("mip_levels", texture.mipLevels)
                put("internal_format", texture.internalFormat)
                put("channel_layout", texture.channelLayout)
                put("numeric_class", texture.numericClass)
                put("component_bits", texture.componentBits)
            })
        }
    }

    private companion object {
        const val MAX_ERRORS = 100
    }
}
