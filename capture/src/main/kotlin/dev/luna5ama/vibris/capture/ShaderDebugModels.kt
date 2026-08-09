package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

data class StorageBufferInfo(val name: String, val glId: Int, val sizeBytes: Long, val category: String = "iris_ssbo")

data class TextureInfo(
    val name: String,
    val textureId: Int,
    val category: String,
    val target: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val mipLevels: Int,
    val internalFormat: String,
    val channelLayout: String,
    val numericClass: String,
    val componentBits: Int,
)

data class TextureCatalog(val textures: List<TextureInfo>)

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

    @Throws(Exception::class)
    fun awaitPatchedShaderWrites() = Unit
}
