package dev.luna5ama.vibris.replay

import dev.luna5ama.vibris.capture.GraphicsProgramRegistry
import dev.luna5ama.vibris.capture.beginGlCapture
import dev.luna5ama.vibris.capture.captureGlDrawArrays
import dev.luna5ama.vibris.capture.captureGlMultiDrawElementsBaseVertex
import dev.luna5ama.vibris.capture.endGlCapture
import dev.luna5ama.vibris.common.CaptureData
import dev.luna5ama.vibris.common.Command
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11C.*
import org.lwjgl.opengl.GL20C.*
import org.lwjgl.opengl.GL30C.*
import org.lwjgl.opengl.GL45C.*
import org.lwjgl.system.MemoryUtil
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphicsReplayGLRuntimeTest {
    @Test
    fun capturesAndReplaysFullscreenDraw() {
        if (!System.getProperty("vibris.runtimeTest").toBoolean()) return
        val output = Path("build/runtime-capture-test/gl-fullscreen-draw")
        if (output.exists()) output.toFile().deleteRecursively()
        check(glfwInit())
        val window = createWindow()
        try {
            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            val vertex = """
                #version 460 core
                layout(location = 0) in vec2 Position;
                void main() { gl_Position = vec4(Position, 0.0, 1.0); }
            """.trimIndent()
            val fragment = """
                #version 460 core
                layout(location = 0) out vec4 color;
                void main() { color = vec4(1.0, 0.25, 0.0, 1.0); }
            """.trimIndent()
            val program = compileProgram(vertex, fragment)
            val vertices = MemoryUtil.memAllocFloat(6)
            vertices.put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)).flip()
            val vertexBuffer = glCreateBuffers()
            val vertexArray = glCreateVertexArrays()
            val texture = glCreateTextures(GL_TEXTURE_2D)
            val framebuffer = glCreateFramebuffers()
            try {
                glNamedBufferStorage(vertexBuffer, vertices, 0)
                glVertexArrayVertexBuffer(vertexArray, 0, vertexBuffer, 0L, 8)
                glEnableVertexArrayAttrib(vertexArray, 0)
                glVertexArrayAttribFormat(vertexArray, 0, 2, GL_FLOAT, false, 0)
                glVertexArrayAttribBinding(vertexArray, 0, 0)
                glTextureStorage2D(texture, 1, GL_RGBA8, 4, 4)
                glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, texture, 0)
                glNamedFramebufferDrawBuffer(framebuffer, GL_COLOR_ATTACHMENT0)
                assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER))
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
                glBindVertexArray(vertexArray)
                glUseProgram(program)
                glViewport(0, 0, 4, 4)
                GraphicsProgramRegistry.register(program, "composite", vertex, null, null, null, fragment)
                beginGlCapture(output)
                captureGlDrawArrays(GL_TRIANGLES, 0, 3, 1)
                endGlCapture().join()
            } finally {
                GraphicsProgramRegistry.unregister(program)
                glDeleteFramebuffers(framebuffer)
                glDeleteTextures(texture)
                glDeleteVertexArrays(vertexArray)
                glDeleteBuffers(vertexBuffer)
                glDeleteProgram(program)
                MemoryUtil.memFree(vertices)
            }

            val data = CaptureData.load(output)
            val draw = data.metadata.commands.single() as Command.DrawArraysCommand
            assertEquals(3, draw.count)
            val colorAttachment = draw.graphicsInfo.framebufferAttachments.single {
                it.attachment == GL_COLOR_ATTACHMENT0
            }
            val replay = GLReplayInstance(data, output)
            try {
                replay.execute()
                val pixels = MemoryUtil.memAlloc(4 * 4 * 4)
                try {
                    org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER, 0)
                    glGetTextureImage(replay.textureId(colorAttachment.imageIndex), 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
                    assertEquals(255, pixels.get(0).toInt() and 0xff)
                    assertTrue((pixels.get(1).toInt() and 0xff) in 63..64)
                    assertEquals(0, pixels.get(2).toInt() and 0xff)
                    assertEquals(255, pixels.get(3).toInt() and 0xff)
                } finally {
                    MemoryUtil.memFree(pixels)
                }
            } finally {
                replay.destroy()
            }
        } finally {
            GL.setCapabilities(null)
            glfwMakeContextCurrent(0L)
            glfwFreeCallbacks(window)
            glfwDestroyWindow(window)
            glfwTerminate()
        }
    }

    @Test
    fun capturesAndReplaysMultiDrawElements() {
        if (!System.getProperty("vibris.runtimeTest").toBoolean()) return
        val output = Path("build/runtime-capture-test/gl-multi-draw")
        if (output.exists()) output.toFile().deleteRecursively()
        check(glfwInit())
        val window = createWindow()
        try {
            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            val vertex = """
                #version 460 core
                layout(location = 0) in vec2 Position;
                void main() { gl_Position = vec4(Position, 0.0, 1.0); }
            """.trimIndent()
            val fragment = """
                #version 460 core
                layout(location = 0) out vec4 color;
                void main() { color = vec4(0.0, 0.5, 1.0, 1.0); }
            """.trimIndent()
            val program = compileProgram(vertex, fragment)
            val vertices = MemoryUtil.memAllocFloat(6).put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)).flip()
            val indices = MemoryUtil.memAllocInt(6).put(intArrayOf(0, 1, 2, 0, 1, 2)).flip()
            val counts = MemoryUtil.memAllocInt(2).put(intArrayOf(3, 3)).flip()
            val offsets = MemoryUtil.memAllocPointer(2).put(0L).put(12L).flip()
            val baseVertices = MemoryUtil.memAllocInt(2).put(intArrayOf(0, 0)).flip()
            val vertexBuffer = glCreateBuffers()
            val indexBuffer = glCreateBuffers()
            val vertexArray = glCreateVertexArrays()
            val texture = glCreateTextures(GL_TEXTURE_2D)
            val framebuffer = glCreateFramebuffers()
            try {
                glNamedBufferStorage(vertexBuffer, vertices, 0)
                glNamedBufferStorage(indexBuffer, indices, 0)
                glVertexArrayVertexBuffer(vertexArray, 0, vertexBuffer, 0L, 8)
                glVertexArrayElementBuffer(vertexArray, indexBuffer)
                glEnableVertexArrayAttrib(vertexArray, 0)
                glVertexArrayAttribFormat(vertexArray, 0, 2, GL_FLOAT, false, 0)
                glVertexArrayAttribBinding(vertexArray, 0, 0)
                glTextureStorage2D(texture, 1, GL_RGBA8, 4, 4)
                glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, texture, 0)
                glNamedFramebufferDrawBuffer(framebuffer, GL_COLOR_ATTACHMENT0)
                assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER))
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
                glBindVertexArray(vertexArray)
                glUseProgram(program)
                glViewport(0, 0, 4, 4)
                GraphicsProgramRegistry.register(program, "shadow", vertex, null, null, null, fragment)
                beginGlCapture(output)
                captureGlMultiDrawElementsBaseVertex(
                    GL_TRIANGLES,
                    MemoryUtil.memAddress(counts),
                    GL_UNSIGNED_INT,
                    MemoryUtil.memAddress(offsets),
                    2,
                    MemoryUtil.memAddress(baseVertices),
                )
                endGlCapture().join()
            } finally {
                GraphicsProgramRegistry.unregister(program)
                glDeleteFramebuffers(framebuffer)
                glDeleteTextures(texture)
                glDeleteVertexArrays(vertexArray)
                glDeleteBuffers(indexBuffer)
                glDeleteBuffers(vertexBuffer)
                glDeleteProgram(program)
                MemoryUtil.memFree(baseVertices)
                MemoryUtil.memFree(offsets)
                MemoryUtil.memFree(counts)
                MemoryUtil.memFree(indices)
                MemoryUtil.memFree(vertices)
            }

            val data = CaptureData.load(output)
            val draw = data.metadata.commands.single() as Command.MultiDrawElementsCommand
            assertEquals(listOf(3, 3), draw.counts)
            assertEquals(listOf(0L, 12L), draw.indexOffsets)
            val replay = GLReplayInstance(data, output)
            try {
                replay.execute()
            } finally {
                replay.destroy()
            }
        } finally {
            GL.setCapabilities(null)
            glfwMakeContextCurrent(0L)
            glfwFreeCallbacks(window)
            glfwDestroyWindow(window)
            glfwTerminate()
        }
    }

    private fun createWindow(): Long {
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API)
        glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_NATIVE_CONTEXT_API)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        return glfwCreateWindow(4, 4, "vibris-graphics-runtime-test", 0L, 0L).also { check(it != 0L) }
    }

    private fun compileProgram(vertex: String, fragment: String): Int {
        val shaders = listOf(GL_VERTEX_SHADER to vertex, GL_FRAGMENT_SHADER to fragment).map { (stage, source) ->
            glCreateShader(stage).also { shader ->
                glShaderSource(shader, source)
                glCompileShader(shader)
                check(glGetShaderi(shader, GL_COMPILE_STATUS) != 0) { glGetShaderInfoLog(shader) }
            }
        }
        return glCreateProgram().also { program ->
            shaders.forEach { glAttachShader(program, it) }
            glLinkProgram(program)
            check(glGetProgrami(program, GL_LINK_STATUS) != 0) { glGetProgramInfoLog(program) }
            shaders.forEach {
                glDetachShader(program, it)
                glDeleteShader(it)
            }
        }
    }
}
