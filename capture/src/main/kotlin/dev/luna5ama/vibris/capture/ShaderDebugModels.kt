package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

data class StorageBufferInfo(val index: Int, val glId: Int)

data class TextureInfo(val name: String, val textureId: Int, val width: Int?, val height: Int?)

data class TextureCatalog(val colortex: List<TextureInfo>, val custom: List<TextureInfo>)

data class ShaderDebugError(
    val type: String,
    val filename: String,
    val message: String,
    val stackTrace: String,
    val timestamp: Long
)

data class GpuTimingStats(
    val average: Long,
    val p5: Long,
    val p95: Long,
    val p50: Long,
)

interface ShaderDebugHost {
    fun shaderPackName(): String?

    @Throws(Exception::class)
    fun reloadShaders()

    fun gameDirectory(): Path

    fun debugShadersEnabled(): Boolean

    fun storageBuffers(): List<StorageBufferInfo>

    fun textureCatalog(): TextureCatalog

    fun resolveTexture(name: String): Int?
}

interface ShaderDebugResourceDumper {
    fun dumpStorageBuffer(buffer: StorageBufferInfo, output: Path): JsonObject

    fun dumpTexture(textureId: Int, output: Path, raw: Boolean): JsonObject
}
