package dev.vibris.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal data class ServerConfiguration(
    val address: InetSocketAddress,
    val paths: VibrisBootstrap.Config,
    val artifactQuotaBytes: Long,
    val maxSourceBytes: Long,
    val maxSourceFiles: Int,
    val maxGlobalQueue: Int,
    val maxActionsPerJob: Int,
) {
    companion object {
        const val DEFAULT_PORT = 50_051
        const val DEFAULT_MAX_SOURCE_BYTES = 512L * 1024 * 1024
        const val DEFAULT_MAX_SOURCE_FILES = 100_000
        const val DEFAULT_MAX_GLOBAL_QUEUE = 32
        const val DEFAULT_MAX_ACTIONS_PER_JOB = 64

        private const val MAX_CONFIG_BYTES = 1024 * 1024L
        private val JSON = Json { isLenient = false }
        private val REQUIRED_KEYS = setOf(
            "schema_version",
            "listen_address",
            "pending_shaders_root",
            "artifact_root",
            "artifact_quota_bytes",
            "shaderpack_root",
            "max_source_bytes",
            "max_source_files",
            "max_global_queue",
            "max_actions_per_job",
        )

        fun defaults(paths: VibrisBootstrap.Config): ServerConfiguration = ServerConfiguration(
            InetSocketAddress("127.0.0.1", paths.port),
            paths,
            ArtifactManager.DEFAULT_QUOTA_BYTES,
            DEFAULT_MAX_SOURCE_BYTES,
            DEFAULT_MAX_SOURCE_FILES,
            DEFAULT_MAX_GLOBAL_QUEUE,
            DEFAULT_MAX_ACTIONS_PER_JOB,
        )

        fun defaults(pendingRoot: Path, artifactRoot: Path): ServerConfiguration {
            val pending = pendingRoot.toAbsolutePath().normalize()
            val artifacts = artifactRoot.toAbsolutePath().normalize()
            return defaults(VibrisBootstrap.Config(0, pending, artifacts, pending))
        }

        @Throws(Failure::class)
        fun load(gameDirectory: Path): ServerConfiguration {
            val game = gameDirectory.toAbsolutePath().normalize()
            val file = game.resolve("config/vibris/server.json")
            var parsedAddress: InetSocketAddress? = null
            try {
                val root = read(file)
                require(root.keys == REQUIRED_KEYS) {
                    "server.json fields do not match schema version 1"
                }
                require(number(root, "schema_version") == 1L) { "schema_version must be 1" }
                val address = address(text(root, "listen_address"))
                parsedAddress = address
                val pending = path(game, text(root, "pending_shaders_root"))
                val artifacts = path(game, text(root, "artifact_root"))
                val shaderpack = path(game, text(root, "shaderpack_root"))
                requireWritableDirectory(pending, "pending_shaders_root")
                requireWritableDirectory(artifacts, "artifact_root")
                requireWritableDirectory(shaderpack, "shaderpack_root")
                val quota = positive(root, "artifact_quota_bytes")
                val sourceBytes = positive(root, "max_source_bytes")
                val sourceFiles = positiveInt(root, "max_source_files")
                val queue = positiveInt(root, "max_global_queue")
                val actions = positiveInt(root, "max_actions_per_job")
                return ServerConfiguration(
                    address,
                    VibrisBootstrap.Config(address.port, pending, artifacts, shaderpack),
                    quota,
                    sourceBytes,
                    sourceFiles,
                    queue,
                    actions,
                )
            } catch (failure: Failure) {
                if (failure.address != null || parsedAddress == null) {
                    throw failure
                }
                throw Failure(failure.message ?: "server.json is invalid", failure, parsedAddress)
            } catch (exception: Exception) {
                val message = exception.message?.takeIf(String::isNotBlank) ?: "server.json is invalid"
                throw Failure(message, exception, parsedAddress)
            }
        }

        private fun read(file: Path): JsonObject {
            try {
                if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                    throw Failure("server.json is missing or is not an ordinary file")
                }
                val size = Files.size(file)
                if (size <= 0 || size > MAX_CONFIG_BYTES) {
                    throw Failure("server.json has an invalid size")
                }
                return JSON.parseToJsonElement(Files.readString(file)).jsonObject
            } catch (failure: Failure) {
                throw failure
            } catch (exception: Exception) {
                throw Failure("server.json could not be read or parsed", exception)
            }
        }

        private fun address(value: String): InetSocketAddress {
            val separator = value.lastIndexOf(':')
            require(separator > 0 && value.substring(0, separator) == "127.0.0.1") {
                "listen_address must use 127.0.0.1"
            }
            val port = value.substring(separator + 1).toIntOrNull()
            require(port != null && port in 1..65_535) { "listen_address port is invalid" }
            return InetSocketAddress("127.0.0.1", port)
        }

        private fun path(game: Path, value: String): Path {
            require(value.isNotBlank()) { "configured paths must not be blank" }
            val candidate = Path.of(value)
            if (candidate.isAbsolute) {
                return candidate.normalize()
            }
            val normalized = value.replace('\\', '/')
            return if (
                normalized.startsWith(".minecraft/") &&
                game.fileName?.toString().equals(".minecraft", ignoreCase = true)
            ) {
                game.resolve(normalized.removePrefix(".minecraft/"))
            } else {
                game.resolve(candidate)
            }.toAbsolutePath().normalize()
        }

        private fun requireWritableDirectory(path: Path, field: String) {
            require(
                Files.isDirectory(path, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(path) &&
                    Files.isWritable(path),
            ) {
                "$field is missing or not writable"
            }
        }

        private fun text(root: JsonObject, field: String): String =
            root.getValue(field).jsonPrimitive.contentOrNull
                ?: throw IllegalArgumentException("$field must be a string")

        private fun number(root: JsonObject, field: String): Long =
            root.getValue(field).jsonPrimitive.longOrNull
                ?: throw IllegalArgumentException("$field must be an integer")

        private fun positive(root: JsonObject, field: String): Long =
            number(root, field).also { require(it > 0) { "$field must be positive" } }

        private fun positiveInt(root: JsonObject, field: String): Int {
            val value = positive(root, field)
            require(value <= Int.MAX_VALUE) { "$field is too large" }
            return value.toInt()
        }
    }

    class Failure(
        message: String,
        cause: Throwable? = null,
        val address: InetSocketAddress? = null,
    ) : IOException(message, cause)
}