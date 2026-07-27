package dev.luna5ama.vibris.capture

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFails

class CaptureManagerTest {
    private val idle = CaptureStatus(false, false, false, null, null)
    private val pending = CaptureStatus(true, false, false, null, null)

    @Test
    fun exposesIdleStatusAndTimestampedDefaultPath() {
        val manager = CaptureManager()

        assertEquals(idle, manager.status())
        val path = CaptureManager.defaultOutputPath("lighting")
        assertEquals(Path.of("vibris"), path.parent)
        assertTrue(path.fileName.toString().matches(Regex("lighting-\\d{8}-\\d{6}")))
    }

    @Test
    fun queuesSingleAndCaseInsensitiveMultiCaptures() {
        val temp = createTempDirectory("vibris-capture-manager-test")
        try {
            CaptureManager().also {
                it.prepareSingleCapture(temp.resolve("single"), "lighting")
                assertEquals(pending, it.status())
            }

            listOf("prepare", "BEGIN", "Deferred", "composite").forEach { type ->
                CaptureManager().also {
                    it.prepareMultiCapture(temp.resolve(type), type)
                    assertEquals(pending, it.status())
                }
            }
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsupportedMultiCaptureTypeExactly() {
        val error = assertFails {
            CaptureManager().prepareMultiCapture(Path.of("unused"), "invalid")
        }

        assertEquals("Unsupported capturemulti type: invalid", error.message)
    }

    @Test
    fun leavesUnqueuedDispatchesAlone() {
        val manager = CaptureManager()

        manager.startFrame()
        assertFalse(manager.dispatchCompute("void main() {}", "lighting", 1, 2, 3))
        assertFalse(manager.dispatchComputeIndirect("void main() {}", "lighting", 16L))
        manager.endFrame()
        assertEquals(idle, manager.status())
    }
}