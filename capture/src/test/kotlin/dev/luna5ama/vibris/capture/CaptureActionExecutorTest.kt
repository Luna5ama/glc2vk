package dev.luna5ama.vibris.capture

import dev.vibris.api.RuntimeAction
import kotlinx.serialization.json.Json
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
            val executor = CaptureActionExecutor(root, manager, ShaderDebugControl(EmptyHost(root)))

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

    @Test
    fun acceptsCoreOwnedAbsoluteCapturePath() {
        val root = createTempDirectory("vibris-action-game")
        val replayRoot = createTempDirectory("vibris-action-replay")
        try {
            val manager = CaptureManager()
            val executor = CaptureActionExecutor(root, manager, ShaderDebugControl(EmptyHost(root)))
            val target = replayRoot.resolve("job-capture").toAbsolutePath().normalize()

            val result = Json.parseToJsonElement(
                executor.execute(RuntimeAction.CaptureMulti("composite", target.toString()))
                    .toCompletableFuture().join(),
            ).jsonObject

            assertTrue(manager.status().pending)
            assertEquals(target.toString(), result["path"]!!.jsonPrimitive.content)
        } finally {
            root.toFile().deleteRecursively()
            replayRoot.toFile().deleteRecursively()
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
}
