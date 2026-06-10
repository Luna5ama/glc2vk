package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.common.CaptureData
import dev.luna5ama.glwrapper.base.glFinish
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.MemoryStack
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GLCapabilities
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

lateinit var glCapabilities: GLCapabilities

fun main(args: Array<String>) {
    check(args.isNotEmpty()) { "Expected at least 1 argument: <path to capture>" }
    val capturePath = Path(args[0])
    check(capturePath.exists()) { "Capture file does not exist: $capturePath" }
    println("Loading OpenGL replay capture from $capturePath")

        val replayFrames = args.getOrNull(1)?.toLongOrNull()

    MemoryStack {
        // region Init GLFW
        val window = run {
            println("Initializing GLFW/OpenGL context")
            glfwInit()
            GLFWErrorCallback.createPrint(System.err).set()

            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API)
            glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_NATIVE_CONTEXT_API)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, 1)
            glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, 0)
            glfwWindowHint(GLFW_DOUBLEBUFFER, GLFW_TRUE)
            glfwWindowHint(GLFW_SAMPLES, 0)
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            val width = 800
            val height = 600
            glfwCreateWindow(width, height, "glc2vk OpenGL", 0L, 0L)
        }
        // endregion

        var focused = true
        glfwSetWindowFocusCallback(window) { _, focus ->
            focused = focus
        }

        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)

        runCatching { GL.create() }
        glCapabilities = GL.createCapabilities()
        println("OpenGL context ready")

        println("Loading capture resources")
        val captureData = CaptureData.load(capturePath)
        println("Creating OpenGL replay instance")
        val replayInstance = runCatching {
            GLReplayInstance(captureData, capturePath)
        }.onFailure {
            captureData.free()
        }.getOrThrow()
        println("OpenGL replay instance ready")

        try {
            if (replayFrames != null) {
                repeat(replayFrames.toInt()) { frame ->
                    println("Executing OpenGL replay frame $frame")
                    MemoryStack {
                        replayInstance.execute()
                    }
                    glFinish()
                    println("Finished OpenGL replay frame $frame")
                }
            } else {
                while (!glfwWindowShouldClose(window)) {
                    glfwPollEvents()
                    if (focused) {
                        Thread.sleep(5)
                    } else {
                        Thread.sleep(25)
                    }
                    MemoryStack {
                        replayInstance.execute()
                    }
                    glFinish()
                }
            }
        } finally {
            println("Destroying OpenGL replay instance")
            replayInstance.destroy()
        }

        println("Destroying OpenGL context")
        GL.destroy()
        println("Destroying GLFW window")
        glfwFreeCallbacks(window)
        glfwDestroyWindow(window)

        if (replayFrames == null) {
            println("Terminating GLFW")
            glfwTerminate()
        }
        println("OpenGL replay finished")
    }
}

inline fun <R> Path.useMapped(crossinline block: (Arr) -> R): R {
    return try {
        FileChannel.open(this).use { fileChannel ->
            Arena.ofConfined().use { arena ->
                val segment = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size(), arena)
                block(Arr.wrap(segment.address(), segment.byteSize()))
            }
        }
    } catch (_: UnsupportedOperationException) {
        Files.newInputStream(this).use {
            Arena.ofConfined().use { arena ->
                val bytes = it.readAllBytes()
                val segment = arena.allocate(bytes.size.toLong(), 16)
                MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.size)
                block(Arr.wrap(segment.address(), segment.byteSize()))
            }
        }
    }
}
