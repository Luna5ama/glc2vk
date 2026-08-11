package dev.vibris.core

import dev.vibris.protocol.v2.ArtifactFormat
import dev.vibris.protocol.v2.ArtifactKind
import dev.vibris.protocol.v2.ArtifactMetadata
import dev.vibris.protocol.v2.ArtifactRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

internal object ArtifactManifest {
    const val SCHEMA_VERSION = 2
    const val FILE_NAME = "manifest.json"

    private val JSON = Json { isLenient = false }
    private val ROOT_KEYS = setOf(
        "schema_version",
        "manifest_id",
        "workspace_id",
        "job_id",
        "request_id",
        "recipe",
        "created_at_unix_ms",
        "completed_at_unix_ms",
        "expires_at_unix_ms",
        "total_bytes",
        "files",
    )
    private val FILE_KEYS = setOf(
        "artifact_id",
        "relative_path",
        "kind",
        "format",
        "role",
        "media_type",
        "byte_size",
        "sha256",
        "created_at_unix_ms",
        "expires_at_unix_ms",
    )

    data class FileSpec(
        val kind: ArtifactKind,
        val format: ArtifactFormat,
        val role: ArtifactRole,
        val mediaType: String,
    )

    data class FileEntry(
        val artifactId: String,
        val relativePath: String,
        val spec: FileSpec,
        val byteSize: Long,
        val sha256: String,
        val createdAtUnixMs: Long,
        val expiresAtUnixMs: Long,
    ) {
        fun metadata(jobId: String, requestId: String, path: Path): ArtifactMetadata =
            ArtifactMetadata.newBuilder()
                .setArtifactId(artifactId)
                .setJobId(jobId)
                .setRequestId(requestId)
                .setRelativePath(path.toAbsolutePath().normalize().toString())
                .setKind(spec.kind)
                .setFormat(spec.format)
                .setRole(spec.role)
                .setMediaType(spec.mediaType)
                .setByteSize(byteSize)
                .setSha256(sha256)
                .setCreatedAtUnixMs(createdAtUnixMs)
                .setExpiresAtUnixMs(expiresAtUnixMs)
                .build()
    }

    data class Document(
        val manifestId: String,
        val workspaceId: String,
        val jobId: String,
        val requestId: String,
        val recipe: String,
        val createdAtUnixMs: Long,
        val completedAtUnixMs: Long,
        val expiresAtUnixMs: Long,
        val totalBytes: Long,
        val files: List<FileEntry>,
    )

    data class Encoded(val document: Document, val bytes: ByteArray, val sha256: String)

