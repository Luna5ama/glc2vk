package dev.vibris.core

import dev.vibris.api.RuntimeAction
import dev.vibris.protocol.v1.Action
import dev.vibris.protocol.v1.CaptureMulti
import dev.vibris.protocol.v1.CapturePass
import dev.vibris.protocol.v1.DumpTexture
import dev.vibris.protocol.v1.GetGpuMetrics
import dev.vibris.protocol.v1.ReloadShader
import dev.vibris.protocol.v1.ScheduleScreenshot
import dev.vibris.protocol.v1.ShaderConfig
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

        val texture = RuntimeActionProtocol.toApi(
            Action.newBuilder()
                .setDumpTexture(DumpTexture.newBuilder().setName("colortex0").setRaw(true))
                .build(),
        )
        assertEquals(RuntimeAction.DumpTexture("colortex0", null, true), texture)

        val reload = RuntimeActionProtocol.toApi(
            Action.newBuilder()
                .setReloadShader(
                    ReloadShader.newBuilder().setConfig(
                        ShaderConfig.newBuilder().putValues("SETTING_SAMPLE_COUNT", "32"),
                    ),
                )
                .build(),
        )
        assertEquals(RuntimeAction.ReloadShader(mapOf("SETTING_SAMPLE_COUNT" to "32")), reload)

        val reloadWithoutConfig = RuntimeActionProtocol.toApi(
            Action.newBuilder().setReloadShader(ReloadShader.getDefaultInstance()).build(),
        )
        assertEquals(RuntimeAction.ReloadShader(null), reloadWithoutConfig)

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
                .setCaptureMulti(CaptureMulti.newBuilder().setType("unknown"))
                .build(),
        )
        assertInvalid(
            Action.newBuilder()
                .setScheduleScreenshot(ScheduleScreenshot.getDefaultInstance())
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
    }

    private fun assertInvalid(action: Action) {
        assertThrows(IllegalArgumentException::class.java) { RuntimeActionProtocol.toApi(action) }
    }
}
