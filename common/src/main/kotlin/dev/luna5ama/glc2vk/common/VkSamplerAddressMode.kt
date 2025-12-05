package dev.luna5ama.glc2vk.common

import kotlinx.serialization.Serializable

@Serializable
enum class VkSamplerAddressMode(override val value: Int) : VkEnum {
    REPEAT(0),
    MIRRORED_REPEAT(1),
    CLAMP_TO_EDGE(2),
    CLAMP_TO_BORDER(3),
    MIRROR_CLAMP_TO_EDGE(4);
}