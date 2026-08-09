package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShaderDebugStateTest {
    @Test
    fun retainsTheNewestOneHundredErrors() {
        val temp = createTempDirectory("vibris-shader-debug-state")
        try {
            val control = ShaderDebugControl(EmptyHost(temp))
            repeat(101) { index ->
                control.recordError("type", "file", "message", "trace", index.toLong())
            }
            val inspection = control.inspect()
            val errors = inspection["errors"]!!.jsonArray
            assertFalse(inspection["pack_loaded"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(100, errors.size)
            assertEquals("1", errors.first().jsonObject["timestamp"]!!.jsonPrimitive.content)
            assertEquals("100", errors.last().jsonObject["timestamp"]!!.jsonPrimitive.content)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun timingHistoryReturnsPercentilesForEveryCapturedSample() {
        val history = TimingHistory()
        (1L..100L).forEach(history::add)
        val stats = history.stats()
        assertEquals(50, stats.average)
        assertEquals(6, stats.p5)
        assertEquals(95, stats.p95)
        assertEquals(51, stats.p50)
    }

    @Test
    fun gpuMetricsCaptureWaitsForTheRequestedFutureFrames() {
        val control = ShaderDebugControl(EmptyHost(Path.of(".")))

        val result = control.captureMetrics(2).toCompletableFuture()
        control.tickFrame()
        assertFalse(result.isDone)
        control.tickFrame()

        assertTrue(result.isDone)
        assertEquals(emptySet(), result.join()["gpuTimings"]!!.jsonObject.keys)
    }

    @Test
    fun patchedShaderIdentityIsStableUntilOutputChanges() {
        val temp = createTempDirectory("vibris-patched-identity")
        try {
            val output = Files.createDirectories(temp.resolve("patched_shaders"))
            Files.writeString(output.resolve("composite.fsh"), "first")
            val control = ShaderDebugControl(DebugHost(temp))

            val first = control.inspect().getValue("patched_shader").jsonObject
            val retry = control.inspect().getValue("patched_shader").jsonObject
            Files.writeString(output.resolve("composite.fsh"), "second")
            val changed = control.inspect().getValue("patched_shader").jsonObject

            assertTrue(first.getValue("available").jsonPrimitive.content.toBoolean())
            assertEquals(first.getValue("sha256"), retry.getValue("sha256"))
            assertEquals("1", retry.getValue("generation").jsonPrimitive.content)
            assertFalse(first.getValue("sha256") == changed.getValue("sha256"))
            assertEquals("2", changed.getValue("generation").jsonPrimitive.content)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    private class EmptyHost(private val root: Path) : ShaderDebugHost {
        override fun shaderPackName(): String? = null
        override fun reloadShaders() = Unit
        override fun gameDirectory() = root
        override fun debugShadersEnabled() = false
        override fun storageBuffers() = emptyList<StorageBufferInfo>()
        override fun textureCatalog() = TextureCatalog(emptyList())
        override fun resolveTexture(name: String): Int? = null
    }

    private class DebugHost(private val root: Path) : ShaderDebugHost {
        override fun shaderPackName() = "vibris"
        override fun reloadShaders() = Unit
        override fun gameDirectory() = root
        override fun debugShadersEnabled() = true
        override fun storageBuffers() = emptyList<StorageBufferInfo>()
        override fun textureCatalog() = TextureCatalog(emptyList())
        override fun resolveTexture(name: String): Int? = null
    }
}