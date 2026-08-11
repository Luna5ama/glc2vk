package dev.vibris.core

import dev.vibris.protocol.v2.ActionKind
import dev.vibris.protocol.v2.ActionReceipt
import dev.vibris.protocol.v2.JobSpec
import dev.vibris.protocol.v2.ReceiptStatus
import dev.vibris.protocol.v2.ResultArtifactOptions
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProfileResultArtifactsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `writes strict v2 JSON and CSV summaries from action receipts`() {
        val job = job(writeJson = true, writeCsv = true)
        val receipt = ActionReceipt.newBuilder()
            .setActionIndex(0)
            .setKind(ActionKind.ACTION_KIND_GET_GPU_METRICS)
            .setStatus(ReceiptStatus.RECEIPT_STATUS_OK)
            .build()
        val manager = ArtifactManager(temp.resolve("artifacts"), 1024 * 1024)

        val committed = manager.beginJob(WORKSPACE_ID, "request", 0).use { transaction ->
            val generated = ProfileResultArtifacts.write(job, transaction, listOf(receipt))
            assertEquals(
                listOf(ProfileResultArtifacts.JSON_FILE, ProfileResultArtifacts.CSV_FILE),
                generated.map { it.fileName },
            )
            transaction.commit(generated.map { it.fileName }.toSet())
        }

        val json = Files.readString(committed.artifacts().getValue(ProfileResultArtifacts.JSON_FILE))
        val csv = Files.readString(committed.artifacts().getValue(ProfileResultArtifacts.CSV_FILE))
        assertTrue(json.contains("\"schema_version\":2"))
        assertTrue(json.contains("\"job_id\":\"job\""))
        assertTrue(json.contains("\"kind\":\"get_gpu_metrics\""))
        assertEquals("action_index,kind,status\n0,get_gpu_metrics,ok\n", csv)
    }

    @Test
    fun `does not request outputs when both v2 flags are false`() {
        assertFalse(ProfileResultArtifacts.requested(job(writeJson = false, writeCsv = false)))
    }

    private fun job(writeJson: Boolean, writeCsv: Boolean): JobSpec = JobSpec.newBuilder()
        .setJobId("job")
        .setResultArtifacts(
            ResultArtifactOptions.newBuilder()
                .setWriteJson(writeJson)
                .setWriteCsv(writeCsv),
        )
        .build()

    companion object {
        private const val WORKSPACE_ID = "11111111-1111-4111-8111-111111111111"
    }
}
