package dev.vibris.core

import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ActionSequence
import dev.vibris.protocol.v2.GetGpuMetrics
import dev.vibris.protocol.v2.JobSpec
import dev.vibris.protocol.v2.LoadShader
import dev.vibris.protocol.v2.ResultArtifactOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CaptureProgramBuilderTest {
    @Test
    fun `strict v2 action sequence accepts result artifacts`() {
        val submission = JobSpec.newBuilder()
            .setJobId("paired-request")
            .setResultArtifacts(
                ResultArtifactOptions.newBuilder()
                    .setWriteJson(true),
            )
            .setActionSequence(
                ActionSequence.newBuilder()
                    .addActions(
                        Action.newBuilder().setLoadShader(
                            LoadShader.newBuilder()
                                .setSourceUuid("33333333-3333-4333-8333-333333333333")
                                .setSourceId("baseline")
                                .setConfigId("config"),
                        ),
                    )
                    .addActions(
                        Action.newBuilder().setGetGpuMetrics(
                            GetGpuMetrics.newBuilder().setFrames(4),
                        ),
                    ),
            )
            .build()
        val job = CoreJob(
            submission,
            "paired-request",
            "11111111-1111-4111-8111-111111111111",
            "message",
            null,
        )

        val program = CaptureProgramBuilder().actions(job, ResourceCatalog.empty())

        assertEquals(2, program.steps.size)
        assertEquals(CaptureProgramBuilder.ActionType.LOAD, program.steps[0].type)
        assertEquals(CaptureProgramBuilder.ActionType.RUNTIME, program.steps[1].type)
    }
}
