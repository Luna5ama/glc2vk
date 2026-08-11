package dev.vibris.core

import dev.vibris.api.RuntimeAction
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.CaptureMulti
import dev.vibris.protocol.v2.CapturePass
import dev.vibris.protocol.v2.DumpTexture
import dev.vibris.protocol.v2.GetGpuMetrics
import dev.vibris.protocol.v2.InspectShader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RuntimeActionProtocolTest {
    @Test
    fun mapsTypedCommands() {
        val pass = RuntimeActionProtocol.toApi(
            Action.newBuilder()
                .setCapturePass(
                    CapturePass.newBuilder()
                        .setPassId("begin1")
                        .setArtifactName("vibris-capture"),
                )
                .build(),
        )
        assertEquals(RuntimeAction.CapturePass("begin1", "vibris-capture"), pass)

        val metrics = RuntimeActionProtocol.toApi(
            Action.newBuilder()
                .setGetGpuMetrics(GetGpuMetrics.newBuilder().setFrames(17))
                .build(),
        )
        assertEquals(RuntimeAction.GpuMetrics(17), metrics)

    }

    @Test
    fun rejectsIncompleteCommandsAtTheGrpcBoundary() {
        assertInvalid(Action.getDefaultInstance())
        assertInvalid(
            Action.newBuilder()
                .setCapturePass(CapturePass.getDefaultInstance())
                .build(),
        )
        assertInvalid(
            Action.newBuilder()
                .setCaptureMulti(CaptureMulti.newBuilder().setCaptureType("unknown"))
                .build(),
        )
        assertInvalid(
            Action.newBuilder()
                .setGetGpuMetrics(GetGpuMetrics.newBuilder().setFrames(10_001))
                .build(),
        )
        assertInvalid(
            Action.newBuilder()
                .setGetGpuMetrics(GetGpuMetrics.getDefaultInstance())
                .build(),
        )
        assertInvalid(
            Action.newBuilder()
                .setDumpTexture(DumpTexture.getDefaultInstance())
                .build(),
        )
        val inspection = Action.newBuilder().setInspectShader(InspectShader.getDefaultInstance()).build()
        assertFalse(RuntimeActionProtocol.isRuntime(inspection))
        assertInvalid(inspection)
    }

    private fun assertInvalid(action: Action) {
        assertThrows(IllegalArgumentException::class.java) { RuntimeActionProtocol.toApi(action) }
    }
}
