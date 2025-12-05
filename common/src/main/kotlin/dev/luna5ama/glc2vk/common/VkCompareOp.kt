package dev.luna5ama.glc2vk.common

import kotlinx.serialization.Serializable

@Serializable
enum class VkCompareOp(override val value: Int) : VkEnum {
    NEVER(0),
    LESS(1),
    EQUAL(2),
    LESS_OR_EQUAL(3),
    GREATER(4),
    NOT_EQUAL(5),
    GREATER_OR_EQUAL(6),
    ALWAYS(7);
}