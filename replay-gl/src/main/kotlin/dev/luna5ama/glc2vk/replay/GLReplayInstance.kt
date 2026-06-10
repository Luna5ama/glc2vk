package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.common.CaptureData
import dev.luna5ama.glc2vk.common.Command
import dev.luna5ama.glc2vk.common.ShaderSourceResolver
import dev.luna5ama.glc2vk.common.debugLabels
import dev.luna5ama.glc2vk.common.shaderIndex
import dev.luna5ama.glc2vk.capture.ShaderSourceContext
import dev.luna5ama.glc2vk.capture.validateCapturedBindings
import dev.luna5ama.glwrapper.base.GL_BUFFER_UPDATE_BARRIER_BIT
import dev.luna5ama.glwrapper.base.GL_DEBUG_SOURCE_APPLICATION
import dev.luna5ama.glwrapper.base.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
import dev.luna5ama.glwrapper.base.GL_SHADER_STORAGE_BARRIER_BIT
import dev.luna5ama.glwrapper.base.GL_TEXTURE_FETCH_BARRIER_BIT
import dev.luna5ama.glwrapper.base.GL_UNIFORM_BARRIER_BIT
import dev.luna5ama.glwrapper.base.glDeleteProgram
import dev.luna5ama.glwrapper.base.glDispatchCompute
import dev.luna5ama.glwrapper.base.glDispatchComputeIndirect
import dev.luna5ama.glwrapper.base.glFinish
import dev.luna5ama.glwrapper.base.glMemoryBarrier
import dev.luna5ama.glwrapper.base.glPopDebugGroup
import dev.luna5ama.glwrapper.base.glPushDebugGroup
import dev.luna5ama.glwrapper.base.glUseProgram
import java.nio.file.Path

class GLReplayInstance(
    private val captureData: CaptureData,
    private val capturePath: Path,
    private val shaderOverridePath: Path? = null
) {
    private val commands = captureData.metadata.commandsForReplay()
    private val shaderSourceResolver = ShaderSourceResolver(capturePath, shaderOverridePath)
    private val programs = List(captureData.metadata.shaderCount) { shaderIndex ->
        val resolved = shaderSourceResolver.resolve(captureData.metadata, shaderIndex)
        val shaderMetadata = captureData.metadata.shaderMetadata(shaderIndex)
        val shaderInfo = ShaderSourceContext(resolved.source)
            .setIdentity(
                passName = shaderMetadata.passName,
                programType = shaderMetadata.programType,
                sourcePath = shaderMetadata.sourcePath,
                stage = shaderMetadata.stage
            ).also {
                it.patchShaderForVulkan()
            }.toShaderInfo()
        if (shaderOverridePath != null) {
            commands.filter { it.shaderIndex() == shaderIndex }.forEach(shaderInfo::validateCapturedBindings)
        }
        loadOpenGLComputeProgram(resolved.source, resolved.path)
    }
    private val resources = runCatching {
        GLReplayResource(captureData)
    }.onFailure {
        programs.forEach(::glDeleteProgram)
    }.getOrThrow()

    fun execute() {
        resources.resetCapturedData()
        glMemoryBarrier(
            GL_BUFFER_UPDATE_BARRIER_BIT or
                GL_TEXTURE_FETCH_BARRIER_BIT or
                GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or
                GL_SHADER_STORAGE_BARRIER_BIT or
                GL_UNIFORM_BARRIER_BIT
        )

        commands.forEach { command ->
            command.debugLabels().forEach { label ->
                glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION, 0, label)
            }

            glUseProgram(programs[command.shaderIndex()])
            resources.useProgram(programs[command.shaderIndex()])
            resources.bind(command)

            when (command) {
                is Command.DispatchCommand -> {
                    glDispatchCompute(command.x, command.y, command.z)
                }

                is Command.DispatchIndirectCommand -> {
                    resources.bindDispatchIndirectBuffer(command.bufferIndex)
                    glDispatchComputeIndirect(command.offset)
                }
            }

            glMemoryBarrier(
                GL_SHADER_STORAGE_BARRIER_BIT or
                    GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or
                    GL_TEXTURE_FETCH_BARRIER_BIT or
                    GL_BUFFER_UPDATE_BARRIER_BIT or
                    GL_UNIFORM_BARRIER_BIT
            )

            repeat(command.debugLabels().size) {
                glPopDebugGroup()
            }
        }

        glMemoryBarrier(
            GL_SHADER_STORAGE_BARRIER_BIT or
                GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or
                GL_TEXTURE_FETCH_BARRIER_BIT or
                GL_BUFFER_UPDATE_BARRIER_BIT
        )
        glFinish()
    }

    fun destroy() {
        glUseProgram(0)
        programs.forEach(::glDeleteProgram)
        resources.destroy()
        captureData.free()
    }

    fun bufferId(index: Int): Int = resources.buffers[index].id
}
