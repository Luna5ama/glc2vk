package dev.luna5ama.glc2vk.common

import kotlinx.serialization.Serializable

@Serializable(VkSamplerAddressMode.Serializer::class)
enum class VkSamplerAddressMode(override val value: Int) : VkEnum {
    REPEAT(0),
    MIRRORED_REPEAT(1),
    CLAMP_TO_EDGE(2),
    CLAMP_TO_BORDER(3),
    MIRROR_CLAMP_TO_EDGE(4);

    companion object Serializer : VkEnum.Serializer<VkSamplerAddressMode>("VkFilter") {
        override fun fromNativeData(value: Int): VkSamplerAddressMode = when (value) {
            0 -> REPEAT
            1 -> MIRRORED_REPEAT
            2 -> CLAMP_TO_EDGE
            3 -> CLAMP_TO_BORDER
            4 -> MIRROR_CLAMP_TO_EDGE
            else -> throw IllegalArgumentException("Unknown value: $value")
        }
    }
}