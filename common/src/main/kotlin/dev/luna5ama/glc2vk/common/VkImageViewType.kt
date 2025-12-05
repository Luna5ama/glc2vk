package dev.luna5ama.glc2vk.common

import kotlinx.serialization.Serializable

@Serializable
enum class VkImageViewType(override val value: Int) : VkEnum {
    `1D`(0),
    `2D`(1),
    `3D`(2),
    CUBE(3),
    `1D_ARRAY`(4),
    `2D_ARRAY`(5),
    CUBE_ARRAY(6);
}