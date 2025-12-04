package dev.luna5ama.glc2vk.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

interface VkEnum {
    val value: Int

    abstract class Serializer<T : VkEnum>(name: String) : KSerializer<T> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.INT)

        override fun serialize(
            encoder: Encoder,
            value: T
        ) {
            encoder.encodeInt(value.value)
        }

        override fun deserialize(decoder: Decoder): T {
            return fromNativeData(decoder.decodeInt())
        }

        abstract fun fromNativeData(value: Int): T
    }
}