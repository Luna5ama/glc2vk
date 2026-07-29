package dev.vibris.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.function.BiConsumer

class PackagedClientAutomation private constructor(
    private val configuration: Configuration,
    private val stopRuntime: Runnable,
    private val reportError: BiConsumer<String, Throwable>,
) {
    private val appendLock = Any()

    @Volatile
    private var stopRequested = false

    fun runId(): String = configuration.runId

    fun appendJsonLine(json: String) {
        synchronized(appendLock) {
            try {
                Files.writeString(
                    configuration.eventFile,
                    json + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            } catch (exception: IOException) {
                reportError.accept("Failed to write a Vibris automation event.", exception)
            }
        }
    }

    fun pollCommand() {
        if (stopRequested || !Files.isRegularFile(configuration.commandFile)) {
            return
        }
        try {
            val command = Json.parseToJsonElement(Files.readString(configuration.commandFile)).jsonObject
            if (command.getValue("run_id").jsonPrimitive.content != configuration.runId) {
                return
            }
            when (command.getValue("command").jsonPrimitive.content) {
                "runtime-metrics" -> {
                    Files.deleteIfExists(configuration.commandFile)
                    System.gc()
                    val directMemory = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
                        .filter { it.name == "direct" }
                        .sumOf(BufferPoolMXBean::getMemoryUsed)
                    val event = buildJsonObject {
                        put("run_id", configuration.runId)
                        put("type", "runtime_metrics")
                        if ("request_id" in command) {
                            put("request_id", command.getValue("request_id").jsonPrimitive.content)
                        }
                        put("heap_used_bytes", ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
                        put("direct_buffer_memory_used_bytes", directMemory)
                        put("thread_count", ManagementFactory.getThreadMXBean().threadCount)
                    }
                    appendJsonLine(event.toString())
                }

                "stop" -> {
                    stopRequested = true
                    Files.deleteIfExists(configuration.commandFile)
                    stopRuntime.run()
                }

                else -> return
            }
        } catch (exception: Exception) {
            reportError.accept("Failed to process a Vibris automation command.", exception)
        }
    }

    @Throws(IOException::class)
    private fun writeReceipt() {
        val receipt = buildJsonObject {
            put("pid", ProcessHandle.current().pid())
            put("started_at_utc", Instant.now().toString())
            put("run_id", configuration.runId)
            put("game_dir", configuration.gameDirectory.toString())
        }
        Files.writeString(configuration.receiptFile, receipt.toString(), StandardOpenOption.CREATE_NEW)
    }

    private data class Configuration(
        val runId: String,
        val gameDirectory: Path,
        val eventFile: Path,
        val receiptFile: Path,
        val commandFile: Path,
    )

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun start(
            actualGameDirectory: Path,
            stopRuntime: Runnable,
            reportError: BiConsumer<String, Throwable>,
        ): PackagedClientAutomation? {
            val runId = System.getProperty(RUN_ID_PROPERTY) ?: return null
            val parsed = UUID.fromString(runId)
            if (parsed.toString() != runId) {
                throw IOException("Vibris automation run ID is not canonical")
            }
            val gameDirectory = actualGameDirectory.toAbsolutePath().normalize()
            val eventFile = gameDirectory.resolve(EVENT_FILE_NAME)
            val receiptFile = gameDirectory.resolve(RECEIPT_FILE_NAME)
            val commandFile = gameDirectory.resolve(COMMAND_FILE_NAME)
            Files.createDirectories(gameDirectory)
            return PackagedClientAutomation(
                Configuration(runId, gameDirectory, eventFile, receiptFile, commandFile),
                stopRuntime,
                reportError,
            ).also(PackagedClientAutomation::writeReceipt)
        }

        const val RUN_ID_PROPERTY = "vibris.automation.runId"
        private const val EVENT_FILE_NAME = "vibris-automation-events.jsonl"
        private const val RECEIPT_FILE_NAME = "vibris-automation-receipt.json"
        private const val COMMAND_FILE_NAME = "vibris-automation-command.json"
    }
}