package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ActionSequence
import dev.vibris.protocol.v2.DumpBuffer
import dev.vibris.protocol.v2.JobSpec
import dev.vibris.protocol.v2.LoadShader
import dev.vibris.protocol.v2.ResetTemporalState
import dev.vibris.protocol.v2.ResourceSelector
import dev.vibris.protocol.v2.WaitFramesReceipt
import java.io.IOException
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CaptureJobExecutorTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `strict only job prepares and atomically registers an authoritative plan`() {
        val executor = CaptureJobExecutor(ArtifactManager(temp.resolve("artifacts")))
        val action = executor.prepareActions(job(), ResourceCatalog.empty(), emptyList())
        val prepared = requireNotNull(action.prepared)
        val checkpoint = prepared.checkpoint()

        val resolved = executor.resolveDeferred(
            prepared,
            action.program,
            action.program.steps.single(),
            bufferCatalog(41),
        )

        assertEquals(listOf(2), resolved.captureActions.map { it.actionIndex })
        assertEquals(8192L, resolved.estimatedBytes)
        assertEquals(listOf(resolved.capture), prepared.plans)

        prepared.rollback(checkpoint)
        assertTrue(prepared.plans.isEmpty())
        prepared.close()
    }

    @Test
    fun `failure receipt preserves exact frame and authoritative descriptor without artifacts`() {
        val executor = CaptureJobExecutor(ArtifactManager(temp.resolve("receipt-artifacts")))
        val catalog = bufferCatalog(0, frameId = 7, byteSize = 8192)
        val action = executor.prepareActions(job(), ResourceCatalog.empty(), emptyList())
        val prepared = requireNotNull(action.prepared)
        val resolved = executor.resolveDeferred(prepared, action.program, action.program.steps.single(), catalog)
        val wait = WaitFramesReceipt.getDefaultInstance()

        val receipt = executor.failureCaptureReceipt(resolved.capture, 0, 97, catalog, wait)

        assertEquals(97L, receipt.frameId)
        assertEquals(97L, receipt.resource.frameId)
        assertEquals(8192L, receipt.resource.byteSize)
        assertEquals("buffer", receipt.resource.logicalName)
        assertTrue(receipt.hasInternalWait())
        assertTrue(receipt.artifactsList.isEmpty())
        prepared.close()
    }

    @Test
    fun `prepared constructor rejects duplicate initial output names ignoring case`() {
        val manager = ArtifactManager(temp.resolve("duplicate-artifacts"))
        val executor = CaptureJobExecutor(manager)
        val selector = CapturePlan.ResourceSelector(
            ResourceCatalog.ResourceKind.BUFFER,
            "buffer",
            null,
            0,
            0,
        )
        val first = CapturePlan.Target(
            selector,
            CapturePlan.ArtifactFormat.BIN,
            "first",
            listOf(output("Shared.bin")),
        )
        val second = CapturePlan.Target(
            selector,
            CapturePlan.ArtifactFormat.BIN,
            "second",
            listOf(output("shared.BIN")),
        )

        manager.beginJob(WORKSPACE_ID, "duplicate", "duplicate", "actions", 4096).use { transaction ->
            assertThrows(IOException::class.java) {
                executor.Prepared(transaction, listOf(CapturePlan(listOf(first, second))), emptyList(), 0, 2)
            }
        }
    }

    @Test
    fun `failure receipt fails closed when authoritative catalog no longer matches`() {
        val executor = CaptureJobExecutor(ArtifactManager(temp.resolve("missing-artifacts")))
        val action = executor.prepareActions(job(), ResourceCatalog.empty(), emptyList())
        val prepared = requireNotNull(action.prepared)
        val resolved = executor.resolveDeferred(
            prepared,
            action.program,
            action.program.steps.single(),
            bufferCatalog(0),
        )

        val failure = assertThrows(RuntimeJobExecutor.Failure::class.java) {
            executor.failureCaptureReceipt(
                resolved.capture,
                0,
                11,
                ResourceCatalog.empty(),
                null,
            )
        }

        assertEquals(dev.vibris.protocol.v2.ErrorCode.ERROR_CODE_RESOURCE_NOT_FOUND, failure.code)
        assertFalse(prepared.plans.isEmpty())
        prepared.close()
    }

    private fun job(): CoreJob {
        val submission = JobSpec.newBuilder()
            .setJobId("capture-executor")
            .setActionSequence(
                ActionSequence.newBuilder()
                    .addActions(
                        Action.newBuilder()
                            .setPrelude(true)
                            .setLoadShader(
                                LoadShader.newBuilder()
                                    .setSourceUuid("33333333-3333-4333-8333-333333333333")
                                    .setSourceId("source")
                                    .setConfigId("config"),
                            ),
                    )
                    .addActions(
                        Action.newBuilder().setResetTemporalState(ResetTemporalState.getDefaultInstance()),
                    )
                    .addActions(
                        Action.newBuilder().setDumpBuffer(
                            DumpBuffer.newBuilder()
                                .setResource(ResourceSelector.newBuilder().setLogicalName("buffer"))
                                .setArtifactName("capture"),
                        ),
                    ),
            )
            .build()
        return CoreJob(submission, submission.jobId, WORKSPACE_ID, "message", null)
    }

    private fun output(name: String) = CapturePlan.ArtifactOutputSpec(
        name,
        CapturePlan.ArtifactFormat.BIN,
        CapturePlan.ArtifactRole.PRIMARY,
        null,
    )

    private fun bufferCatalog(
        layer: Int,
        frameId: Long = 1,
        byteSize: Long = 4096,
    ): ResourceCatalog = ResourceCatalog.of(
        listOf(
            ResourceCatalog.ResourceDescriptor.of(
                "buffer",
                ResourceCatalog.ResourceKind.BUFFER,
                emptyList(),
                0,
                0,
                0,
                0,
                0,
                "binary",
                0,
                ResourceCatalog.ScalarType.UINT8,
                byteSize,
                frameId,
                "buffer-$layer",
                "shader_storage",
                "",
                "",
                "",
                0,
                "",
                "",
            ),
        ),
        emptyList(),
    )

    private companion object {
        const val WORKSPACE_ID = "11111111-1111-4111-8111-111111111111"
    }
}