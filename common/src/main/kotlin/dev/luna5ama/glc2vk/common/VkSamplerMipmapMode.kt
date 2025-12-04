package dev.luna5ama.glc2vk.common

import kotlinx.serialization.Serializable

@Serializable(VkSamplerMipmapMode.Serializer::class)
enum class VkSamplerMipmapMode(override val value: Int) : VkEnum {
    NEAREST(0),
    LINEAR(1);

    companion object Serializer : VkEnum.Serializer<VkSamplerMipmapMode>("VkSamplerMipmapMode") {
        override fun fromNativeData(value: Int): VkSamplerMipmapMode = when (value) {
            0 -> NEAREST
            1 -> LINEAR
            else -> throw IllegalArgumentException("Unknown value: $value")
        }
    }
}