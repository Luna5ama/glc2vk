package dev.vibris.core

import dev.vibris.api.RuntimeAction
import dev.vibris.protocol.v1.Action
import dev.vibris.protocol.v1.CaptureMulti
import dev.vibris.protocol.v1.CapturePass
import dev.vibris.protocol.v1.DumpTextureV2
import dev.vibris.protocol.v1.GetGpuMetrics
import dev.vibris.protocol.v1.EmptyAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RuntimeActionProtocolTest {
    @Test
    fun mapsTypedCommands() {
        val pass = RuntimeActionProtocol.toApi(
            Action.newBuilder()
                .setCapturePass(
                    CapturePass.newBuilder()
                        .setPass("begin1")
                        .setPath("vibris/capture"),
                )
                .build(),
        )
        assertEquals(RuntimeAction.CapturePass("begin1", "vibris/capture"), pass)

        val buffers = RuntimeActionProtocol.toApi(
            Action.newBuilder()
                .setListBuffers(EmptyAction.getDefaultInstance())
                .build(),
        )
        assertEquals(RuntimeAction.ListBuffers, buffers)

        val metrics = RuntimeActionProtocol.toApi(
            Action.newBuilder()
                .setGetGpuMetrics(GetGpuMetrics.newBuilder().setFrames(17))
                .build(),
        )
        assertEquals(RuntimeAction.GpuMetrics(17), metrics)

        val inspection = RuntimeActionProtocol.toApi(
            Action.newBuilder().setInspectShader(EmptyAction.getDefaultInstance()).build(),
        )
        assertEquals(RuntimeAction.InspectShader, inspection)
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
                .setCaptureMulti(CaptureMulti.newBuilder().setType("unknown"))
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
                .setDumpTextureV2(DumpTextureV2.getDefaultInstance())
                .build(),
        )
    }

    private fun assertInvalid(action: Action) {
        assertThrows(IllegalArgumentException::class.java) { RuntimeActionProtocol.toApi(action) }
    }
}
