package dev.luna5ama.vibris.capture

import dev.luna5ama.glwrapper.ShaderProgramResourceManager
import dev.luna5ama.vibris.common.BlendState
import dev.luna5ama.vibris.common.Command
import dev.luna5ama.vibris.common.FramebufferAttachment
import dev.luna5ama.vibris.common.GraphicsPassInfo
import dev.luna5ama.vibris.common.GraphicsState
import dev.luna5ama.vibris.common.VertexAttribute
import org.lwjgl.opengl.GL11C.*
import org.lwjgl.opengl.GL14C.*
import org.lwjgl.opengl.GL15C.*
import org.lwjgl.opengl.GL20C.*
import org.lwjgl.opengl.GL30C.*
import org.lwjgl.opengl.GL31C.*
import org.lwjgl.opengl.GL32C.*
import org.lwjgl.opengl.GL40C.*
import org.lwjgl.opengl.GL43C.*
import org.lwjgl.opengl.GL45C.GL_TEXTURE_TARGET
import org.lwjgl.opengl.GL45C.glGetTextureParameteri
import org.lwjgl.system.MemoryStack

internal data class GraphicsCaptureInfo(
    val program: GraphicsProgramInfo,
    val passInfo: GraphicsPassInfo,
)

internal fun CaptureContext.captureGraphicsInfo(program: GraphicsProgramInfo): GraphicsCaptureInfo {
    glFinish()
    val programId = glGetInteger(GL_CURRENT_PROGRAM)
    val allocator = ShaderBindingAllocator()
    val shaderInfos = program.sources.map { (stage, source) ->
        ShaderSourceContext(source, allocator)
            .setIdentity(program.passName, program.programType, "${program.passName}.${stageExtension(stage)}", stage)
            .also { it.patchShaderForVulkan() }
            .toShaderInfo()
    }
    val resourceManager = ShaderProgramResourceManager(programId)
    val resources = captureShaderProgramResources(shaderInfos.mergeGraphicsResources(), resourceManager)
    return GraphicsCaptureInfo(
        program,
        GraphicsPassInfo(
            shaderIndices = shaderInfos.map(::shaderIndex),
            resources = resources,
            vertexAttributes = captureVertexAttributes(),
            framebufferAttachments = captureFramebufferAttachments(),
            drawBuffers = captureDrawBuffers(),
            state = captureGraphicsState(),
        ),
    )
}

internal fun CaptureContext.recordDrawArrays(
    program: GraphicsProgramInfo,
    mode: Int,
    first: Int,
    count: Int,
    instanceCount: Int,
) {
    commands += Command.DrawArraysCommand(mode, first, count, instanceCount, captureGraphicsInfo(program).passInfo)
}

internal fun CaptureContext.recordDrawElements(
    program: GraphicsProgramInfo,
    mode: Int,
    count: Int,
    indexType: Int,
    indexOffset: Long,
    baseVertex: Int,
    instanceCount: Int,
) {
    val indexBuffer = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING)
    check(indexBuffer != 0) { "Indexed graphics capture requires an element buffer" }
    commands += Command.DrawElementsCommand(
        mode,
        count,
        indexType,
        indexOffset,
        baseVertex,
        instanceCount,
        getBufferIndex(indexBuffer),
        captureGraphicsInfo(program).passInfo,
    )
}

internal fun CaptureContext.recordMultiDrawElements(
    program: GraphicsProgramInfo,
    mode: Int,
    counts: List<Int>,
    indexType: Int,
    indexOffsets: List<Long>,
    baseVertices: List<Int>,
) {
    require(counts.size == indexOffsets.size && counts.size == baseVertices.size)
    val indexBuffer = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING)
    check(indexBuffer != 0) { "Indexed graphics capture requires an element buffer" }
    commands += Command.MultiDrawElementsCommand(
        mode,
        counts,
        indexType,
        indexOffsets,
        baseVertices,
        getBufferIndex(indexBuffer),
        captureGraphicsInfo(program).passInfo,
    )
}

