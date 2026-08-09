package dev.luna5ama.vibris.capture

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11C.GL_PACK_ALIGNMENT
import org.lwjgl.opengl.GL11C.GL_PACK_LSB_FIRST
import org.lwjgl.opengl.GL11C.GL_PACK_SKIP_PIXELS
import org.lwjgl.opengl.GL11C.GL_PACK_SKIP_ROWS
import org.lwjgl.opengl.GL11C.GL_PACK_SWAP_BYTES
import org.lwjgl.opengl.GL11C.GL_RGBA
import org.lwjgl.opengl.GL11C.GL_RGBA8
import org.lwjgl.opengl.GL11C.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11C.glGetInteger
import org.lwjgl.opengl.GL11C.glPixelStorei
import org.lwjgl.opengl.GL12C.GL_PACK_IMAGE_HEIGHT
import org.lwjgl.opengl.GL12C.GL_PACK_ROW_LENGTH
import org.lwjgl.opengl.GL12C.GL_PACK_SKIP_IMAGES
import org.lwjgl.opengl.GL15C.GL_STATIC_DRAW
import org.lwjgl.opengl.GL15C.glBindBuffer
import org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER
import org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER_BINDING
import org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL20C.GL_LINK_STATUS
import org.lwjgl.opengl.GL20C.glAttachShader
import org.lwjgl.opengl.GL20C.glCompileShader
import org.lwjgl.opengl.GL20C.glCreateProgram
import org.lwjgl.opengl.GL20C.glCreateShader
import org.lwjgl.opengl.GL20C.glDeleteProgram
import org.lwjgl.opengl.GL20C.glDeleteShader
import org.lwjgl.opengl.GL20C.glGetProgrami
import org.lwjgl.opengl.GL20C.glGetShaderi
import org.lwjgl.opengl.GL20C.glLinkProgram
import org.lwjgl.opengl.GL20C.glShaderSource
import org.lwjgl.opengl.GL20C.glUseProgram
import org.lwjgl.opengl.GL43C.GL_COMPUTE_SHADER
import org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43C.glBindBufferBase
import org.lwjgl.opengl.GL43C.glDispatchCompute
import org.lwjgl.opengl.GL45C.glCreateBuffers
import org.lwjgl.opengl.GL45C.glCreateTextures
import org.lwjgl.opengl.GL45C.glDeleteBuffers
import org.lwjgl.opengl.GL45C.glDeleteTextures
import org.lwjgl.opengl.GL45C.glNamedBufferData
import org.lwjgl.opengl.GL45C.glTextureStorage2D
import org.lwjgl.opengl.GL45C.glTextureSubImage2D
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GlArtifactCaptureRuntimeTest {
    @Test
    fun capturesTypedArtifactsAndRestoresPixelPackState() {
        if (!System.getProperty("vibris.runtimeTest").toBoolean()) return

        withGlContext {
            val ssbo = glCreateBuffers()
            val texture = glCreateTextures(GL_TEXTURE_2D)
            val packBuffer = glCreateBuffers()
            try {
                val bufferBytes = byteArrayOf(0, 0x7f, 0x80.toByte(), 0xff.toByte())
                glNamedBufferData(ssbo, BufferUtils.createByteBuffer(4).put(bufferBytes).flip(), GL_STATIC_DRAW)
                val bufferOutput = ByteArrayOutputStream()
                val bufferMetadata = GlArtifactCapture.captureBuffer(ssbo, bufferOutput)
                assertContentEquals(bufferBytes, bufferOutput.toByteArray())
                assertEquals(4, bufferMetadata.byteSize)
                assertEquals(ResourceCatalog.ScalarType.UINT8, bufferMetadata.scalarType)

                writeBufferFromComputeShader(ssbo)
                val gpuOutput = ByteArrayOutputStream()
                GlArtifactCapture.captureBuffer(ssbo, gpuOutput)
                val expectedGpuBytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
                    .putInt(0x12345678).array()
                assertContentEquals(expectedGpuBytes, gpuOutput.toByteArray())

                glTextureStorage2D(texture, 1, GL_RGBA8, 2, 1)
                val pixels = BufferUtils.createByteBuffer(8)
                    .put(byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte()))
                    .flip()
                glTextureSubImage2D(texture, 0, 0, 0, 2, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
                setPackState(packBuffer)

                val rawOutput = ByteArrayOutputStream()
                val rawMetadata = GlArtifactCapture.captureTexture(
                    texture,
                    0,
                    0,
                    CapturePlan.ArtifactFormat.BIN,
                    rawOutput,
                )
                assertEquals(2, rawMetadata.width)
                assertEquals(1, rawMetadata.height)
                assertEquals(4, rawMetadata.channelCount)
                assertEquals(ResourceCatalog.ScalarType.UINT8, rawMetadata.scalarType)
                assertEquals(8, rawMetadata.byteSize)
                assertEquals(rawMetadata, GlArtifactCapture.describeTexture(texture, 0))
                assertEquals(rawMetadata.byteSize, rawOutput.size().toLong())
                assertContentEquals(
                    byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte()),
                    rawOutput.toByteArray(),
                )
                assertPackState(packBuffer)

                val pngOutput = ByteArrayOutputStream()
                val pngMetadata = GlArtifactCapture.captureTexture(
                    texture,
                    0,
                    0,
                    CapturePlan.ArtifactFormat.PNG,
                    pngOutput,
                )
                assertEquals(rawMetadata, pngMetadata)
                val image = assertNotNull(ImageIO.read(ByteArrayInputStream(pngOutput.toByteArray())))
                assertEquals(2, image.width)
                assertEquals(1, image.height)
                assertEquals(0xffff0000.toInt(), image.getRGB(0, 0))
                assertEquals(0xff00ff00.toInt(), image.getRGB(1, 0))
                assertPackState(packBuffer)
            } finally {
                resetPackState()
                glDeleteBuffers(packBuffer)
                glDeleteTextures(texture)
                glDeleteBuffers(ssbo)
            }
        }
    }

    private fun setPackState(packBuffer: Int) {
        glNamedBufferData(packBuffer, 16L, GL_STATIC_DRAW)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer)
        glPixelStorei(GL_PACK_ALIGNMENT, 8)
        glPixelStorei(GL_PACK_SWAP_BYTES, 1)
        glPixelStorei(GL_PACK_LSB_FIRST, 1)
        glPixelStorei(GL_PACK_ROW_LENGTH, 17)
        glPixelStorei(GL_PACK_IMAGE_HEIGHT, 19)
        glPixelStorei(GL_PACK_SKIP_PIXELS, 3)
        glPixelStorei(GL_PACK_SKIP_ROWS, 5)
        glPixelStorei(GL_PACK_SKIP_IMAGES, 7)
    }

    private fun assertPackState(packBuffer: Int) {
        assertEquals(packBuffer, glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING))
        assertEquals(8, glGetInteger(GL_PACK_ALIGNMENT))
        assertEquals(1, glGetInteger(GL_PACK_SWAP_BYTES))
        assertEquals(1, glGetInteger(GL_PACK_LSB_FIRST))
        assertEquals(17, glGetInteger(GL_PACK_ROW_LENGTH))
        assertEquals(19, glGetInteger(GL_PACK_IMAGE_HEIGHT))
        assertEquals(3, glGetInteger(GL_PACK_SKIP_PIXELS))
        assertEquals(5, glGetInteger(GL_PACK_SKIP_ROWS))
        assertEquals(7, glGetInteger(GL_PACK_SKIP_IMAGES))
    }

    private fun resetPackState() {
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        glPixelStorei(GL_PACK_ALIGNMENT, 4)
        glPixelStorei(GL_PACK_SWAP_BYTES, 0)
        glPixelStorei(GL_PACK_LSB_FIRST, 0)
        glPixelStorei(GL_PACK_ROW_LENGTH, 0)
        glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0)
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0)
        glPixelStorei(GL_PACK_SKIP_ROWS, 0)
        glPixelStorei(GL_PACK_SKIP_IMAGES, 0)
    }

    private fun writeBufferFromComputeShader(buffer: Int) {
        val shader = glCreateShader(GL_COMPUTE_SHADER)
        val program = glCreateProgram()
        try {
            glShaderSource(shader, """
                #version 430 core
                layout(local_size_x = 1) in;
                layout(std430, binding = 0) buffer Output { uint value; } outputBuffer;
                void main() { outputBuffer.value = 0x12345678u; }
            """.trimIndent())
            glCompileShader(shader)
            check(glGetShaderi(shader, GL_COMPILE_STATUS) != 0) { "Compute shader compilation failed" }
            glAttachShader(program, shader)
            glLinkProgram(program)
            check(glGetProgrami(program, GL_LINK_STATUS) != 0) { "Compute program link failed" }
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer)
            glUseProgram(program)
            glDispatchCompute(1, 1, 1)
            glUseProgram(0)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, 0)
        } finally {
            glDeleteProgram(program)
            glDeleteShader(shader)
        }
    }

    private fun withGlContext(action: () -> Unit) {
        check(glfwInit()) { "Failed to initialize GLFW" }
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        val window = glfwCreateWindow(64, 64, "vibris-artifact-capture-test", 0L, 0L)
        check(window != 0L) { "Failed to create GLFW window" }
        try {
            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            action()
        } finally {
            GL.setCapabilities(null)
            glfwFreeCallbacks(window)
            glfwDestroyWindow(window)
            glfwTerminate()
        }
    }
}
