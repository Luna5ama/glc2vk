package dev.luna5ama.vibris.capture

import dev.vibris.api.DebugControlCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.function.Consumer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CaptureDebugControlTest {
    @Test
    fun queuesPassCaptureInsideTheGameDirectory() {
        val root = createTempDirectory("vibris-debug-control")
        try {
            val manager = CaptureManager()
            val control = CaptureDebugControl(root, manager, ShaderDebugControl(EmptyHost(root), EmptyDumper))

            val result = Json.parseToJsonElement(
                control.execute(DebugControlCommand.CapturePass("composite", "vibris/test-capture")),
            ).jsonObject

            assertTrue(manager.status().pending)
            assertEquals(root.resolve("vibris/test-capture").toString(), result["path"]!!.jsonPrimitive.content)
            assertFailsWith<IllegalArgumentException> {
                control.execute(DebugControlCommand.CapturePass("composite", "../outside"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
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
