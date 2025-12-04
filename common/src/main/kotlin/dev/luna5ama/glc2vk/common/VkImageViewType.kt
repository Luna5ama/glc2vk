package dev.luna5ama.glc2vk.common

import kotlinx.serialization.Serializable

@Serializable(VkImageViewType.Serializer::class)
enum class VkImageViewType(override val value: Int) : VkEnum {
    `1D`(0),
    `2D`(1),
    `3D`(2),
    CUBE(3),
    `1D_ARRAY`(4),
    `2D_ARRAY`(5),
    CUBE_ARRAY(6), ;

    companion object Serializer : VkEnum.Serializer<VkImageViewType>("VkImageViewType") {
        override fun fromNativeData(value: Int): VkImageViewType = when (value) {
            0 -> `1D`
            1 -> `2D`
            2 -> `3D`
            3 -> CUBE
            4 -> `1D_ARRAY`
            5 -> `2D_ARRAY`
            6 -> CUBE_ARRAY
            else -> throw IllegalArgumentException("Unknown value: $value")
        }
    }
}