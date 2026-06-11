package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.capture.ShaderSourceContext
import dev.luna5ama.glc2vk.capture.beginGlCapture
import dev.luna5ama.glc2vk.capture.captureGlDispatchCompute
import dev.luna5ama.glc2vk.capture.endGlCapture
import dev.luna5ama.glc2vk.capture.glDebugGroupCaptureAware
import dev.luna5ama.glc2vk.common.CaptureData
import dev.luna5ama.glc2vk.common.Command
import dev.luna5ama.glwrapper.base.GL_COMPILE_STATUS
import dev.luna5ama.glwrapper.base.GL_COMPUTE_SHADER
import dev.luna5ama.glwrapper.base.GL_DYNAMIC_STORAGE_BIT
import dev.luna5ama.glwrapper.base.GL_INFO_LOG_LENGTH
import dev.luna5ama.glwrapper.base.GL_LINK_STATUS
import dev.luna5ama.glwrapper.base.GL_SHADER_STORAGE_BARRIER_BIT
import dev.luna5ama.glwrapper.base.GL_SHADER_STORAGE_BUFFER
import dev.luna5ama.glwrapper.base.glAttachShader
import dev.luna5ama.glwrapper.base.glBindBufferBase
import dev.luna5ama.glwrapper.base.glCompileShader
import dev.luna5ama.glwrapper.base.glCreateProgram
import dev.luna5ama.glwrapper.base.glCreateShader
import dev.luna5ama.glwrapper.base.glDeleteProgram
import dev.luna5ama.glwrapper.base.glDeleteShader
import dev.luna5ama.glwrapper.base.glDetachShader
import dev.luna5ama.glwrapper.base.glFinish
import dev.luna5ama.glwrapper.base.glGetNamedBufferSubData
import dev.luna5ama.glwrapper.base.glGetProgramInfoLog
import dev.luna5ama.glwrapper.base.glGetProgrami
import dev.luna5ama.glwrapper.base.glGetShaderInfoLog
import dev.luna5ama.glwrapper.base.glGetShaderi
import dev.luna5ama.glwrapper.base.glLinkProgram
import dev.luna5ama.glwrapper.base.glMemoryBarrier
import dev.luna5ama.glwrapper.base.glShaderSource
import dev.luna5ama.glwrapper.base.glUseProgram
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.MemoryStack
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.GLFW_CLIENT_API
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_CREATION_API
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_NATIVE_CONTEXT_API
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_API
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.opengl.GL
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ReplayGLRuntimeTest {
    @Test
    fun captureAndReplayTwoDispatches() {
        if (!System.getProperty("glc2vk.runtimeTest").toBoolean()) {
            return
        }

        val outputPath = Path("build/runtime-capture-test/gl-two-dispatch")
        if (outputPath.exists()) {
            outputPath.toFile().deleteRecursively()
        }

        val source = """
            #version 460 core
            layout(local_size_x = 1) in;
            layout(std430, binding = 0) buffer Data {
                uint values[];
            };
            void main() {
                values[gl_GlobalInvocationID.x] += 1u;
            }
        """.trimIndent()
        val shaderInfo = ShaderSourceContext(source).run {
            patchShaderForVulkan()
            toShaderInfo()
        }

        check(glfwInit()) { "Failed to initialize GLFW" }
        val window = try {
            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API)
            glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_NATIVE_CONTEXT_API)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            glfwCreateWindow(64, 64, "glc2vk-runtime-test", 0L, 0L).also {
                check(it != 0L) { "Failed to create GLFW window" }
            }
        } catch (t: Throwable) {
            glfwTerminate()
            throw t
        }

        try {
            glfwMakeContextCurrent(window)
            GL.createCapabilities()

            val program = compileComputeProgram(source)
            val buffer = BufferObject.Immutable()
            val initialData = Arr.malloc(16L)
            try {
                repeat(4) { initialData.ptr.setInt((it * 4).toLong(), it + 1) }
                buffer.allocate(16L, GL_DYNAMIC_STORAGE_BIT)
                buffer.upload(0L, 16L, initialData.ptr)
                buffer.label = "runtime-test-data"

                glUseProgram(program)
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer.id)

                beginGlCapture(outputPath)
                glDebugGroupCaptureAware("runtime-test-group") {
                    captureGlDispatchCompute(shaderInfo, 4, 1, 1)
                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)
                    captureGlDispatchCompute(shaderInfo, 4, 1, 1)
                }.also {
                    endGlCapture().join()
                }
                assertTrue(outputPath.resolve("shader_0.comp.spv").exists(), "Expected Vulkan SPIR-V shader output")
                glFinish()
            } finally {
                initialData.free()
                buffer.destroy()
                glUseProgram(0)
                glDeleteProgram(program)
            }

            val metadataOnly = CaptureData.load(outputPath)
            val commands = metadataOnly.metadata.commandsForReplay()
            assertEquals(2, commands.size)
            commands.forEach {
                assertIs<Command.DispatchCommand>(it)
                assertEquals(listOf("runtime-test-group"), it.debugLabels)
            }
            metadataOnly.free()

            val replay = GLReplayInstance(captureData, outputPath)
            try {
                replay.execute()
                val bufferIndex = commands.first().storageBufferBindings().first { it.name == "Data" }.bufferIndex
                MemoryStack {
                    val readback = malloc(16L)
                    glGetNamedBufferSubData(replay.bufferId(bufferIndex), 0L, 16L, readback.ptr)
                    assertEquals(3, readback.ptr.getInt(0L))
                    assertEquals(4, readback.ptr.getInt(4L))
                    assertEquals(5, readback.ptr.getInt(8L))
                    assertEquals(6, readback.ptr.getInt(12L))
                }
            } finally {
                replay.destroy()
            }
        } finally {
            GL.destroy()
            glfwFreeCallbacks(window)
            glfwDestroyWindow(window)
            glfwTerminate()
        }
    }

    private fun compileComputeProgram(source: String): Int {
        val shader = glCreateShader(GL_COMPUTE_SHADER)
        glShaderSource(shader, source)
        glCompileShader(shader)
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            val log = glGetShaderInfoLog(shader, glGetShaderi(shader, GL_INFO_LOG_LENGTH))
            glDeleteShader(shader)
            error("Failed to compile runtime test shader:\n$log")
        }

        val program = glCreateProgram()
        glAttachShader(program, shader)
        glLinkProgram(program)
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
            val log = glGetProgramInfoLog(program, glGetProgrami(program, GL_INFO_LOG_LENGTH))
            glDetachShader(program, shader)
            glDeleteShader(shader)
            glDeleteProgram(program)
            error("Failed to link runtime test shader:\n$log")
        }

        glDetachShader(program, shader)
        glDeleteShader(shader)
        return program
    }
}
