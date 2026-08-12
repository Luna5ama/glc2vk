package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShaderDebugStateTest {
    @Test
    fun retainsTheNewestOneHundredErrors() {
        val temp = createTempDirectory("vibris-shader-debug-state")
        try {
            val control = ShaderDebugControl(EmptyHost(temp))
            repeat(101) { index ->
                control.recordError("type", "file", "message", "trace", index.toLong())
            }
            val inspection = control.inspect()
            val errors = inspection["errors"]!!.jsonArray
            assertFalse(inspection["pack_loaded"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(100, errors.size)
            assertEquals("1", errors.first().jsonObject["timestamp"]!!.jsonPrimitive.content)
            assertEquals("100", errors.last().jsonObject["timestamp"]!!.jsonPrimitive.content)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun timingHistoryReturnsPercentilesForEveryCapturedSample() {
        val history = TimingHistory()
        (1L..100L).forEach(history::add)
        val stats = history.stats()
        assertEquals(50, stats.average)
        assertEquals(6, stats.p5)
        assertEquals(95, stats.p95)
        assertEquals(51, stats.p50)
        assertEquals((1L..100L).toList(), stats.samples)
    }

    @Test
    fun gpuMetricsCaptureWaitsForTheRequestedFutureFrames() {
        val control = ShaderDebugControl(EmptyHost(Path.of(".")))

        val result = control.captureMetrics(2).toCompletableFuture()
        control.tickFrame()
        assertFalse(result.isDone)
        control.tickFrame()

        assertTrue(result.isDone)
        val json = result.join()
        assertEquals("ns", json.getValue("timingUnit").jsonPrimitive.content)
        assertEquals(2, json.getValue("sampledFrames").jsonPrimitive.content.toInt())
        assertEquals(emptySet(), json["gpuTimings"]!!.jsonObject.keys)
    }

    @Test
    fun preservesMultipleProgramIdentitiesInsideOneFrameworkPass() {
        val histories = GpuTimingHistories()
        histories.add(
            GpuTimingTarget.Aggregate(
                GpuTimingScope("begin3_total", GpuTimingScopeKind.FRAMEWORK_TOTAL, "begin3", null),
            ),
            900,
        )
        histories.add(
            GpuTimingTarget.Program(
                GpuTimingProgram.compute(
                    "begin3",
                    "CloudAmbientSample.comp.glsl",
                    dispatch = "direct:1x1x1",
                ),
                "begin3",
            ),
            100,
        )
        histories.add(
            GpuTimingTarget.Program(
                GpuTimingProgram.compute(
                    "begin3_a",
                    "GenerateSkyViewLUT.comp.glsl",
                    mapOf("SKY_VIEW_SAMPLES" to "32"),
                    "direct:120x68x1",
                ),
                "begin3",
            ),
            300,
        )

        val snapshot = histories.snapshot()
        val programs = snapshot.programTimings.associateBy { it.program.program }

        assertEquals(setOf("begin3_total", "begin3_compute"), snapshot.aggregateTimings.keys)
        assertEquals(900, snapshot.aggregateTimings.getValue("begin3_total").average)
        assertEquals(200, snapshot.aggregateTimings.getValue("begin3_compute").average)
        assertEquals(setOf("begin3", "begin3_a"), programs.keys)
        assertEquals("CloudAmbientSample.comp.glsl", programs.getValue("begin3").program.sourceFile)
        assertEquals("GenerateSkyViewLUT.comp.glsl", programs.getValue("begin3_a").program.sourceFile)
        assertEquals("32", programs.getValue("begin3_a").program.defines.getValue("SKY_VIEW_SAMPLES"))
        assertEquals("direct:120x68x1", programs.getValue("begin3_a").program.dispatch)
        assertEquals(300, programs.getValue("begin3_a").statistics.average)
        assertEquals("begin3_compute", programs.getValue("begin3_a").compatibilityMetric)
        assertEquals(listOf(300L), programs.getValue("begin3_a").statistics.samples)

        val json = ShaderDebugControl(EmptyHost(Path.of("."))).metricsJson(snapshot)
        val skyView = json.getValue("gpuProgramTimings").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("program").jsonPrimitive.content == "begin3_a" }
        assertEquals("program", skyView.getValue("kind").jsonPrimitive.content)
        assertEquals("compute", skyView.getValue("stage").jsonPrimitive.content)
        assertEquals("GenerateSkyViewLUT.comp.glsl", skyView.getValue("source").jsonPrimitive.content)
        assertEquals(
            "32",
            skyView.getValue("defines").jsonObject.getValue("SKY_VIEW_SAMPLES").jsonPrimitive.content,
        )
        assertEquals("direct:120x68x1", skyView.getValue("dispatch").jsonPrimitive.content)
        assertEquals("begin3", skyView.getValue("framework_pass").jsonPrimitive.content)
        assertEquals("begin3_compute", skyView.getValue("compatibility_metric").jsonPrimitive.content)
        assertEquals(300, skyView.getValue("statistics").jsonObject.getValue("avg").jsonPrimitive.content.toLong())
        assertEquals(
            listOf("300"),
            skyView.getValue("statistics").jsonObject.getValue("samples").jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun patchedShaderIdentityIsStableUntilOutputChanges() {
        val temp = createTempDirectory("vibris-patched-identity")
        try {
            val output = Files.createDirectories(temp.resolve("patched_shaders"))
            Files.writeString(output.resolve("composite.fsh"), "first")
            val control = ShaderDebugControl(DebugHost(temp))

            val first = control.inspect().getValue("patched_shader").jsonObject
            val retry = control.inspect().getValue("patched_shader").jsonObject
            Files.writeString(output.resolve("composite.fsh"), "second")
            val changed = control.inspect().getValue("patched_shader").jsonObject

            assertTrue(first.getValue("available").jsonPrimitive.content.toBoolean())
            assertEquals(first.getValue("sha256"), retry.getValue("sha256"))
            assertEquals("1", retry.getValue("generation").jsonPrimitive.content)
            assertFalse(first.getValue("sha256") == changed.getValue("sha256"))
            assertEquals("2", changed.getValue("generation").jsonPrimitive.content)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    private class EmptyHost(private val root: Path) : ShaderDebugHost {
        override fun shaderPackName(): String? = null
        override fun reloadShaders() = Unit
        override fun gameDirectory() = root
        override fun debugShadersEnabled() = false
        override fun storageBuffers() = emptyList<StorageBufferInfo>()
        override fun textureCatalog() = TextureCatalog(emptyList())
        override fun resolveTexture(name: String): Int? = null
    }

    private class DebugHost(private val root: Path) : ShaderDebugHost {
        override fun shaderPackName() = "vibris"
        override fun reloadShaders() = Unit
        override fun gameDirectory() = root
        override fun debugShadersEnabled() = true
        override fun storageBuffers() = emptyList<StorageBufferInfo>()
        override fun textureCatalog() = TextureCatalog(emptyList())
        override fun resolveTexture(name: String): Int? = null
    }
}
