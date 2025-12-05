package dev.luna5ama.glc2vk.common

import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.asByteBuffer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.lwjgl.util.zstd.Zstd
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
sealed interface Command {
    @Serializable
    data class DispatchCommand(
        val x: Int,
        val y: Int,
        val z: Int
    ) : Command

    @Serializable
    data class DispatchIndirectCommand(
        val bufferIndex: Int,
        val offset: Long
    ) : Command
}

@Serializable
data class ImageMetadata(
    val name: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val mipLevels: Int,
    val arrayLayers: Int,
    val format: VkFormat,
    val type: VkImageViewType,
    val levelDataSizes: List<Long>
)

@Serializable
data class BufferMetadata(
    val name: String,
    val size: Long
)

@Serializable
data class SamplerInfo(
    val magFilter: VkFilter,
    val minFilter: VkFilter,
    val mipmapMode: VkSamplerMipmapMode,
    val addressModeU: VkSamplerAddressMode,
    val addressModeV: VkSamplerAddressMode,
    val addressModeW: VkSamplerAddressMode,
    val mipLodBias: Float,
    val anisotropyEnable: Boolean,
    val maxAnisotropy: Float,
    val compareEnable: Boolean,
    val compareOp: VkCompareOp,
    val minLod: Float,
    val maxLod: Float,
    val boarderColorR: Float,
    val boarderColorG: Float,
    val boarderColorB: Float,
    val boarderColorA: Float,
    val unnormalizedCoordinates: Boolean
)

@Serializable
data class SamplerBinding(
    val name: String,
    val imageIndex: Int,
    val set: Int,
    val binding: Int,
    val samplerInfo: SamplerInfo
)

@Serializable
data class ImageBinding(
    val name: String,
    val imageIndex: Int,
    val set: Int,
    val binding: Int
)

@Serializable
data class BufferBinding(
    val name: String,
    val bufferIndex: Int,
    val set: Int,
    val binding: Int,
    val offset: Long,
)

@Serializable
data class CaptureMetadata(
    val images: List<ImageMetadata>,
    val buffers: List<BufferMetadata>,
    val samplerBindings: List<SamplerBinding>,
    val imageBindings: List<ImageBinding>,
    val storageBufferBindings: List<BufferBinding>,
    val uniformBufferBindings: List<BufferBinding>,
    val command: Command
)

class ImageData(
    val levels: List<Arr>
) {
    fun free() {
        for (level in levels) {
            level.free()
        }
    }
}

class CaptureData(
    val metadata: CaptureMetadata,
    val imageData: List<ImageData>,
    val bufferData: List<Arr>,
) {
    fun free() {
        for (image in imageData) {
            image.free()
        }
        for (buffer in bufferData) {
            buffer.free()
        }
    }

    companion object {
        fun save(outputPath: Path, capture: CaptureData) {
            @OptIn(ExperimentalSerializationApi::class)
            val jsonInstance = Json {
                prettyPrint = true
                prettyPrintIndent = "    "
            }
            outputPath.createDirectories()
            outputPath.resolve("metadata.json").writeText(jsonInstance.encodeToString(capture.metadata))

            fun writeCompressed(path: Path, data: Arr) {
                val maxCompressedSize = Zstd.ZSTD_compressBound(data.len)
                Arr.malloc(maxCompressedSize).use {
                    val outputBuffer = it.ptr.asByteBuffer(it.len.toInt()).order(ByteOrder.nativeOrder())
                    val srcAsByteBuffer = data.ptr.asByteBuffer(data.len.toInt()).order(ByteOrder.nativeOrder())
                    val finalSize = Zstd.ZSTD_compress(outputBuffer, srcAsByteBuffer, Zstd.ZSTD_CLEVEL_DEFAULT)
                    outputBuffer.clear()
                    outputBuffer.limit(finalSize.toInt())

                    Files.newByteChannel(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    ).use { channel ->
                        channel.write(outputBuffer)
                    }
                }
            }

            fun writeUncompressed(path: Path, data: Arr) {
                Files.newByteChannel(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { channel ->
                    val buffer = data.ptr.asByteBuffer(data.len.toInt()).order(ByteOrder.nativeOrder())
                    channel.write(buffer)
                }
            }

            (capture.metadata.images zip capture.imageData).forEachIndexed { imageIndex, (metadata, data) ->
                data.levels.forEachIndexed { level, data ->
                    writeUncompressed(outputPath.resolve("image_${imageIndex}_$level.bin"), data)
                }
            }
            capture.metadata.buffers.forEachIndexed { i, metadata ->
                writeUncompressed(outputPath.resolve("buffer_$i.bin"), capture.bufferData[i])
            }
        }

        fun load(inputPath: Path): CaptureData {
            val metadata = Json.decodeFromString<CaptureMetadata>(inputPath.resolve("metadata.json").readText())
            fun readCompressed(path: Path, uncompressedSize: Long): Arr {
                return Files.newByteChannel(
                    path,
                    StandardOpenOption.READ
                ).use { channel ->
                    val outputBuffer = Arr.malloc(uncompressedSize)
                    Arr.malloc(channel.size()).use {
                        val inputBuffer = it.ptr.asByteBuffer(it.len.toInt()).order(ByteOrder.nativeOrder())
                        val outputBufferBB = outputBuffer.ptr.asByteBuffer(outputBuffer.len.toInt()).order(ByteOrder.nativeOrder())
                        channel.read(inputBuffer)
                        inputBuffer.clear()
                        val decompressedSize = Zstd.ZSTD_decompress(
                            outputBufferBB,
                            inputBuffer
                        )
                        if (decompressedSize != uncompressedSize) {
                            error("Decompression size mismatch for $path: expected $uncompressedSize, got $decompressedSize")
                        }
                    }
                    outputBuffer
                }
            }

            val imageData = metadata.images.mapIndexed { i, imageMeta ->
                val levels = imageMeta.levelDataSizes.mapIndexed { levelIndex, levelSize ->
                    readCompressed(inputPath.resolve("image_${i}_$levelIndex.bin.zstd"), levelSize)
                }
                ImageData(levels)
            }
            val bufferData = metadata.buffers.mapIndexed { i, bufferMeta ->
                readCompressed(inputPath.resolve("buffer_$i.bin.zstd"), bufferMeta.size)
            }
            return CaptureData(
                metadata,
                imageData,
                bufferData
            )
        }
    }
}