package dev.vibris.core

import dev.vibris.protocol.v1.Action
import dev.vibris.protocol.v1.ActionResult
import dev.vibris.protocol.v1.ActionSequence
import dev.vibris.protocol.v1.ArtifactFormat
import dev.vibris.protocol.v1.ArtifactKind
import dev.vibris.protocol.v1.GetGpuMetrics
import dev.vibris.protocol.v1.JobActionKind
import dev.vibris.protocol.v1.LoadShader
import dev.vibris.protocol.v1.ResultArtifactOptions
import dev.vibris.protocol.v1.SubmitJob
import dev.vibris.protocol.v1.WaitFrames
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProfileResultArtifactsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun writesCompleteJsonAndCsvThroughManagedTransaction() {
        val manager = ArtifactManager(temp.resolve("artifacts"), 1024 * 1024)
        val submission = submission()
        val actionResults = listOf(
            ActionResult.newBuilder()
                .setActionIndex(0)
                .setKind(JobActionKind.JOB_ACTION_KIND_LOAD_SHADER)
                .setJson("""{"success":true,"case_id":"dev--spawn","source":"dev","config":"spawn"}""")
                .build(),
            ActionResult.newBuilder()
                .setActionIndex(2)
                .setKind(JobActionKind.JOB_ACTION_KIND_GET_GPU_METRICS)
                .setJson(
                    """{"gpuTimings":{"composite18_total":{"avg":7000000,"p50":6800000},"shadowcomp0":{"avg":120000}}}""",
                )
                .build(),
        )

        val result = manager.beginJob(WORKSPACE_ID, "profile-request", 0).use { transaction ->
            val generated = ProfileResultArtifacts.write(submission, transaction, actionResults)
            assertEquals(
                listOf(ProfileResultArtifacts.JSON_FILE, ProfileResultArtifacts.CSV_FILE),
                generated.map { it.fileName },
            )
            transaction.open("shader.log").use { it.write("Shader reload succeeded.\n".toByteArray()) }
            CaptureProtocolArtifacts().commit(
                CoreJob(submission, "message-profile-request", null),
                emptyList(),
                emptyList(),
                transaction,
                emptyList(),
                null,
                generated,
            )
        }

        val jsonArtifact = result.artifactsList.single { it.fileName == ProfileResultArtifacts.JSON_FILE }
        assertEquals(ArtifactKind.ARTIFACT_KIND_PROFILE_RESULT, jsonArtifact.kind)
        assertEquals(ArtifactFormat.ARTIFACT_FORMAT_JSON, jsonArtifact.format)
        val jsonPath = Path.of(jsonArtifact.path)
        val document = Json.parseToJsonElement(Files.readString(jsonPath)).jsonObject
        val case = document.getValue("cases").jsonArray.single().jsonObject
        val composite = case.getValue("metrics").jsonObject.getValue("gpuTimings").jsonObject
            .getValue("composite18_total").jsonObject
        assertEquals(7_000_000.0, composite.getValue("avg").jsonPrimitive.double)
        assertEquals(7_000.0, composite.getValue("avg_us").jsonPrimitive.double)
        assertEquals(7.0, composite.getValue("avg_ms").jsonPrimitive.double)
        assertEquals("passed", case.getValue("status").jsonPrimitive.content)
        assertEquals(2, document.getValue("raw_action_results").jsonArray.size)

        val csvPath = Path.of(result.artifactsList.single { it.fileName == ProfileResultArtifacts.CSV_FILE }.path)
        val csv = Files.readString(csvPath)
        assertTrue(
            csv.startsWith(
                "case_id,source_id,config_id,status,error_code,error_message,pass,statistic," +
                    "value_ns,value_us,value_ms\n",
            ),
        )
        assertTrue(csv.contains("dev--spawn,dev,spawn,passed,,,composite18_total,avg,7000000.0,7000.0,7.0"))
        assertTrue(Files.readString(Path.of(result.manifestPath)).contains(ProfileResultArtifacts.JSON_FILE))
        assertEquals(jsonPath.parent, csvPath.parent)
        assertTrue(manager.usedBytes() > Files.size(jsonPath) + Files.size(csvPath))
    }

    private fun submission(): SubmitJob = SubmitJob.newBuilder()
        .setRequestId("profile-request")
        .setWorkspaceId(WORKSPACE_ID)
        .setResultArtifacts(
            ResultArtifactOptions.newBuilder()
                .setJson(true)
                .setCsv(true)
                .setKind("profile_matrix")
                .addConvertedUnits("us")
                .addConvertedUnits("ms"),
        )
        .setActions(
            ActionSequence.newBuilder()
                .addActions(
                    Action.newBuilder().setLoadShader(
                        LoadShader.newBuilder()
                            .setSourceUuid("22222222-2222-4222-8222-222222222222")
                            .setSourceId("dev")
                            .setConfigId("spawn")
                            .setCaseId("dev--spawn")
                            .setContinueOnFailure(true),
                    ),
                )
                .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(32)))
                .addActions(Action.newBuilder().setGetGpuMetrics(GetGpuMetrics.newBuilder().setFrames(64))),
        )
        .build()

    companion object {
        private const val WORKSPACE_ID = "11111111-1111-4111-8111-111111111111"
    }
}
