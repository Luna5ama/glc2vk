package dev.vibris.core

import dev.vibris.api.DebugControlCommand
import dev.vibris.protocol.v1.DebugCaptureMulti
import dev.vibris.protocol.v1.DebugCapturePass
import dev.vibris.protocol.v1.DebugControlRequest
import dev.vibris.protocol.v1.DebugDumpTexture
import dev.vibris.protocol.v1.DebugReloadShader
import dev.vibris.protocol.v1.DebugScheduleScreenshot
import dev.vibris.protocol.v1.ShaderConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DebugControlProtocolTest {
    @Test
    fun mapsTypedCommands() {
        val pass = DebugControlProtocol.toApi(
            DebugControlRequest.newBuilder()
                .setCapturePass(
                    DebugCapturePass.newBuilder()
                        .setPass("begin1")
                        .setPath("vibris/capture"),
                )
                .build(),
        )
        assertEquals(DebugControlCommand.CapturePass("begin1", "vibris/capture"), pass)

        val texture = DebugControlProtocol.toApi(
            DebugControlRequest.newBuilder()
                .setDumpTexture(DebugDumpTexture.newBuilder().setName("colortex0").setRaw(true))
                .build(),
        )
        assertEquals(DebugControlCommand.DumpTexture("colortex0", null, true), texture)

        val reload = DebugControlProtocol.toApi(
            DebugControlRequest.newBuilder()
                .setReloadShader(
                    DebugReloadShader.newBuilder().setConfig(
                        ShaderConfig.newBuilder().putValues("SETTING_SAMPLE_COUNT", "32"),
                    ),
                )
                .build(),
        )
        assertEquals(DebugControlCommand.ReloadShader(mapOf("SETTING_SAMPLE_COUNT" to "32")), reload)

        val reloadWithoutConfig = DebugControlProtocol.toApi(
            DebugControlRequest.newBuilder().setReloadShader(DebugReloadShader.getDefaultInstance()).build(),
        )
        assertEquals(DebugControlCommand.ReloadShader(null), reloadWithoutConfig)
    }

    @Test
    fun rejectsIncompleteCommandsAtTheGrpcBoundary() {
        assertInvalid(DebugControlRequest.getDefaultInstance())
        assertInvalid(
            DebugControlRequest.newBuilder()
                .setCapturePass(DebugCapturePass.getDefaultInstance())
                .build(),
        )
        assertInvalid(
            DebugControlRequest.newBuilder()
                .setCaptureMulti(DebugCaptureMulti.newBuilder().setType("unknown"))
                .build(),
        )
        assertInvalid(
            DebugControlRequest.newBuilder()
                .setScheduleScreenshot(DebugScheduleScreenshot.getDefaultInstance())
                .build(),
        )
        assertInvalid(
            DebugControlRequest.newBuilder()
                .setDumpTexture(DebugDumpTexture.getDefaultInstance())
                .build(),
        )
    }

    private fun assertInvalid(request: DebugControlRequest) {
        assertThrows(IllegalArgumentException::class.java) { DebugControlProtocol.toApi(request) }
    }
}
