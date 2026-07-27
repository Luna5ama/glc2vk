package dev.luna5ama.vibris.common

import kotlinx.serialization.Serializable

@Serializable
enum class VkFilter(override val value: Int) : VkEnum {
    NEAREST(0),
    LINEAR(1);
}