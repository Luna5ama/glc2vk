package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v1.Action
import dev.vibris.protocol.v1.ArtifactFormat
import dev.vibris.protocol.v1.DumpBuffer
import dev.vibris.protocol.v1.DumpTextureV2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CapturePlanBuilderV2Test {
    @Test
    fun expandsThreeDimensionalPngAndBufferSidecarsDeterministically() {
        val catalog = ResourceCatalog(listOf(
            resource("iris_custom_image.volume", ResourceCatalog.ResourceKind.TEXTURE, depth = 12),
            resource("iris_ssbo_6", ResourceCatalog.ResourceKind.BUFFER, depth = 0),
        ))
        val targets = mutableListOf<CapturePlan.Target>()
        CapturePlanBuilder.addAction(targets, Action.newBuilder().setDumpTextureV2(
            DumpTextureV2.newBuilder().setLogicalName("iris_custom_image.volume")
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG).setArtifactName("volume"),
        ).build(), catalog)
        CapturePlanBuilder.addAction(targets, Action.newBuilder().setDumpBuffer(
            DumpBuffer.newBuilder().setLogicalName("iris_ssbo_6").setArtifactName("ssbo-6"),
        ).build(), catalog)

        val plan = CapturePlanBuilder.plan(targets, catalog).capture
        assertEquals((0 until 12).map { "volume.layer${it.toString().padStart(4, '0')}.png" } +
            "volume.json", plan.targets[0].outputs.map { it.fileName })
        assertEquals(listOf("ssbo-6.bin", "ssbo-6.json"), plan.targets[1].outputs.map { it.fileName })
        assertEquals((0 until 12).toList(), plan.targets[0].outputs.dropLast(1).map { it.subresourceIndex })
    }

    @Test
    fun rejectsRemovedOrAmbiguousFormatsAndDuplicateOutputs() {
        val catalog = ResourceCatalog(listOf(resource("colortex0.main", ResourceCatalog.ResourceKind.TEXTURE, 1)))
        val unspecified = mutableListOf<CapturePlan.Target>()
        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            CapturePlanBuilder.addAction(unspecified, Action.newBuilder().setDumpTextureV2(
                DumpTextureV2.newBuilder().setLogicalName("colortex0.main").setArtifactName("texture"),
            ).build(), catalog)
        }

        val target = CapturePlanBuilder.target(ResourceCatalog.ResourceKind.TEXTURE, "colortex0.main",
            CapturePlan.ArtifactFormat.BIN, "same", 0, 0)
        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            CapturePlanBuilder.plan(listOf(target, target), catalog)
        }
    }

    private fun resource(name: String, kind: ResourceCatalog.ResourceKind, depth: Int) =
        ResourceCatalog.ResourceDescriptor(
            name, kind, if (kind == ResourceCatalog.ResourceKind.BUFFER) 0 else 2,
            if (kind == ResourceCatalog.ResourceKind.BUFFER) 0 else 2, depth, 1, 1,
            if (kind == ResourceCatalog.ResourceKind.BUFFER) "binary" else "RGBA8", 4,
            ResourceCatalog.ScalarType.UINT8, if (kind == ResourceCatalog.ResourceKind.BUFFER) 64 else 16L * depth,
            1, name,
        )
}
