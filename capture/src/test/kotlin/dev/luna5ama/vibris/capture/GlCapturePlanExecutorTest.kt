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
import java.util.function.Function
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GlCapturePlanExecutorTest {
    @Test
    fun writesArtifactsSidecarsAndManifest() {
        val targets = listOf(
            target(ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, "beauty", CapturePlan.ArtifactFormat.PNG),
            target(ResourceCatalog.ResourceKind.TEXTURE, "colortex0", CapturePlan.ArtifactFormat.RAW),
            target(ResourceCatalog.ResourceKind.BUFFER, "radiance_cache", CapturePlan.ArtifactFormat.BIN),
        )
        val sink = MemorySink()
        val resolved = mutableListOf<String>()
        val captured = mutableListOf<String>()

        val result = GlCapturePlanExecutor.capture(
            CapturePlan(targets),
            sink,
            42,
            CancellationToken.none(),
            Function { target ->
                resolved += target.logicalName
                resolved.size
            },
        ) { target, glId, output ->
            captured += target.logicalName
            output.write(byteArrayOf(glId.toByte()))
            GlCaptureMetadata(2, 3, 1, "format\\\"$glId", 4, ResourceCatalog.ScalarType.FLOAT32, glId.toLong())
        }

        assertEquals(targets.map(CapturePlan.Target::logicalName), resolved)
        assertEquals(resolved, captured)
        assertEquals(targets.map(CapturePlan.Target::artifactName).toSet(), result.artifacts.keys)
        assertTrue("beauty.json" !in sink.artifacts)
        assertContentEquals(byteArrayOf(1), sink.artifacts.getValue("beauty.png"))
        assertContentEquals(byteArrayOf(2), sink.artifacts.getValue("colortex0.raw"))
        assertContentEquals(byteArrayOf(3), sink.artifacts.getValue("radiance_cache.bin"))
        assertEquals(
            "{\"logical_name\":\"colortex0\",\"kind\":\"TEXTURE\",\"width\":2,\"height\":3,\"depth\":1," +
                "\"internal_format\":\"format\\\\\\\"2\",\"channel_count\":4,\"scalar_type\":\"FLOAT32\"," +
                "\"byte_size\":2,\"frame_id\":42}",
            sink.artifacts.getValue("colortex0.json").decodeToString(),
        )
        assertEquals(42, result.frameId)
        assertEquals(42, result.artifacts.getValue("radiance_cache").frameId)
    }

    @Test
    fun resolvesEveryTargetBeforeWriting() {
        val sink = MemorySink()
        val plan = CapturePlan(
            listOf(
                target(ResourceCatalog.ResourceKind.TEXTURE, "present", CapturePlan.ArtifactFormat.RAW),
                target(ResourceCatalog.ResourceKind.TEXTURE, "missing", CapturePlan.ArtifactFormat.RAW),
            ),
        )

        val failure = assertFailsWith<CaptureResourceNotFoundException> {
            GlCapturePlanExecutor.capture(
                plan,
                sink,
                1,
                CancellationToken.none(),
                Function { target -> if (target.logicalName == "present") 7 else null },
            ) { _, _, _ -> error("Capture must not start before all targets resolve") }
        }

        assertEquals("Capture resource was not found: missing", failure.message)
        assertTrue(sink.artifacts.isEmpty())
    }

    @Test
    fun wrapsSinkFailuresAndHonorsCancellation() {
        val plan = CapturePlan(
            listOf(target(ResourceCatalog.ResourceKind.TEXTURE, "colortex0", CapturePlan.ArtifactFormat.RAW)),
        )
        val ioFailure = assertFailsWith<UncheckedIOException> {
            GlCapturePlanExecutor.capture(
                plan,
                ArtifactSink { throw IOException("sink failed") },
                1,
                CancellationToken.none(),
                Function { 7 },
            )
        }
        assertEquals("sink failed", ioFailure.cause?.message)

        val cancellation = CancellationToken.source().also { it.cancel() }.token()
        val sink = MemorySink()
        assertFailsWith<CancellationException> {
            GlCapturePlanExecutor.capture(plan, sink, 1, cancellation, Function { 7 })
        }
        assertTrue(sink.artifacts.isEmpty())
    }

    private fun target(
        kind: ResourceCatalog.ResourceKind,
        name: String,
        format: CapturePlan.ArtifactFormat,
    ) = CapturePlan.Target(kind, name, format, name, 0, 0)

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