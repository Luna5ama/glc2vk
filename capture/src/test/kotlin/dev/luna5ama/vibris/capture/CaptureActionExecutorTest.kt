package dev.luna5ama.vibris.capture

import dev.vibris.api.RuntimeAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CaptureActionExecutorTest {
    @Test
    fun queuesPassCaptureInsideTheGameDirectory() {
        val root = createTempDirectory("vibris-action-executor")
        try {
            val manager = CaptureManager()
            val executor = CaptureActionExecutor(root, manager, ShaderDebugControl(EmptyHost(root), EmptyDumper))

            val result = Json.parseToJsonElement(
                executor.execute(RuntimeAction.CapturePass("composite", "vibris/test-capture"))
                    .toCompletableFuture().join(),
            ).jsonObject

            assertTrue(manager.status().pending)
            assertEquals(root.resolve("vibris/test-capture").toString(), result["path"]!!.jsonPrimitive.content)
            assertFailsWith<IllegalArgumentException> {
                executor.execute(RuntimeAction.CapturePass("composite", "../outside"))
                    .toCompletableFuture().join()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class EmptyHost(private val root: Path) : ShaderDebugHost {
        override fun shaderPackName(): String? = null
        override fun reloadShaders() = Unit
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
