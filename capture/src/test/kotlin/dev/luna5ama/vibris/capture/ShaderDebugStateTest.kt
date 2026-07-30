package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.function.Consumer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ShaderDebugStateTest {
    @Test
    fun retainsTheNewestOneHundredErrors() {
        val temp = createTempDirectory("vibris-shader-debug-state")
        try {
            val control = ShaderDebugControl(EmptyHost(temp), EmptyDumper)
            repeat(101) { index ->
                control.recordError("type", "file", "message", "trace", index.toLong())
            }
            val errors = control.errorsJson()["errors"]!!.jsonArray
            assertEquals(100, errors.size)
            assertEquals("1", errors.first().jsonObject["timestamp"]!!.jsonPrimitive.content)
            assertEquals("100", errors.last().jsonObject["timestamp"]!!.jsonPrimitive.content)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun timingHistoryReturnsPercentilesForTheNewestSamples() {
        val history = TimingHistory(3)
        listOf(10L, 20L, 30L, 40L).forEach(history::add)
        val stats = history.stats()
        assertEquals(30, stats.average)
        assertEquals(21, stats.p5)
        assertEquals(39, stats.p95)
        assertEquals(30, stats.p50)
    }

    private class EmptyHost(private val root: Path) : ShaderDebugHost {
        override fun shaderPackName(): String? = null
        override fun reloadShaders() = Unit
        override fun captureScreenshot(onSaved: Consumer<Path>) = Unit
        override fun gameDirectory() = root
        override fun debugShadersEnabled() = false
        override fun storageBuffers() = emptyList<StorageBufferInfo>()
        override fun textureCatalog() = TextureCatalog(emptyList(), emptyList())
        override fun resolveTexture(name: String): Int? = null
    }

    private object EmptyDumper : ShaderDebugResourceDumper {
        override fun dumpStorageBuffer(buffer: StorageBufferInfo, output: Path) = JsonObject(emptyMap())
        override fun dumpTexture(textureId: Int, output: Path, raw: Boolean) = JsonObject(emptyMap())
    }
}
