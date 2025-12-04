package dev.luna5ama.glc2vk.common

import dev.luna5ama.kmogus.Arr
import kotlinx.serialization.Serializable

@Serializable
data class ImageMetadata(
    val name: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val mipLevels: Int,
    val arrayLayers: Int,
    val format: VkFormat
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

class ResourceCapture(
    val metadata: ResourceMetadata,
    val imageData: List<Arr>,
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
}