private fun CaptureContext.captureVertexAttributes(): List<VertexAttribute> = buildList {
    val program = glGetInteger(GL_CURRENT_PROGRAM)
    val attributeNames = MemoryStack.stackPush().use { stack ->
        buildMap<Int, String> {
            val activeCount = glGetProgrami(program, GL_ACTIVE_ATTRIBUTES)
            val maxNameLength = glGetProgrami(program, GL_ACTIVE_ATTRIBUTE_MAX_LENGTH)
            for (index in 0 until activeCount) {
                val name = glGetActiveAttrib(program, index, maxNameLength, stack.mallocInt(1), stack.mallocInt(1))
                put(glGetAttribLocation(program, name), name)
            }
        }
    }
    val maxAttributes = glGetInteger(GL_MAX_VERTEX_ATTRIBS)
    for (location in 0 until maxAttributes) {
        if (glGetVertexAttribi(location, GL_VERTEX_ATTRIB_ARRAY_ENABLED) == GL_FALSE) continue
        val binding = glGetVertexAttribi(location, GL_VERTEX_ATTRIB_BINDING)
        val buffer = glGetIntegeri(GL_VERTEX_BINDING_BUFFER, binding)
        check(buffer != 0) { "Enabled vertex attribute $location has no buffer" }
        add(
            VertexAttribute(
                location = location,
                name = attributeNames[location],
                bufferIndex = getBufferIndex(buffer),
                size = glGetVertexAttribi(location, GL_VERTEX_ATTRIB_ARRAY_SIZE),
                type = glGetVertexAttribi(location, GL_VERTEX_ATTRIB_ARRAY_TYPE),
                normalized = glGetVertexAttribi(location, GL_VERTEX_ATTRIB_ARRAY_NORMALIZED) != GL_FALSE,
                integer = glGetVertexAttribi(location, GL_VERTEX_ATTRIB_ARRAY_INTEGER) != GL_FALSE,
                long = glGetVertexAttribi(location, GL_VERTEX_ATTRIB_ARRAY_LONG) != GL_FALSE,
                stride = glGetIntegeri(GL_VERTEX_BINDING_STRIDE, binding),
                offset = glGetInteger64i(GL_VERTEX_BINDING_OFFSET, binding) +
                    glGetVertexAttribi(location, GL_VERTEX_ATTRIB_RELATIVE_OFFSET),
                divisor = glGetIntegeri(GL_VERTEX_BINDING_DIVISOR, binding),
            ),
        )
    }
}

private fun CaptureContext.captureFramebufferAttachments(): List<FramebufferAttachment> = buildList {
    val attachments = captureDrawBuffers().distinct().toMutableList()
    attachments += GL_DEPTH_ATTACHMENT
    attachments += GL_STENCIL_ATTACHMENT
    attachments.distinct().forEach { attachment ->
        val type = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)
        if (type == GL_NONE) return@forEach
        check(type == GL_TEXTURE) { "Graphics capture does not support renderbuffer attachment 0x${attachment.toString(16)}" }
        val texture = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
        val level = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, attachment, GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL)
        val target = glGetTextureParameteri(texture, GL_TEXTURE_TARGET)
        val layer = if (target == GL_TEXTURE_1D_ARRAY || target == GL_TEXTURE_2D_ARRAY ||
            target == GL_TEXTURE_3D || target == GL_TEXTURE_CUBE_MAP_ARRAY
        ) {
            glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, attachment, GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER)
        } else {
            -1
        }
        add(FramebufferAttachment(attachment, getImageIndex(texture), level, layer))
    }
}

private fun captureDrawBuffers(): List<Int> = buildList {
    val max = glGetInteger(GL_MAX_DRAW_BUFFERS)
    for (index in 0 until max) {
        val buffer = glGetInteger(GL_DRAW_BUFFER0 + index)
        if (buffer != GL_NONE) add(buffer)
    }
}

