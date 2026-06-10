package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.common.Command
import dev.luna5ama.glc2vk.common.defaultUniformBindings
import dev.luna5ama.glc2vk.common.imageBindings
import dev.luna5ama.glc2vk.common.samplerBindings
import dev.luna5ama.glc2vk.common.storageBufferBindings
import dev.luna5ama.glc2vk.common.uniformBufferBindings
import dev.luna5ama.glwrapper.ShaderProgramResourceManager
import dev.luna5ama.glwrapper.enums.GLSLDataType

fun validateOpenGLCapturedBindings(program: Int, passName: String?, command: Command) {
    val resources = ShaderProgramResourceManager(program)
    val missing = mutableListOf<String>()
    val samplerNames = command.samplerBindings().mapTo(HashSet()) { it.name }
    val imageNames = command.imageBindings().mapTo(HashSet()) { it.name }
    val storageBufferNames = command.storageBufferBindings().mapTo(HashSet()) { it.name }
    val uniformBufferNames = command.uniformBufferBindings().mapTo(HashSet()) { it.name }
    val defaultUniformNames = command.defaultUniformBindings().mapTo(HashSet()) { it.name }

    resources.uniformResource.entries.values.forEach { uniform ->
        if (uniform.blockIndex >= 0) return@forEach
        val name = uniform.name.activeResourceName()
        if (name.startsWith("gl_")) return@forEach
        when (uniform.type) {
            is GLSLDataType.Value -> {
                if (name !in defaultUniformNames) {
                    missing += "default uniform $name"
                }
            }

            is GLSLDataType.Opaque.Sampler -> {
                if (name !in samplerNames) {
                    missing += "sampler $name"
                }
            }

            is GLSLDataType.Opaque.Image -> {
                if (name !in imageNames) {
                    missing += "image $name"
                }
            }

            else -> {
                missing += "opaque uniform $name (${uniform.type.codeStr})"
            }
        }
    }

    resources.shaderStorageBlockResource.entries.values.forEach { buffer ->
        if (buffer.name !in storageBufferNames) {
            missing += "storage buffer ${buffer.name}"
        }
    }
    resources.uniformBlockResource.entries.values.forEach { buffer ->
        if (buffer.name !in uniformBufferNames) {
            missing += "uniform buffer ${buffer.name}"
        }
    }

    check(missing.isEmpty()) {
        val label = passName?.let { " for pass $it" }.orEmpty()
        "Replacement shader$label references active resources that were not captured: ${missing.joinToString()}"
    }
}

private fun String.activeResourceName(): String {
    return removeSuffix("[0]")
}
