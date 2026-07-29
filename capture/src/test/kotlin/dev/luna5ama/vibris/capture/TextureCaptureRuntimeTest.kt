package dev.luna5ama.vibris.capture

import dev.luna5ama.vibris.common.VkFormat
import dev.luna5ama.vibris.common.VkImageViewType
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11C.*
import org.lwjgl.opengl.GL45C.*
import kotlin.test.Test
import kotlin.test.assertEquals

class TextureCaptureRuntimeTest {
    @Test
    fun capturesSampler1DWithInheritedPixelPackSkip() {
        if (!System.getProperty("vibris.runtimeTest").toBoolean()) return

        check(glfwInit()) { "Failed to initialize GLFW" }
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        val window = glfwCreateWindow(64, 64, "vibris-texture-capture-test", 0L, 0L)
        check(window != 0L) { "Failed to create GLFW window" }

        try {
            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            val texture = glCreateTextures(GL_TEXTURE_1D)
            val context = CaptureContext()
            try {
                glTextureStorage1D(texture, 1, GL_RGBA8, 4089)
                glPixelStorei(GL_PACK_SKIP_PIXELS, 1)

                val imageIndex = context.getImageIndex(texture)

                assertEquals(GL_NO_ERROR, glGetError(), "capture must ignore inherited pixel-pack skips")
                assertEquals(1, glGetInteger(GL_PACK_SKIP_PIXELS), "capture must restore pixel-pack state")
                val metadata = context.imageMetadata[imageIndex]
                assertEquals(4089, metadata.width)
                assertEquals(1, metadata.height)
                assertEquals(1, metadata.depth)
                assertEquals(VkImageViewType.`1D`, metadata.viewType)
                assertEquals(VkFormat.R8G8B8A8_UNORM, metadata.format)
                assertEquals(listOf(16356L), metadata.levelDataSizes)
            } finally {
                context.destroy()
                glPixelStorei(GL_PACK_SKIP_PIXELS, 0)
                glDeleteTextures(texture)
            }
        } finally {
            GL.setCapabilities(null)
            glfwFreeCallbacks(window)
            glfwDestroyWindow(window)
            glfwTerminate()
        }
    }
}