private fun captureGraphicsState(): GraphicsState = MemoryStack.stackPush().use { stack ->
    val ints = stack.mallocInt(4)
    glGetIntegerv(GL_VIEWPORT, ints)
    val viewport = List(4) { ints[it] }
    ints.clear()
    glGetIntegerv(GL_SCISSOR_BOX, ints)
    val scissor = List(4) { ints[it] }
    val blendCount = maxOf(1, captureDrawBuffers().size)
    val blendColorBuffer = stack.mallocFloat(4)
    glGetFloatv(GL_BLEND_COLOR, blendColorBuffer)
    val blends = List(blendCount) { index ->
        val mask = stack.malloc(4)
        glGetBooleani_v(GL_COLOR_WRITEMASK, index, mask)
        BlendState(
            enabled = glIsEnabledi(GL_BLEND, index),
            sourceRgb = glGetIntegeri(GL_BLEND_SRC_RGB, index),
            destinationRgb = glGetIntegeri(GL_BLEND_DST_RGB, index),
            sourceAlpha = glGetIntegeri(GL_BLEND_SRC_ALPHA, index),
            destinationAlpha = glGetIntegeri(GL_BLEND_DST_ALPHA, index),
            equationRgb = glGetIntegeri(GL_BLEND_EQUATION_RGB, index),
            equationAlpha = glGetIntegeri(GL_BLEND_EQUATION_ALPHA, index),
            colorMask = (if (mask[0].toInt() != 0) 1 else 0) or
                (if (mask[1].toInt() != 0) 2 else 0) or
                (if (mask[2].toInt() != 0) 4 else 0) or
                (if (mask[3].toInt() != 0) 8 else 0),
        )
    }
    GraphicsState(
        viewport = viewport,
        scissorEnabled = glIsEnabled(GL_SCISSOR_TEST),
        scissor = scissor,
        depthTest = glIsEnabled(GL_DEPTH_TEST),
        depthFunction = glGetInteger(GL_DEPTH_FUNC),
        depthWrite = glGetBoolean(GL_DEPTH_WRITEMASK),
        cullEnabled = glIsEnabled(GL_CULL_FACE),
        cullFace = glGetInteger(GL_CULL_FACE_MODE),
        frontFace = glGetInteger(GL_FRONT_FACE),
        polygonMode = glGetInteger(GL_POLYGON_MODE),
        polygonOffsetEnabled = glIsEnabled(GL_POLYGON_OFFSET_FILL),
        polygonOffsetFactor = glGetFloat(GL_POLYGON_OFFSET_FACTOR),
        polygonOffsetUnits = glGetFloat(GL_POLYGON_OFFSET_UNITS),
        lineWidth = glGetFloat(GL_LINE_WIDTH),
        blends = blends,
        blendColor = List(4) { blendColorBuffer[it] },
    )
}

internal fun List<ShaderInfo>.mergeGraphicsResources(): ShaderInfo {
    require(isNotEmpty())
    fun <T> merge(selector: (ShaderInfo) -> Map<String, T>): Map<String, T> = buildMap {
        this@mergeGraphicsResources.forEach { shader ->
            selector(shader).forEach { (name, value) ->
                val previous = putIfAbsent(name, value)
                check(previous == null || previous == value) { "Graphics resource $name differs between shader stages" }
            }
        }
    }

    return first().copy(
        uniforms = merge(ShaderInfo::uniforms),
        ssbos = merge(ShaderInfo::ssbos),
        ubos = merge(ShaderInfo::ubos),
        imageTypeOverrides = merge(ShaderInfo::imageTypeOverrides),
    )
}

private fun stageExtension(stage: String): String = when (stage) {
    "vertex" -> "vsh"
    "tesc" -> "tcs"
    "tese" -> "tes"
    "geometry" -> "gsh"
    "fragment" -> "fsh"
    else -> error("Unsupported graphics shader stage: $stage")
}
