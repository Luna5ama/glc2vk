package dev.luna5ama.glc2vk.common

import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.asByteBuffer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
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
            (capture.metadata.images zip capture.imageData).forEachIndexed { imageIndex, (metadata, data) ->
                data.levels.forEachIndexed { level, data ->
                    val path = outputPath.resolve("image_${imageIndex}_$level.bin")
                    FileChannel.open(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    ).use { channel ->
                        val mappedBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, data.len)
                        mappedBuffer.order(ByteOrder.nativeOrder())
                            .put(data.ptr.asByteBuffer(data.len.toInt()).order(ByteOrder.nativeOrder()))
                        mappedBuffer.force()
                    }
                }
            }
            capture.metadata.buffers.forEachIndexed { i, metadata ->
                val path = outputPath.resolve("buffer_$i.bin")
                val data = capture.bufferData[i]
                FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { channel ->
                    val mappedBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, metadata.size)
                    mappedBuffer.order(ByteOrder.nativeOrder())
                        .put(data.ptr.asByteBuffer(data.len.toInt()).order(ByteOrder.nativeOrder()))
                    mappedBuffer.force()
                }
            }
        }

        fun load(inputPath: Path): CaptureData {
            val metadata = Json.decodeFromString<CaptureMetadata>(inputPath.resolve("metadata.json").readText())
            val imageData = metadata.images.mapIndexed { i, imageMeta ->
                val levels = imageMeta.levelDataSizes.mapIndexed { levelIndex, levelSize ->
                    val path = inputPath.resolve("image_${i}_$levelIndex.bin")
                    FileChannel.open(
                        path,
                        StandardOpenOption.READ
                    ).use { channel ->
                        val mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0L, levelSize)
                            .order(ByteOrder.nativeOrder())
                        val arr = Arr.malloc(levelSize)
                        arr.ptr.asByteBuffer(levelSize.toInt()).order(ByteOrder.nativeOrder()).put(mappedBuffer)
                        arr
                    }
                }
                ImageData(levels)
            }
            val bufferData = metadata.buffers.mapIndexed { i, bufferMeta ->
                val path = inputPath.resolve("buffer_$i.bin")
                FileChannel.open(
                    path,
                    StandardOpenOption.READ
                ).use { channel ->
                    val mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, bufferMeta.size)
                        .order(ByteOrder.nativeOrder())
                    val arr = Arr.malloc(bufferMeta.size)
                    arr.ptr.asByteBuffer(bufferMeta.size.toInt()).order(ByteOrder.nativeOrder()).put(mappedBuffer)
                    arr
                }
            }
            return CaptureData(
                metadata,
                imageData,
                bufferData
            )
        }
    }
}