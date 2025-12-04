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
import kotlin.io.path.writeText

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
data class ResourceMetadata(
    val images: List<ImageMetadata>,
    val buffers: List<BufferMetadata>,
    val samplerBindings: List<SamplerBinding>,
    val imageBindings: List<ImageBinding>,
    val storageBufferBindings: List<BufferBinding>,
    val uniformBufferBindings: List<BufferBinding>
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

class ResourceCapture(
    val metadata: ResourceMetadata,
    val imageData: List<ImageData>,
    val bufferData: List<Arr>
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
//        TODO: Implement compressed resource writing
//        private fun writeCompressedResources(capture: ResourceCapture) {
//            FileChannel.open(
//                outputPath.resolve("resouces.zstd"),
//                StandardOpenOption.CREATE,
//                StandardOpenOption.READ,
//                StandardOpenOption.WRITE,
//                StandardOpenOption.TRUNCATE_EXISTING
//            ).use { channel ->
//                val alignment = 64L
//
//                fun alignUpTo(value: Long, alignment: Long): Long {
//                    return ((value + alignment - 1L) / alignment) * alignment
//                }
//
//
//                org.lwjgl.system.MemoryStack.stackPush().use { stack ->
//                    val cctx = Zstd.ZSTD_createCCtx()
//                    var outputOffset = 0L
//                    var fileOffset = 0L
//
//                    var outBuffer = channel.map(FileChannel.MapMode.READ_WRITE, fileOffset, bufferSize)
//                        .order(ByteOrder.nativeOrder())
//
//                    // At least 16MB buffer size for Zstd streaming
//                    val bufferSize = maxOf(Zstd.ZSTD_CStreamInSize(), 16 * 1024 * 1024)
//
//
//                    fun streamCompress(input: Arr) {
//                        stack.push().use { stack ->
//
//                            val zstdOutputBuffer = ZSTDOutBuffer.calloc(stack)
//                                .dst(outBuffer)
//                                .pos(0L)
//                        }
//                    }
//                }
//            }
//        }

        fun save(outputPath: Path, capture: ResourceCapture) {
            @OptIn(ExperimentalSerializationApi::class)
            val jsonInstance = Json {
                prettyPrint = true
                prettyPrintIndent = "    "
            }
            outputPath.createDirectories()
            outputPath.resolve("metadata.json").writeText(jsonInstance.encodeToString(capture.metadata))
            (capture.metadata.images zip capture.imageData).forEach { (metadata, data) ->
                val path = outputPath.resolve("image_${metadata.name}.bin")
                val totalSize = metadata.levelDataSizes.sum()
                FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { channel ->
                    val mappedBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalSize)
                        .order(ByteOrder.nativeOrder())
                    data.levels.forEach {
                        mappedBuffer.put(it.ptr.asByteBuffer(it.len.toInt()))
                    }
                }
            }
            capture.metadata.buffers.forEachIndexed { index, metadata ->
                val path = outputPath.resolve("buffer_${metadata.name}.bin")
                val data = capture.bufferData[index]
                FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { channel ->
                    val mappedBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, metadata.size)
                        .order(ByteOrder.nativeOrder())
                    mappedBuffer.put(data.ptr.asByteBuffer(data.len.toInt()))
                }
            }
        }
    }
}