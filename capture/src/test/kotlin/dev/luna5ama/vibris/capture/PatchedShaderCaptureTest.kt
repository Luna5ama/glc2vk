package dev.luna5ama.vibris.capture

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatchedShaderCaptureTest {
    @Test
    fun waitsForWritesAndCopiesStableSortedSnapshot() {
        val gameDirectory = Files.createTempDirectory("vibris-patched-shaders-")
        val patched = Files.createDirectory(gameDirectory.resolve("patched_shaders"))
        Files.writeString(patched.resolve("002_begin.json"), "{\"stage\":2}")
        var awaited = false
        val host = object : ShaderDebugHost {
            override fun shaderPackName() = "test"
            override fun reloadShaders() = Unit
            override fun gameDirectory() = gameDirectory
            override fun debugShadersEnabled() = true
            override fun storageBuffers() = emptyList<StorageBufferInfo>()
            override fun textureCatalog() = TextureCatalog(emptyList())
            override fun resolveTexture(name: String): Int? = null
            override fun awaitPatchedShaderWrites() {
                awaited = true
                Files.writeString(patched.resolve("001_begin.vsh"), "vertex")
            }
        }
        val sink = MemorySink()

        val result = PatchedShaderCapture.capture(host, "patched", sink, 42, CancellationToken.none())

        assertTrue(awaited)
        assertContentEquals("vertex".encodeToByteArray(), sink.files.getValue("patched.001_begin.vsh"))
        assertContentEquals("{\"stage\":2}".encodeToByteArray(), sink.files.getValue("patched.002_begin.json"))
        val group = result.groups.single()
        assertEquals("patched", group.name)
        assertEquals(listOf(0, 1), group.artifacts.map { it.subresourceIndex })
        assertEquals(listOf(CapturePlan.ArtifactFormat.TEXT, CapturePlan.ArtifactFormat.JSON),
            group.artifacts.map { it.format })
        assertEquals(17, group.resource.byteSize)
        assertEquals(42, group.resource.frameId)
    }

    private class MemorySink : ArtifactSink {
        val files = linkedMapOf<String, ByteArray>()
        override fun open(artifactName: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                files[artifactName] = toByteArray()
                super.close()
            }
        }
    }
}
