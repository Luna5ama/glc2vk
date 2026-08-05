package dev.luna5ama.vibris.replay

import dev.luna5ama.vibris.common.CaptureData
import dev.luna5ama.vibris.common.Command
import dev.luna5ama.vibris.common.GraphicsPassInfo
import dev.luna5ama.vibris.common.GraphicsState
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
    private val programs = captureData.metadata.shaders.indices
        .filter { captureData.metadata.shaderMetadata(it).stage == "compute" }
        .associateWith { shaderIndex ->
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
    private val graphicsPrograms = commands.filterIsInstance<Command.GraphicsCommand>()
        .map { it.graphicsInfo.shaderIndices }
        .distinct()
        .associateWith { indices ->
            loadOpenGLGraphicsProgram(indices.map { index ->
                captureData.metadata.shaderMetadata(index).stage to shaderSourceResolver.resolve(captureData.metadata, index)
            })
        }
    private val resources = runCatching {
        GLReplayResource(captureData)
    }.onFailure {
        programs.values.forEach(::glDeleteProgram)
        graphicsPrograms.values.forEach(::glDeleteProgram)
    }.getOrThrow()
    private val graphicsVertexArray = org.lwjgl.opengl.GL30C.glGenVertexArrays()
    private val graphicsFramebuffer = org.lwjgl.opengl.GL30C.glGenFramebuffers()
    private val configuredAttachments = mutableSetOf<Int>()

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
                    val program = programs.getValue(command.passInfo.shaderIndex)
                    glUseProgram(program)
                    resources.useProgram(program)
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

                is Command.GraphicsCommand -> executeGraphics(command)
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
        programs.values.forEach(::glDeleteProgram)
        graphicsPrograms.values.forEach(::glDeleteProgram)
        org.lwjgl.opengl.GL30C.glDeleteVertexArrays(graphicsVertexArray)
        org.lwjgl.opengl.GL30C.glDeleteFramebuffers(graphicsFramebuffer)
        resources.destroy()
    }

    fun bufferId(index: Int): Int = resources.buffers[index].buffer.id

    fun textureId(index: Int): Int = resources.textureId(index)

    private fun executeGraphics(command: Command.GraphicsCommand) {
        val info = command.graphicsInfo
        val program = graphicsPrograms.getValue(info.shaderIndices)
        glUseProgram(program)
        resources.useProgram(program)
        resources.bind(info.resources)
        bindFramebuffer(info)
        bindVertexInput(info)
        applyGraphicsState(info.state)
        when (command) {
            is Command.DrawArraysCommand -> if (command.instanceCount == 1) {
                org.lwjgl.opengl.GL11C.glDrawArrays(command.mode, command.first, command.count)
            } else {
                org.lwjgl.opengl.GL31C.glDrawArraysInstanced(
                    command.mode,
                    command.first,
                    command.count,
                    command.instanceCount,
                )
            }
            is Command.DrawElementsCommand -> {
                org.lwjgl.opengl.GL15C.glBindBuffer(
                    org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER,
                    resources.bufferId(command.indexBufferIndex),
                )
                when {
                    command.instanceCount > 1 && command.baseVertex != 0 ->
                        org.lwjgl.opengl.GL32C.glDrawElementsInstancedBaseVertex(
                            command.mode,
                            command.count,
                            command.indexType,
                            command.indexOffset,
                            command.instanceCount,
                            command.baseVertex,
                        )
                    command.instanceCount > 1 -> org.lwjgl.opengl.GL31C.glDrawElementsInstanced(
                        command.mode,
                        command.count,
                        command.indexType,
                        command.indexOffset,
                        command.instanceCount,
                    )
                    command.baseVertex != 0 -> org.lwjgl.opengl.GL32C.glDrawElementsBaseVertex(
                        command.mode,
                        command.count,
                        command.indexType,
                        command.indexOffset,
                        command.baseVertex,
                    )
                    else -> org.lwjgl.opengl.GL11C.glDrawElements(
                        command.mode,
                        command.count,
                        command.indexType,
                        command.indexOffset,
                    )
                }
            }
            is Command.MultiDrawElementsCommand -> {
                org.lwjgl.opengl.GL15C.glBindBuffer(
                    org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER,
                    resources.bufferId(command.indexBufferIndex),
                )
                command.counts.indices.forEach { index ->
                    org.lwjgl.opengl.GL32C.glDrawElementsBaseVertex(
                        command.mode,
                        command.counts[index],
                        command.indexType,
                        command.indexOffsets[index],
                        command.baseVertices[index],
                    )
                }
            }
        }
    }

    private fun bindFramebuffer(info: GraphicsPassInfo) {
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, graphicsFramebuffer)
        configuredAttachments.forEach {
            org.lwjgl.opengl.GL45C.glNamedFramebufferTexture(graphicsFramebuffer, it, 0, 0)
        }
        configuredAttachments.clear()
        info.framebufferAttachments.forEach { attachment ->
            val texture = resources.textureId(attachment.imageIndex)
            if (attachment.layer >= 0) {
                org.lwjgl.opengl.GL45C.glNamedFramebufferTextureLayer(
                    graphicsFramebuffer,
                    attachment.attachment,
                    texture,
                    attachment.level,
                    attachment.layer,
                )
            } else {
                org.lwjgl.opengl.GL45C.glNamedFramebufferTexture(
                    graphicsFramebuffer,
                    attachment.attachment,
                    texture,
                    attachment.level,
                )
            }
            configuredAttachments += attachment.attachment
        }
        org.lwjgl.opengl.GL20C.glDrawBuffers(info.drawBuffers.toIntArray())
        check(
            org.lwjgl.opengl.GL30C.glCheckFramebufferStatus(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER) ==
                org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE,
        ) { "Captured graphics framebuffer is incomplete" }
    }

    private fun bindVertexInput(info: GraphicsPassInfo) {
        org.lwjgl.opengl.GL30C.glBindVertexArray(graphicsVertexArray)
        val maxAttributes = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL20C.GL_MAX_VERTEX_ATTRIBS)
        repeat(maxAttributes) { org.lwjgl.opengl.GL20C.glDisableVertexAttribArray(it) }
        info.vertexAttributes.forEach { attribute ->
            org.lwjgl.opengl.GL15C.glBindBuffer(
                org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER,
                resources.bufferId(attribute.bufferIndex),
            )
            when {
                attribute.long -> org.lwjgl.opengl.GL41C.glVertexAttribLPointer(
                    attribute.location,
                    attribute.size,
                    attribute.type,
                    attribute.stride,
                    attribute.offset,
                )
                attribute.integer -> org.lwjgl.opengl.GL30C.glVertexAttribIPointer(
                    attribute.location,
                    attribute.size,
                    attribute.type,
                    attribute.stride,
                    attribute.offset,
                )
                else -> org.lwjgl.opengl.GL20C.glVertexAttribPointer(
                    attribute.location,
                    attribute.size,
                    attribute.type,
                    attribute.normalized,
                    attribute.stride,
                    attribute.offset,
                )
            }
            org.lwjgl.opengl.GL20C.glEnableVertexAttribArray(attribute.location)
            org.lwjgl.opengl.GL33C.glVertexAttribDivisor(attribute.location, attribute.divisor)
        }
    }

    private fun applyGraphicsState(state: GraphicsState) {
        org.lwjgl.opengl.GL11C.glViewport(state.viewport[0], state.viewport[1], state.viewport[2], state.viewport[3])
        setEnabled(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST, state.scissorEnabled)
        if (state.scissorEnabled) {
            org.lwjgl.opengl.GL11C.glScissor(state.scissor[0], state.scissor[1], state.scissor[2], state.scissor[3])
        }
        setEnabled(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST, state.depthTest)
        org.lwjgl.opengl.GL11C.glDepthFunc(state.depthFunction)
        org.lwjgl.opengl.GL11C.glDepthMask(state.depthWrite)
        setEnabled(org.lwjgl.opengl.GL11C.GL_CULL_FACE, state.cullEnabled)
        org.lwjgl.opengl.GL11C.glCullFace(state.cullFace)
        org.lwjgl.opengl.GL11C.glFrontFace(state.frontFace)
        org.lwjgl.opengl.GL11C.glPolygonMode(org.lwjgl.opengl.GL11C.GL_FRONT_AND_BACK, state.polygonMode)
        setEnabled(org.lwjgl.opengl.GL11C.GL_POLYGON_OFFSET_FILL, state.polygonOffsetEnabled)
        org.lwjgl.opengl.GL11C.glPolygonOffset(state.polygonOffsetFactor, state.polygonOffsetUnits)
        org.lwjgl.opengl.GL11C.glLineWidth(state.lineWidth)
        state.blends.forEachIndexed { index, blend ->
            if (blend.enabled) org.lwjgl.opengl.GL30C.glEnablei(org.lwjgl.opengl.GL11C.GL_BLEND, index)
            else org.lwjgl.opengl.GL30C.glDisablei(org.lwjgl.opengl.GL11C.GL_BLEND, index)
            org.lwjgl.opengl.GL40C.glBlendFuncSeparatei(
                index,
                blend.sourceRgb,
                blend.destinationRgb,
                blend.sourceAlpha,
                blend.destinationAlpha,
            )
            org.lwjgl.opengl.GL40C.glBlendEquationSeparatei(index, blend.equationRgb, blend.equationAlpha)
            org.lwjgl.opengl.GL30C.glColorMaski(
                index,
                blend.colorMask and 1 != 0,
                blend.colorMask and 2 != 0,
                blend.colorMask and 4 != 0,
                blend.colorMask and 8 != 0,
            )
        }
    }

    private fun setEnabled(capability: Int, enabled: Boolean) {
        if (enabled) org.lwjgl.opengl.GL11C.glEnable(capability) else org.lwjgl.opengl.GL11C.glDisable(capability)
    }
}