    @JvmStatic
    fun encode(document: Document): Encoded {
        val fileBytes = document.files.fold(0L) { total, file -> Math.addExact(total, file.byteSize) }
        var totalBytes = fileBytes
        while (true) {
            val candidate = document.copy(totalBytes = totalBytes)
            val bytes = (json(candidate).toString() + "\n").toByteArray(StandardCharsets.UTF_8)
            val inclusive = Math.addExact(fileBytes, bytes.size.toLong())
            if (inclusive == totalBytes) return Encoded(candidate, bytes, sha256(bytes))
            totalBytes = inclusive
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun decode(file: Path): Encoded {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw IOException("Artifact v2 manifest is not an ordinary file.")
        }
        val bytes = Files.readAllBytes(file)
        val root = try {
            JSON.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
        } catch (exception: Exception) {
            throw IOException("Artifact v2 manifest is malformed.", exception)
        }
        if (number(root, "schema_version") != SCHEMA_VERSION.toLong()) {
            throw UnsupportedVersionException("Artifact manifest schema_version must be 2.")
        }
        if (root.keys != ROOT_KEYS) throw IOException("Artifact v2 manifest fields do not match schema version 2.")
        val files = array(root, "files").map { element ->
            val entry = element.jsonObject
            if (entry.keys != FILE_KEYS) throw IOException("Artifact v2 file fields do not match schema version 2.")
            val relativePath = text(entry, "relative_path")
            if (Path.of(relativePath).fileName.toString() != relativePath || relativePath == FILE_NAME) {
                throw IOException("Artifact v2 relative_path is invalid.")
            }
            val hash = text(entry, "sha256")
            if (!hash.matches(Regex("[0-9a-f]{64}"))) throw IOException("Artifact v2 file SHA-256 is invalid.")
            FileEntry(
                text(entry, "artifact_id"),
                relativePath,
                FileSpec(
                    enumValue<ArtifactKind>(entry, "kind", "ARTIFACT_KIND_UNSPECIFIED"),
                    enumValue<ArtifactFormat>(entry, "format", "ARTIFACT_FORMAT_UNSPECIFIED"),
                    enumValue<ArtifactRole>(entry, "role", "ARTIFACT_ROLE_UNSPECIFIED"),
                    text(entry, "media_type"),
                ),
                nonNegative(entry, "byte_size"),
                hash,
                nonNegative(entry, "created_at_unix_ms"),
                positive(entry, "expires_at_unix_ms"),
            )
        }
        val document = Document(
            text(root, "manifest_id"),
            text(root, "workspace_id"),
            text(root, "job_id"),
            text(root, "request_id"),
            text(root, "recipe"),
            nonNegative(root, "created_at_unix_ms"),
            nonNegative(root, "completed_at_unix_ms"),
            positive(root, "expires_at_unix_ms"),
            nonNegative(root, "total_bytes"),
            files,
        )
        if (document.createdAtUnixMs > document.completedAtUnixMs ||
            document.completedAtUnixMs >= document.expiresAtUnixMs ||
            files.map(FileEntry::relativePath).toSet().size != files.size ||
            files.map(FileEntry::artifactId).toSet().size != files.size ||
            files.any { it.createdAtUnixMs != document.createdAtUnixMs ||
                it.expiresAtUnixMs != document.expiresAtUnixMs }
        ) throw IOException("Artifact v2 manifest identities or lifecycle timestamps are inconsistent.")
        val encoded = encode(document)
        if (!bytes.contentEquals(encoded.bytes) || document.totalBytes != encoded.document.totalBytes) {
            throw IOException("Artifact v2 manifest is not canonical or has invalid totals.")
        }
        return Encoded(document, bytes, sha256(bytes))
    }

    @JvmStatic
    fun schemaVersion(file: Path): Long? {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return null
        return try {
            JSON.parseToJsonElement(Files.readString(file)).jsonObject["schema_version"]?.jsonPrimitive?.longOrNull
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @JvmStatic
    fun reservationBytes(fileCount: Int): Long {
        require(fileCount >= 0) { "fileCount must not be negative" }
        return Math.addExact(8L * 1024, Math.multiplyExact(fileCount.toLong(), 2L * 1024))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun sha256(file: Path): String = Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun json(document: Document): JsonObject = buildJsonObject {
        put("schema_version", SCHEMA_VERSION)
        put("manifest_id", document.manifestId)
        put("workspace_id", document.workspaceId)
        put("job_id", document.jobId)
        put("request_id", document.requestId)
        put("recipe", document.recipe)
        put("created_at_unix_ms", document.createdAtUnixMs)
        put("completed_at_unix_ms", document.completedAtUnixMs)
        put("expires_at_unix_ms", document.expiresAtUnixMs)
        put("total_bytes", document.totalBytes)
        put("files", buildJsonArray {
            document.files.forEach { file ->
                add(buildJsonObject {
                    put("artifact_id", file.artifactId)
                    put("relative_path", file.relativePath)
                    put("kind", file.spec.kind.name)
                    put("format", file.spec.format.name)
                    put("role", file.spec.role.name)
                    put("media_type", file.spec.mediaType)
                    put("byte_size", file.byteSize)
                    put("sha256", file.sha256)
                    put("created_at_unix_ms", file.createdAtUnixMs)
                    put("expires_at_unix_ms", file.expiresAtUnixMs)
                })
            }
        })
    }

    private inline fun <reified T : Enum<T>> enumValue(root: JsonObject, field: String, unspecified: String): T {
        val value = text(root, field)
        val parsed = enumValues<T>().firstOrNull { it.name == value }
            ?: throw IOException("Artifact v2 $field is invalid.")
        if (parsed.name == unspecified) throw IOException("Artifact v2 $field must be specified.")
        return parsed
    }

    private fun text(root: JsonObject, field: String): String =
        root[field]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IOException("Artifact v2 $field must be a non-empty string.")

    private fun number(root: JsonObject, field: String): Long =
        root[field]?.jsonPrimitive?.longOrNull
            ?: throw IOException("Artifact v2 $field must be an integer.")

    private fun nonNegative(root: JsonObject, field: String): Long =
        number(root, field).also { if (it < 0) throw IOException("Artifact v2 $field must not be negative.") }

    private fun positive(root: JsonObject, field: String): Long =
        number(root, field).also { if (it <= 0) throw IOException("Artifact v2 $field must be positive.") }

    private fun array(root: JsonObject, field: String): JsonArray =
        try {
            root.getValue(field).jsonArray
        } catch (exception: Exception) {
            throw IOException("Artifact v2 $field must be an array.", exception)
        }

    class UnsupportedVersionException(message: String) : IOException(message)
}
