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

class PackagedClientProbe private constructor(
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
                reportError.accept("Failed to write a Phase 4 probe event.", exception)
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
            reportError.accept("Failed to process the Phase 4 probe command.", exception)
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
        ): PackagedClientProbe? {
            val runId = System.getProperty("vibris.phase4.runId") ?: return null
            val parsed = UUID.fromString(runId)
            if (parsed.toString() != runId) {
                throw IOException("Phase 4 run ID is not canonical")
            }
            val gameDirectory = actualGameDirectory.toAbsolutePath().normalize()
            if (gameDirectory != propertyPath("vibris.phase4.gameDir")) {
                throw IOException("Phase 4 game directory does not match")
            }
            val eventFile = ownedFile("vibris.phase4.eventFile", gameDirectory)
            val receiptFile = ownedFile("vibris.phase4.receiptFile", gameDirectory)
            val commandFile = ownedFile("vibris.phase4.commandFile", gameDirectory)
            Files.createDirectories(gameDirectory)
            Files.createDirectories(eventFile.parent)
            return PackagedClientProbe(
                Configuration(runId, gameDirectory, eventFile, receiptFile, commandFile),
                stopRuntime,
                reportError,
            ).also(PackagedClientProbe::writeReceipt)
        }

        @Throws(IOException::class)
        private fun ownedFile(property: String, gameDirectory: Path): Path {
            val path = propertyPath(property)
            if (!path.startsWith(gameDirectory) || path == gameDirectory) {
                throw IOException("$property must be a file below the Phase 4 game directory")
            }
            return path
        }

        @Throws(IOException::class)
        private fun propertyPath(name: String): Path {
            val value = System.getProperty(name)
            if (value.isNullOrBlank()) {
                throw IOException("Missing Phase 4 property: $name")
            }
            return Path.of(value).toAbsolutePath().normalize()
        }
    }
}