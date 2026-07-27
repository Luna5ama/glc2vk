package dev.luna5ama.vibris.replay

import dev.luna5ama.vibris.common.CaptureData
import dev.luna5ama.vibris.common.Command
import dev.luna5ama.vibris.common.ShaderSourceResolver
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
    private val shaderOverridePath: Path? = null,
    shaderPasses: Set<String> = emptySet()
) {
    private val commands = captureData.metadata.commandsForReplay()
    private val passCommands = commands.filterIsInstance<Command.PassCommand>()
    private val shaderSourceResolver = ShaderSourceResolver(capturePath, shaderOverridePath, shaderPasses)
    private val programs = List(captureData.metadata.shaders.size) { shaderIndex ->
        val resolved = shaderSourceResolver.resolve(captureData.metadata, shaderIndex)
        val program = loadOpenGLComputeProgram(resolved.source, resolved.path)
        if (resolved.isOverride) {
            val passName = captureData.metadata.shaderMetadata(shaderIndex).passName
            passCommands.filter { it.passInfo.shaderIndex == shaderIndex }.forEach {
                validateOpenGLCapturedBindings(program, passName, it)
            }
        }
        program
    }
    private val resources = runCatching {
        GLReplayResource(captureData)
    }.onFailure {
        programs.forEach(::glDeleteProgram)
    }.getOrThrow()

    fun execute() {
        glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION, 0, "Copy")
        resources.resetCapturedData()
        glMemoryBarrier(
            GL_BUFFER_UPDATE_BARRIER_BIT or
                GL_TEXTURE_FETCH_BARRIER_BIT or
                GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or
                GL_SHADER_STORAGE_BARRIER_BIT or
                GL_UNIFORM_BARRIER_BIT
        )
        glPopDebugGroup()

        glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION, 0, "Replay")
        commands.forEach { command ->
            when (command) {
                is Command.PushDebugLabelCommand -> {
                    glPushDebugGroup(GL_DEBUG_SOURCE_APPLICATION, 0, command.label)
                }

                Command.PopDebugLabelCommand -> {
                    glPopDebugGroup()
                }

                is Command.PassCommand -> {
                    glUseProgram(programs[command.passInfo.shaderIndex])
                    resources.useProgram(programs[command.passInfo.shaderIndex])
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
                }
            }
        }

        glMemoryBarrier(
            GL_SHADER_STORAGE_BARRIER_BIT or
                GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or
                GL_TEXTURE_FETCH_BARRIER_BIT or
                GL_BUFFER_UPDATE_BARRIER_BIT
        )
        glPopDebugGroup()
        if (passCommands.isNotEmpty()) {
            glDispatchCompute(0, 0, 0)
        }
        glFinish()
    }

    fun destroy() {
        glUseProgram(0)
        programs.forEach(::glDeleteProgram)
        resources.destroy()
    }

    fun bufferId(index: Int): Int = resources.buffers[index].buffer.id
}
