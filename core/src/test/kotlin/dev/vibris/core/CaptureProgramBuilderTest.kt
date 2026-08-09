package dev.vibris.core

import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v1.Action
import dev.vibris.protocol.v1.ActionSequence
import dev.vibris.protocol.v1.BenchmarkCase
import dev.vibris.protocol.v1.GetGpuMetrics
import dev.vibris.protocol.v1.LoadShader
import dev.vibris.protocol.v1.ResultArtifactOptions
import dev.vibris.protocol.v1.SubmitJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CaptureProgramBuilderTest {
    @Test
    fun `isolated paired benchmark accepts benchmark result artifacts`() {
        val caseId = "ab-r01-s1-baseline"
        val submission = SubmitJob.newBuilder()
            .setRequestId("paired-request")
            .setWorkspaceId("11111111-1111-4111-8111-111111111111")
            .setBenchmarkCase(
                BenchmarkCase.newBuilder()
                    .setWorkflowId("22222222-2222-4222-8222-222222222222")
                    .setCaseId(caseId),
            )
            .setResultArtifacts(
                ResultArtifactOptions.newBuilder()
                    .setJson(true)
                    .setKind("benchmark_ab")
                    .setAttempt(1),
            )
            .setActions(
                ActionSequence.newBuilder()
                    .addActions(
                        Action.newBuilder().setLoadShader(
                            LoadShader.newBuilder()
                                .setSourceUuid("33333333-3333-4333-8333-333333333333")
                                .setSourceId("baseline")
                                .setConfigId("config")
                                .setCaseId(caseId),
                        ),
                    )
                    .addActions(
                        Action.newBuilder().setGetGpuMetrics(
                            GetGpuMetrics.newBuilder().setFrames(4),
                        ),
                    ),
            )
            .build()
        val job = CoreJob(submission, "message", null)

        val program = CaptureProgramBuilder().actions(job, ResourceCatalog.empty())

        assertEquals(2, program.steps.size)
        assertEquals(CaptureProgramBuilder.ActionType.LOAD, program.steps[0].type)
        assertEquals(CaptureProgramBuilder.ActionType.RUNTIME, program.steps[1].type)
    }
}
