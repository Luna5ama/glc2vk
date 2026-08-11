package dev.vibris.core

import dev.vibris.api.ReloadResult
import dev.vibris.protocol.v2.ArtifactCapacity
import dev.vibris.protocol.v2.ArtifactFormat
import dev.vibris.protocol.v2.ArtifactKind
import dev.vibris.protocol.v2.ArtifactMetadata
import dev.vibris.protocol.v2.ArtifactRole
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.LinkedHashMap
import java.util.UUID

internal class ArtifactManager internal constructor(
    root: Path,
    private val quotaBytesValue: Long,
    private val ttlValue: Duration,
    private val clock: Clock,
) : ShaderLogSink {
    private val rootValue = root.toAbsolutePath().normalize()
    private lateinit var rootIdentity: OwnedPathIdentity
    private val completed = LinkedHashMap<String, CompletedJob>()
    private val reservations = HashMap<ArtifactJobTransaction, Long>()
    private var usedBytesValue = 0L
    private var reservedBytesValue = 0L
    private var nextExpirationScan = 0L

    init {
        require(quotaBytesValue > 0) { "quotaBytes must be positive" }
        require(!ttlValue.isZero && !ttlValue.isNegative) { "ttl must be positive" }
        try {
            OwnedPathIdentity.createDirectoriesSafely(rootValue)
            rootIdentity = OwnedPathIdentity.captureDirectory(rootValue)
            ArtifactFiles.verifyWritable(rootValue, rootIdentity)
            recoverV2()
            expire(force = true)
        } catch (exception: Exception) {
            throw IllegalStateException("Could not initialize the artifact v2 root.", exception)
        }
    }

    constructor(root: Path) : this(root, DEFAULT_QUOTA_BYTES, DEFAULT_TTL, Clock.systemUTC())
    constructor(root: Path, quotaBytes: Long) : this(root, quotaBytes, DEFAULT_TTL, Clock.systemUTC())
    constructor(root: Path, quotaBytes: Long, clock: Clock) : this(root, quotaBytes, DEFAULT_TTL, clock)
    constructor(root: Path, quotaBytes: Long, ttl: Duration) : this(root, quotaBytes, ttl, Clock.systemUTC())

    fun root(): Path = rootValue
    fun quotaBytes(): Long = quotaBytesValue
    fun ttl(): Duration = ttlValue

    @Synchronized
    fun usedBytes(): Long {
        expireUnchecked()
        return usedBytesValue
    }

    @Synchronized
    fun reservedBytes(): Long = reservedBytesValue

    @Synchronized
    fun capacity(estimatedJobBytes: Long = 0): ArtifactCapacity {
        require(estimatedJobBytes >= 0) { "estimatedJobBytes must not be negative" }
        expireUnchecked()
        val occupied = saturatedAdd(usedBytesValue, reservedBytesValue, estimatedJobBytes)
        return ArtifactCapacity.newBuilder()
            .setCapBytes(quotaBytesValue)
            .setUsedBytes(usedBytesValue)
            .setReservedBytes(reservedBytesValue)
            .setEstimatedJobBytes(estimatedJobBytes)
            .setWarning(occupied >= warningThreshold())
            .setFits(occupied <= quotaBytesValue)
            .build()
    }

    @Synchronized
    @Throws(IOException::class)
    fun beginJob(
        workspaceId: String,
        jobId: String,
        requestId: String,
        recipe: String,
        estimatedBytes: Long,
    ): JobTransaction {
        require(estimatedBytes >= 0) { "estimatedBytes must not be negative" }
        require(jobId.isNotBlank()) { "jobId must not be blank" }
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(recipe.isNotBlank()) { "recipe must not be blank" }
        if (estimatedBytes > quotaBytesValue) throw JobTooLargeException(estimatedBytes, quotaBytesValue)
        verifyRootIdentity()
        expire(force = true)
        requireCapacity(estimatedBytes)
        val workspace = rootValue.resolve(workspaceSegment(workspaceId))
        val job = workspace.resolve(segment("job", jobId))
        val directory = job.resolve(segment("request", requestId))
        val temporary = directory.resolveSibling(directory.fileName.toString() + V2_TEMP_SUFFIX)
        OwnedPathIdentity.createDirectoriesSafely(job)
        verifyRootIdentity()
        val jobIdentity = OwnedPathIdentity.captureDirectory(job)
        if (Files.exists(directory, NOFOLLOW_LINKS) || Files.exists(temporary, NOFOLLOW_LINKS)) {
            throw IOException("Artifact v2 request already exists.")
        }
        Files.createDirectory(temporary)
        val createdAt = clock.millis()
        val transaction = try {
            JobTransaction(
                temporary, directory, jobIdentity, workspaceId, jobId, requestId, recipe,
                createdAt, deadline(createdAt),
            )
        } catch (exception: Exception) {
            ArtifactFiles.deleteTree(temporary, jobIdentity)
            throw exception
        }
        reservations[transaction] = estimatedBytes
        reservedBytesValue = Math.addExact(reservedBytesValue, estimatedBytes)
        return transaction
    }

    @Synchronized
    @Throws(IOException::class)
    fun manifests(workspaceId: String, jobId: String? = null, requestId: String? = null): List<ManagedManifest> {
        val workspace = workspaceSegment(workspaceId)
        expire(force = true)
        return completed.values.asSequence()
            .filter { it.workspaceSegment == workspace }
            .filter { jobId == null || it.encoded.document.jobId == jobId }
            .filter { requestId == null || it.encoded.document.requestId == requestId }
            .onEach(::verifyCompleted)
            .sortedByDescending { it.encoded.document.completedAtUnixMs }
            .map(::managed)
            .toList()
    }

    @Synchronized
    @Throws(IOException::class)
    fun manifest(workspaceId: String, manifestId: String): ManagedManifest {
        val workspace = workspaceSegment(workspaceId)
        expire(force = true)
        val found = completed[manifestId] ?: throw IOException("Artifact manifest was not found.")
        if (found.workspaceSegment != workspace) throw OwnershipException("Artifact manifest belongs to another workspace.")
        verifyCompleted(found)
        return managed(found)
    }

    @Synchronized
    @Throws(IOException::class)
    fun delete(workspaceId: String, manifestId: String, expectedManifestSha256: String) {
        val workspace = workspaceSegment(workspaceId)
        if (!expectedManifestSha256.matches(Regex("[0-9a-f]{64}"))) {
            throw IOException("expected_manifest_sha256 must be 64 lowercase hexadecimal characters.")
        }
        expire(force = true)
        val found = completed[manifestId] ?: throw IOException("Artifact manifest was not found.")
        if (found.workspaceSegment != workspace) throw OwnershipException("Artifact manifest belongs to another workspace.")
        verifyCompleted(found)
        if (found.encoded.sha256 != expectedManifestSha256) {
            throw DeletionRaceException("Artifact manifest SHA-256 changed before deletion.")
        }
        ArtifactFiles.deleteTree(found.directory, found.parentIdentity)
        completed.remove(manifestId)
        usedBytesValue = Math.subtractExact(usedBytesValue, found.encoded.document.totalBytes)
    }

    @Throws(IOException::class)
    override fun writeShaderLog(
        workspaceId: String,
        requestId: String,
        diagnostics: List<ReloadResult.Diagnostic>,
    ): ArtifactMetadata {
        val bytes = logText(diagnostics).toByteArray(StandardCharsets.UTF_8)
        val spec = ArtifactManifest.FileSpec(
            ArtifactKind.ARTIFACT_KIND_SHADER_COMPILE_LOG,
            ArtifactFormat.ARTIFACT_FORMAT_TEXT,
            ArtifactRole.ARTIFACT_ROLE_DIAGNOSTIC,
            "text/plain; charset=utf-8",
        )
        val committed = beginJob(workspaceId, requestId, requestId, "shader_log", bytes.size.toLong()).use { transaction ->
            transaction.open("shader.log").use { output -> output.write(bytes) }
            transaction.commit(mapOf("shader.log" to spec))
        }
        return committed.metadata().getValue("shader.log")
    }

    @Synchronized
    @Throws(IOException::class)
    fun reserve(transaction: ArtifactJobTransaction, bytes: Long) {
        val current = reservations[transaction] ?: throw IOException("Artifact job is no longer active.")
        if (bytes > quotaBytesValue) throw JobTooLargeException(bytes, quotaBytesValue)
        if (bytes <= current) return
        expire(force = true)
        val additional = Math.subtractExact(bytes, current)
        requireCapacity(additional)
        reservations[transaction] = bytes
        reservedBytesValue = Math.addExact(reservedBytesValue, additional)
    }

    @Synchronized
    fun complete(transaction: ArtifactJobTransaction, encoded: ArtifactManifest.Encoded) {
        val reservation = reservations.remove(transaction)
        if (reservation != null) reservedBytesValue = Math.subtractExact(reservedBytesValue, reservation)
        val document = encoded.document
        val completedJob = CompletedJob(
            transaction.directory(), encoded, workspaceSegment(document.workspaceId), transaction.workspaceIdentity(),
        )
        completed[document.manifestId] = completedJob
        usedBytesValue = Math.addExact(usedBytesValue, document.totalBytes)
    }

    @Synchronized
    fun abort(transaction: ArtifactJobTransaction) {
        val reservation = reservations.remove(transaction)
        if (reservation != null) reservedBytesValue = Math.subtractExact(reservedBytesValue, reservation)
    }

    @Throws(IOException::class)
    fun verifyStorageIdentity(workspace: Path, workspaceIdentity: OwnedPathIdentity) {
        verifyRootIdentity()
        if (!workspaceIdentity.matchesDirectory(workspace)) throw IOException("Artifact v2 job directory changed identity.")
    }

    internal fun manifestId(workspaceId: String, jobId: String, requestId: String): String =
        segment("manifest", workspaceId + '\u0000' + jobId + '\u0000' + requestId)

    internal fun artifactId(workspaceId: String, jobId: String, requestId: String, fileName: String): String =
        segment("artifact", workspaceId + '\u0000' + jobId + '\u0000' + requestId + '\u0000' + fileName)

    internal fun now(): Long = clock.millis()

    private fun recoverV2() {
        Files.newDirectoryStream(rootValue).use { workspaces ->
            for (workspace in workspaces) {
                if (!Files.isDirectory(workspace, NOFOLLOW_LINKS) || Files.isSymbolicLink(workspace)) continue
                val workspaceIdentity = OwnedPathIdentity.captureDirectory(workspace)
                Files.newDirectoryStream(workspace).use { jobs ->
                    for (job in jobs) {
                        if (!Files.isDirectory(job, NOFOLLOW_LINKS) || Files.isSymbolicLink(job)) continue
                        val jobIdentity = OwnedPathIdentity.captureDirectory(job)
                        Files.newDirectoryStream(job).use { requests ->
                            for (request in requests) {
                                if (!Files.isDirectory(request, NOFOLLOW_LINKS) || Files.isSymbolicLink(request)) continue
                                if (request.fileName.toString().endsWith(V2_TEMP_SUFFIX)) continue
                                val manifest = request.resolve(ArtifactManifest.FILE_NAME)
                                if (ArtifactManifest.schemaVersion(manifest) != ArtifactManifest.SCHEMA_VERSION.toLong()) continue
                                val encoded = ArtifactManifest.decode(manifest)
                                if (workspace.fileName.toString() != workspaceSegment(encoded.document.workspaceId) ||
                                    job.fileName.toString() != segment("job", encoded.document.jobId) ||
                                    request.fileName.toString() != segment("request", encoded.document.requestId) ||
                                    encoded.document.manifestId != manifestId(
                                        encoded.document.workspaceId,
                                        encoded.document.jobId,
                                        encoded.document.requestId,
                                    ) ||
                                    encoded.document.files.any { file ->
                                        file.artifactId != artifactId(
                                            encoded.document.workspaceId,
                                            encoded.document.jobId,
                                            encoded.document.requestId,
                                            file.relativePath,
                                        )
                                    }
                                ) throw IOException("Artifact v2 manifest grouping does not match its path.")
                                val recovered = CompletedJob(request, encoded, workspace.fileName.toString(), jobIdentity)
                                verifyCompleted(recovered)
                                if (completed.put(encoded.document.manifestId, recovered) != null) {
                                    throw IOException("Artifact v2 manifest ID is repeated.")
                                }
                                usedBytesValue = Math.addExact(usedBytesValue, encoded.document.totalBytes)
                            }
                        }
                        if (!jobIdentity.matchesDirectory(job)) throw IOException("Artifact v2 job directory changed during recovery.")
                    }
                }
                if (!workspaceIdentity.matchesDirectory(workspace)) throw IOException("Artifact v2 workspace directory changed during recovery.")
            }
        }
    }

    @Throws(IOException::class)
    private fun expire(force: Boolean) {
        val now = clock.millis()
        if (!force && now < nextExpirationScan) return
        nextExpirationScan = saturatedAdd(now, EXPIRATION_SCAN_INTERVAL.toMillis())
        val expired = completed.values.filter { it.encoded.document.expiresAtUnixMs <= now }
        for (job in expired) {
            verifyCompleted(job)
            ArtifactFiles.deleteTree(job.directory, job.parentIdentity)
            completed.remove(job.encoded.document.manifestId)
            usedBytesValue = Math.subtractExact(usedBytesValue, job.encoded.document.totalBytes)
        }
    }

    private fun expireUnchecked() {
        try {
            expire(force = false)
        } catch (exception: IOException) {
            throw IllegalStateException("Artifact v2 expiration failed.", exception)
        }
    }

    @Throws(IOException::class)
    private fun verifyCompleted(job: CompletedJob) {
        verifyRootIdentity()
        val manifest = job.directory.resolve(ArtifactManifest.FILE_NAME)
        if (ArtifactManifest.sha256(manifest) != job.encoded.sha256) {
            throw DeletionRaceException("Artifact manifest changed after it was indexed.")
        }
        var total = Files.size(manifest)
        for (file in job.encoded.document.files) {
            val path = job.directory.resolve(file.relativePath)
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
                Files.size(path) != file.byteSize || ArtifactManifest.sha256(path) != file.sha256
            ) throw IOException("Artifact v2 file changed after it was indexed.")
            total = Math.addExact(total, file.byteSize)
        }
        if (total != job.encoded.document.totalBytes) throw IOException("Artifact v2 total bytes changed.")
    }

    private fun managed(job: CompletedJob): ManagedManifest {
        val document = job.encoded.document
        val files = document.files.map { file ->
            file.metadata(document.jobId, document.requestId, job.directory.resolve(file.relativePath))
        }
        return ManagedManifest(document, job.encoded.sha256, job.directory.resolve(ArtifactManifest.FILE_NAME), files)
    }

    @Throws(IOException::class)
    private fun verifyRootIdentity() {
        if (!rootIdentity.matchesDirectory(rootValue)) throw IOException("Artifact root directory changed identity.")
    }

    @Throws(QuotaExceededException::class)
    private fun requireCapacity(additionalBytes: Long) {
        if (saturatedAdd(usedBytesValue, reservedBytesValue, additionalBytes) > quotaBytesValue) {
            throw QuotaExceededException(quotaBytesValue)
        }
    }

    private fun warningThreshold(): Long = quotaBytesValue - quotaBytesValue / 5
    private fun deadline(createdAt: Long): Long = saturatedAdd(createdAt, ttlValue.toMillis())

    class CommittedJob(
        directory: Path,
        manifest: Path,
        artifacts: Map<String, Path>,
        fileByteSizes: Map<String, Long>,
        metadata: Map<String, ArtifactMetadata>,
        private val manifestIdValue: String,
        private val manifestSha256Value: String,
        private val expiresAtUnixMsValue: Long,
        private val byteSizeValue: Long,
    ) {
        private val directoryValue = directory.toAbsolutePath().normalize()
        private val manifestValue = manifest.toAbsolutePath().normalize()
        private val artifactsValue = java.util.Map.copyOf(artifacts)
        private val fileByteSizesValue = java.util.Map.copyOf(fileByteSizes)
        private val metadataValue = java.util.Map.copyOf(metadata)
        fun directory(): Path = directoryValue
        fun manifest(): Path = manifestValue
        fun artifacts(): Map<String, Path> = artifactsValue
        fun fileByteSizes(): Map<String, Long> = fileByteSizesValue
        fun metadata(): Map<String, ArtifactMetadata> = metadataValue
        fun manifestId(): String = manifestIdValue
        fun manifestSha256(): String = manifestSha256Value
        fun expiresAtUnixMs(): Long = expiresAtUnixMsValue
        fun byteSize(): Long = byteSizeValue
    }

    data class ManagedManifest(
        val document: ArtifactManifest.Document,
        val manifestSha256: String,
        val manifestPath: Path,
        val files: List<ArtifactMetadata>,
    )

    class JobTooLargeException(bytes: Long, quotaBytes: Long) :
        IOException("Artifact job requires $bytes bytes, exceeding quota $quotaBytes.")
    class QuotaExceededException(quotaBytes: Long) :
        IOException("Artifact quota $quotaBytes bytes cannot fit the requested reservation.")
    class OwnershipException(message: String) : IOException(message)
    class DeletionRaceException(message: String) : IOException(message)

    inner class JobTransaction internal constructor(
        temporary: Path,
        directory: Path,
        workspaceIdentity: OwnedPathIdentity,
        workspaceId: String,
        jobId: String,
        requestId: String,
        recipe: String,
        createdAtUnixMs: Long,
        expiresAtUnixMs: Long,
    ) : ArtifactJobTransaction(
        this@ArtifactManager, temporary, directory, workspaceIdentity, workspaceId, jobId, requestId,
        recipe, createdAtUnixMs, expiresAtUnixMs,
    )

    private data class CompletedJob(
        val directory: Path,
        val encoded: ArtifactManifest.Encoded,
        val workspaceSegment: String,
        val parentIdentity: OwnedPathIdentity,
    )

    companion object {
        const val DEFAULT_QUOTA_BYTES = 3L * 1024 * 1024 * 1024
        @JvmField val DEFAULT_TTL: Duration = Duration.ofHours(168)
        @JvmField val EXPIRATION_SCAN_INTERVAL: Duration = Duration.ofMinutes(1)
        private const val V2_TEMP_SUFFIX = ".v2.tmp"

        private fun logText(diagnostics: List<ReloadResult.Diagnostic>): String {
            if (diagnostics.isEmpty()) return "Shader reload failed."
            return buildString {
                for (diagnostic in diagnostics) {
                    append('[').append(diagnostic.severity).append("] ").append(diagnostic.source)
                    if (diagnostic.line > 0) append(':').append(diagnostic.line)
                    append(": ").append(diagnostic.message).append(System.lineSeparator())
                }
            }
        }

        private fun saturatedAdd(vararg values: Long): Long = try {
            values.fold(0L, Math::addExact)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

        private fun segment(kind: String, value: String): String =
            UUID.nameUUIDFromBytes((kind + '\u0000' + value).toByteArray(StandardCharsets.UTF_8)).toString()

        private fun workspaceSegment(value: String): String {
            val parsed = try {
                UUID.fromString(value)
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("workspace ID must be a canonical UUID", exception)
            }
            require(parsed.toString().equals(value, ignoreCase = true)) { "workspace ID must be a canonical UUID" }
            return parsed.toString()
        }
    }
}
