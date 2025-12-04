package dev.luna5ama.glc2vk.common

import kotlinx.serialization.Serializable

@Serializable(VkFilter.Serializer::class)
enum class VkFilter(override val value: Int) : VkEnum {
    NEAREST(0),
    LINEAR(1);

    companion object Serializer : VkEnum.Serializer<VkFilter>("VkFilter") {
        override fun fromNativeData(value: Int): VkFilter = when (value) {
            0 -> NEAREST
            1 -> LINEAR
            else -> throw IllegalArgumentException("Unknown value: $value")
        }
    }
}