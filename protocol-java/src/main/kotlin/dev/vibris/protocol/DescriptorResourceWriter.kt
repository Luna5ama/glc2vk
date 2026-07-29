package dev.vibris.protocol

import dev.vibris.protocol.v1.VibrisControlProto
import java.nio.file.Files
import java.nio.file.Path

object DescriptorResourceWriter {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Expected generated resource directory" }
        val descriptor = Path.of(arguments[0]).resolve("META-INF/vibris/vibris_control.desc")
        Files.createDirectories(descriptor.parent)
        Files.write(descriptor, VibrisControlProto.getDescriptor().toProto().toByteArray())
    }
}