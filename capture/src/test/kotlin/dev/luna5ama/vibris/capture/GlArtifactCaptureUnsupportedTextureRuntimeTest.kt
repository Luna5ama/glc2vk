package dev.luna5ama.vibris.capture

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
import org.lwjgl.opengl.GL12C.GL_TEXTURE_3D
import org.lwjgl.opengl.GL45C.glCreateTextures
import org.lwjgl.opengl.GL45C.glDeleteTextures
import kotlin.test.Test
import kotlin.test.assertNull

class GlArtifactCaptureUnsupportedTextureRuntimeTest {
    @Test
    fun returnsNoMetadataWhenTextureTargetIsUnsupported() {
        if (!System.getProperty("vibris.runtimeTest").toBoolean()) return

        check(glfwInit()) { "Failed to initialize GLFW" }
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        val window = glfwCreateWindow(64, 64, "vibris-unsupported-texture-test", 0L, 0L)
        check(window != 0L) { "Failed to create GLFW window" }
        try {
            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            val texture = glCreateTextures(GL_TEXTURE_3D)
            try {
                assertNull(GlArtifactCapture.describeTextureOrNull(texture, 0))
            } finally {
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
