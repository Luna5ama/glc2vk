package dev.luna5ama.vibris.capture

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResourceNotFoundException
import dev.vibris.api.ResourceCatalog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.UncheckedIOException
import java.util.concurrent.CancellationException
import java.util.function.Function
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GlCapturePlanExecutorTest {
    @Test
    fun writesArtifactGroupsAndSidecars() {
        val targets = listOf(
            target(ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, "beauty", CapturePlan.ArtifactFormat.PNG),
            target(ResourceCatalog.ResourceKind.TEXTURE, "colortex0", CapturePlan.ArtifactFormat.BIN),
            target(ResourceCatalog.ResourceKind.BUFFER, "iris_ssbo_6", CapturePlan.ArtifactFormat.BIN),
        )
        val sink = MemorySink()
        val resolved = mutableListOf<String>()
        val captured = mutableListOf<String>()

        val result = GlCapturePlanExecutor.capture(
            CapturePlan(targets), sink, 42, CancellationToken.none(),
            Function { target -> resolved.add(target.resource.logicalName).let { resolved.size } },
        ) { target, _, glId, output ->
            captured += target.resource.logicalName
            output.write(byteArrayOf(glId.toByte()))
            GlCaptureMetadata(2, 3, 1, "format\"$glId", 4, ResourceCatalog.ScalarType.FLOAT32, glId.toLong())
        }

        assertEquals(targets.map { it.resource.logicalName }, resolved)
        assertEquals(resolved, captured)
        assertEquals(targets.map(CapturePlan.Target::artifactName), result.groups.map { it.name })
        assertTrue("beauty.json" !in sink.artifacts)
        assertContentEquals(byteArrayOf(1), sink.artifacts.getValue("beauty.png"))
        assertContentEquals(byteArrayOf(2), sink.artifacts.getValue("colortex0.bin"))
        assertContentEquals(byteArrayOf(3), sink.artifacts.getValue("iris_ssbo_6.bin"))
        val sidecar = sink.artifacts.getValue("colortex0.json").decodeToString()
        assertTrue(sidecar.contains("\"logical_name\":\"colortex0\""))
        assertTrue(sidecar.contains("\"endianness\":\"native\""))
        assertTrue(sidecar.contains("\"y_flipped\":false"))
        val pngTarget = target(ResourceCatalog.ResourceKind.TEXTURE, "colortex1", CapturePlan.ArtifactFormat.PNG)
        val pngSink = MemorySink()
        GlCapturePlanExecutor.capture(
            CapturePlan(listOf(pngTarget)), pngSink, 42, CancellationToken.none(), Function { 4 },
        ) { _, _, _, output ->
            output.write(4)
            GlCaptureMetadata(2, 3, 1, "RGBA8", 4, ResourceCatalog.ScalarType.UINT8, 24)
        }
        assertTrue(pngSink.artifacts.getValue("colortex1.json").decodeToString()
            .contains("\"y_flipped\":true"))
        assertEquals(42, result.frameId)
        assertEquals(42, result.groups.last().resource.frameId)
    }

    @Test
    fun resolvesEveryTargetBeforeWriting() {
        val sink = MemorySink()
        val plan = CapturePlan(listOf(
            target(ResourceCatalog.ResourceKind.TEXTURE, "present", CapturePlan.ArtifactFormat.BIN),
            target(ResourceCatalog.ResourceKind.TEXTURE, "missing", CapturePlan.ArtifactFormat.BIN),
        ))
        val failure = assertFailsWith<CaptureResourceNotFoundException> {
            GlCapturePlanExecutor.capture(
                plan, sink, 1, CancellationToken.none(),
                Function { target -> if (target.resource.logicalName == "present") 7 else null },
            ) { _, _, _, _ -> error("Capture must not start before all targets resolve") }
        }
        assertEquals("Capture resource was not found: missing", failure.message)
        assertTrue(sink.artifacts.isEmpty())
    }

    @Test
    fun wrapsSinkFailuresAndHonorsCancellation() {
        val plan = CapturePlan(listOf(
            target(ResourceCatalog.ResourceKind.TEXTURE, "colortex0", CapturePlan.ArtifactFormat.BIN),
        ))
        val ioFailure = assertFailsWith<UncheckedIOException> {
            GlCapturePlanExecutor.capture(
                plan, ArtifactSink { throw IOException("sink failed") }, 1, CancellationToken.none(), Function { 7 },
            ) { _, _, _, _ -> GlCaptureMetadata(1, 1, 1, "R8", 1, ResourceCatalog.ScalarType.UINT8, 1) }
        }
        assertEquals("sink failed", ioFailure.cause?.message)

        val cancellation = CancellationToken.source().also { it.cancel() }.token()
        val sink = MemorySink()
        assertFailsWith<CancellationException> {
            GlCapturePlanExecutor.capture(plan, sink, 1, cancellation, Function { 7 })
        }
        assertTrue(sink.artifacts.isEmpty())
    }

    private fun target(kind: ResourceCatalog.ResourceKind, name: String, format: CapturePlan.ArtifactFormat): CapturePlan.Target {
        val primary = CapturePlan.ArtifactOutputSpec(
            "$name.${format.name.lowercase()}", format, CapturePlan.ArtifactRole.PRIMARY, null,
        )
        val outputs = if (kind == ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER) listOf(primary) else listOf(
            primary,
            CapturePlan.ArtifactOutputSpec("$name.json", CapturePlan.ArtifactFormat.JSON,
                CapturePlan.ArtifactRole.METADATA, null),
        )
        return CapturePlan.Target(
            CapturePlan.ResourceSelector(
                kind,
                name,
                if (kind == ResourceCatalog.ResourceKind.TEXTURE) ResourceCatalog.TextureView.CURRENT else null,
                0,
                0,
            ),
            format,
            name,
            outputs,
        )
    }

    private class MemorySink : ArtifactSink {
        val artifacts = linkedMapOf<String, ByteArray>()
        override fun open(artifactName: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                artifacts[artifactName] = toByteArray()
                super.close()
            }
        }
    }
}
