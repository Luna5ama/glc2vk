package dev.vibris.core

import dev.vibris.protocol.v2.NsightGpuTrace
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NsightGpuTraceRunnerTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `single and multi capture configurations validate`() {
        assertDoesNotThrow {
            NsightGpuTraceRunner.validate(command("trace") { passId = "composite/composite1" })
        }
        assertDoesNotThrow {
            NsightGpuTraceRunner.validate(command("group") { captureType = "composite" })
        }
    }

    @Test
    fun `invalid internal capture selectors fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            NsightGpuTraceRunner.validate(command("missing"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NsightGpuTraceRunner.validate(command("bad-type") { captureType = "everything" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NsightGpuTraceRunner.validate(command("../escape") { passId = "composite/composite1" })
        }
    }

    @Test
    fun `managed export names are flat and deterministic`() {
        val command = command("trace") { passId = "composite/composite1" }

        assertEquals(
            listOf(
                "trace.nsight.bundle.json",
                "trace.nsight.REPRO_INFO.xls",
                "trace.nsight.FRAME.xls",
                "trace.nsight.GPUTRACE_FRAME.xls",
                "trace.nsight.D3DPERF_EVENTS.xls",
                "trace.nsight.GPUTRACE_REGIMES.xls",
                "trace.nsight.log",
            ),
            NsightGpuTraceRunner.artifactFileNames(command),
        )
    }

    @Test
    fun `replayer jars resolve from the game-side vibris directory`() {
        val root = temp.resolve("vibris")

        assertEquals(root.resolve("replayer-gl.jar"), NsightGpuTraceRunner.replayerJarPath(root, "gl"))
        assertEquals(root.resolve("replayer-vk.jar"), NsightGpuTraceRunner.replayerJarPath(root, "vk"))
    }

    private fun command(
        artifactName: String,
        capture: NsightGpuTrace.Builder.() -> Unit = {},
    ): NsightGpuTrace = NsightGpuTrace.newBuilder()
        .apply(capture)
        .setArtifactName(artifactName)
        .setReplayBackend("gl")
        .setArchitecture("Ada")
        .setMetricSetName("Throughput Metrics")
        .setReplayFrames(300)
        .setStartAfterMs(1_000)
        .setMaxDurationMs(1_000)
        .setTimeoutSeconds(300)
        .setTimeEveryAction(true)
        .setGpuClocks("base")
        .build()
}
