package dev.vibris.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class PackagedClientAutomationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @AfterEach
    fun clearProperties() {
        PROPERTIES.forEach(System::clearProperty)
    }

    @Test
    fun `start writes ownership receipt and appends JSONL`() {
        val paths = configure()
        val errors = mutableListOf<String>()

        val probe = requireNotNull(PackagedClientAutomation.start(temporaryDirectory, {}, { message, _ ->
            errors += message
        }))
        probe.appendJsonLine("{\"run_id\":\"$RUN_ID\",\"type\":\"server_ready\"}")

        val receipt = Json.parseToJsonElement(Files.readString(paths.receipt)).jsonObject
        assertEquals(ProcessHandle.current().pid(), receipt.getValue("pid").jsonPrimitive.content.toLong())
        assertEquals(RUN_ID, receipt.getValue("run_id").jsonPrimitive.content)
        assertEquals(temporaryDirectory.toString(), receipt.getValue("game_dir").jsonPrimitive.content)
        Instant.parse(receipt.getValue("started_at_utc").jsonPrimitive.content)
        assertEquals(
            "{\"run_id\":\"$RUN_ID\",\"type\":\"server_ready\"}${System.lineSeparator()}",
            Files.readString(paths.events),
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `poll command reports runtime metrics and stops once`() {
        val paths = configure()
        val stopCount = AtomicInteger()
        val errors = mutableListOf<String>()
        val probe = requireNotNull(PackagedClientAutomation.start(temporaryDirectory, stopCount::incrementAndGet) {
                message, _ -> errors += message
        })
        Files.writeString(
            paths.command,
            "{\"run_id\":\"$RUN_ID\",\"command\":\"runtime-metrics\",\"request_id\":\"metrics-1\"}",
        )

        probe.pollCommand()

        val metrics = Json.parseToJsonElement(Files.readString(paths.events).trim()).jsonObject
        assertEquals(RUN_ID, metrics.getValue("run_id").jsonPrimitive.content)
        assertEquals("runtime_metrics", metrics.getValue("type").jsonPrimitive.content)
        assertEquals("metrics-1", metrics.getValue("request_id").jsonPrimitive.content)
        assertTrue(metrics.getValue("heap_used_bytes").jsonPrimitive.content.toLong() >= 0)
        assertTrue(metrics.getValue("direct_buffer_memory_used_bytes").jsonPrimitive.content.toLong() >= 0)
        assertTrue(metrics.getValue("thread_count").jsonPrimitive.content.toInt() > 0)
        assertFalse(Files.exists(paths.command))

        Files.writeString(paths.command, "{\"run_id\":\"$RUN_ID\",\"command\":\"stop\"}")
        probe.pollCommand()
        probe.pollCommand()

        assertEquals(1, stopCount.get())
        assertFalse(Files.exists(paths.command))
        assertTrue(errors.isEmpty())
    }

    private fun configure(): Paths {
        val paths = Paths(
            temporaryDirectory.resolve("vibris-automation-events.jsonl"),
            temporaryDirectory.resolve("vibris-automation-receipt.json"),
            temporaryDirectory.resolve("vibris-automation-command.json"),
        )
        System.setProperty(PackagedClientAutomation.RUN_ID_PROPERTY, RUN_ID)
        return paths
    }

    private data class Paths(val events: Path, val receipt: Path, val command: Path)

    private companion object {
        const val RUN_ID = "1d79203e-0610-4840-8f14-c1a7cf2776bc"
        val PROPERTIES = listOf(PackagedClientAutomation.RUN_ID_PROPERTY)
    }
}