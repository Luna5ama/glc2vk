package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.jsonPrimitive
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
import org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11C.GL_PACK_SKIP_PIXELS
import org.lwjgl.opengl.GL11C.GL_PACK_SWAP_BYTES
import org.lwjgl.opengl.GL11C.GL_RGBA
import org.lwjgl.opengl.GL11C.GL_RGBA8
import org.lwjgl.opengl.GL15C.GL_STATIC_DRAW
import org.lwjgl.opengl.GL11C.GL_TEXTURE_2D
import org.lwjgl.opengl.GL12C.GL_TEXTURE_3D
import org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL11C.glClear
import org.lwjgl.opengl.GL11C.glGetInteger
import org.lwjgl.opengl.GL11C.glPixelStorei
import org.lwjgl.opengl.GL45C.glCreateBuffers
import org.lwjgl.opengl.GL45C.glCreateTextures
import org.lwjgl.opengl.GL45C.glDeleteBuffers
import org.lwjgl.opengl.GL45C.glDeleteTextures
import org.lwjgl.opengl.GL45C.glFinish
import org.lwjgl.opengl.GL45C.glNamedBufferData
import org.lwjgl.opengl.GL45C.glTextureStorage2D
import org.lwjgl.opengl.GL45C.glTextureStorage3D
import org.lwjgl.opengl.GL45C.glTextureSubImage2D
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShaderDebugRuntimeTest {
    @Test
    fun dumpsRealGlResourcesAndCollectsGpuTiming() {
        if (!System.getProperty("vibris.runtimeTest").toBoolean()) return

        withGlContext {
            val temp = createTempDirectory("vibris-shader-debug-gl")
            val buffer = glCreateBuffers()
            val texture = glCreateTextures(GL_TEXTURE_2D)
            val volume = glCreateTextures(GL_TEXTURE_3D)
            try {
                val bufferBytes = byteArrayOf(0, 0x7f, 0x80.toByte(), 0xff.toByte())
                glNamedBufferData(buffer, BufferUtils.createByteBuffer(4).put(bufferBytes).flip(), GL_STATIC_DRAW)
                val ssboPath = temp.resolve("ssbo.bin")
                GlShaderDebugResourceDumper.dumpStorageBuffer(StorageBufferInfo(3, buffer), ssboPath)
                assertContentEquals(bufferBytes, Files.readAllBytes(ssboPath))

                glTextureStorage2D(texture, 1, GL_RGBA8, 2, 1)
                val pixels = BufferUtils.createByteBuffer(8)
                    .put(byteArrayOf(255.toByte(), 0, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte()))
                    .flip()
                glTextureSubImage2D(texture, 0, 0, 0, 2, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
                glPixelStorei(GL_PACK_SKIP_PIXELS, 1)
                glPixelStorei(GL_PACK_SWAP_BYTES, 1)
                val rawPath = temp.resolve("texture.bin")
                val raw = GlShaderDebugResourceDumper.dumpTexture(texture, rawPath, true)
                assertEquals(32L, Files.size(rawPath))
                assertEquals(32, raw["totalBytes"]!!.jsonPrimitive.content.toInt())
                assertEquals(1, glGetInteger(GL_PACK_SKIP_PIXELS))
                assertEquals(1, glGetInteger(GL_PACK_SWAP_BYTES))

                val pngPath = temp.resolve("texture.png")
                GlShaderDebugResourceDumper.dumpTexture(texture, pngPath, false)
                assertTrue(Files.size(pngPath) > 0)

                glTextureStorage3D(volume, 1, GL_RGBA8, 1, 1, 2)
                val volumeResult = GlShaderDebugResourceDumper.dumpTexture(
                    volume,
                    temp.resolve("volume.png"),
                    false
                )
                assertTrue(volumeResult["path"]!!.jsonPrimitive.content.endsWith("volume_layer*.png"))
                assertEquals(2, volumeResult["layers"]!!.jsonPrimitive.content.toInt())
                assertTrue(Files.isRegularFile(temp.resolve("volume_layer0.png")))
                assertTrue(Files.isRegularFile(temp.resolve("volume_layer1.png")))

                val metrics = GpuTimingMetrics()
                metrics.begin("test_draw")
                glClear(GL_COLOR_BUFFER_BIT)
                metrics.end()
                glFinish()
                metrics.begin("drain")
                metrics.end()
                assertTrue(metrics.snapshot().getValue("test_draw").samples.single() >= 0)
            } finally {
                glPixelStorei(GL_PACK_SKIP_PIXELS, 0)
                glPixelStorei(GL_PACK_SWAP_BYTES, 0)
                glDeleteTextures(volume)
                glDeleteTextures(texture)
                glDeleteBuffers(buffer)
                temp.toFile().deleteRecursively()
            }
        }
    }

    private fun withGlContext(action: () -> Unit) {
        check(glfwInit()) { "Failed to initialize GLFW" }
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        val window = glfwCreateWindow(64, 64, "vibris-shader-debug-test", 0L, 0L)
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