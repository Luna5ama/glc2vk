package dev.luna5ama.glc2vk.common

import kotlinx.serialization.Serializable

@Serializable(VkCompareOp.Serializer::class)
enum class VkCompareOp(override val value: Int) : VkEnum {
    NEVER(0),
    LESS(1),
    EQUAL(2),
    LESS_OR_EQUAL(3),
    GREATER(4),
    NOT_EQUAL(5),
    GREATER_OR_EQUAL(6),
    ALWAYS(7);

    companion object Serializer : VkEnum.Serializer<VkCompareOp>("VkCompareOp") {
        override fun fromNativeData(value: Int): VkCompareOp = when (value) {
            0 -> NEVER
            1 -> LESS
            2 -> EQUAL
            3 -> LESS_OR_EQUAL
            4 -> GREATER
            5 -> NOT_EQUAL
            6 -> GREATER_OR_EQUAL
            7 -> ALWAYS
            else -> throw IllegalArgumentException("Unknown value: $value")
        }
    }
}