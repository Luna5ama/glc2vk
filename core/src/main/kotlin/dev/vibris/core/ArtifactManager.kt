package dev.vibris.core

import dev.vibris.api.ReloadResult
import dev.vibris.protocol.v1.ArtifactFormat
import dev.vibris.protocol.v1.ArtifactKind
import dev.vibris.protocol.v1.ArtifactMetadata
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.HashMap
import java.util.UUID

internal class ArtifactManager internal constructor(
    root: Path,
    private val quotaBytesValue: Long,
    private val clock: Clock,
) : ShaderLogSink {
    private val rootValue: Path
    private lateinit var rootIdentity: OwnedPathIdentity
    private val completed = HashMap<Path, CompletedJob>()
    private val reservations = HashMap<ArtifactJobTransaction, Long>()
    private val unreported = HashMap<Path, Long>()
    private var usedBytesValue = 0L
    private var reservedBytes = 0L
    private var completionSequence = 0L

    init {
        require(quotaBytesValue >= 0) { "quotaBytes must not be negative" }
        rootValue = root.toAbsolutePath().normalize()
        try {
            OwnedPathIdentity.createDirectoriesSafely(rootValue)
            rootIdentity = OwnedPathIdentity.captureDirectory(rootValue)
            ArtifactFiles.verifyWritable(rootValue, rootIdentity)
            for (job in ArtifactFiles.recover(rootValue, rootIdentity)) {
                completed[job.directory()] = CompletedJob(
                    job.directory(),
                    job.bytes(),
                    job.completedAt(),
                    completionSequence++,
                    job.workspaceIdentity(),
                )
                usedBytesValue = Math.addExact(usedBytesValue, job.bytes())
            }
            evictUntilWithinQuota(0)
        } catch (exception: IOException) {
            throw IllegalStateException("Could not initialize the artifact root.", exception)
        } catch (exception: ArithmeticException) {
            throw IllegalStateException("Could not initialize the artifact root.", exception)
        }
    }

    constructor(root: Path) : this(root, DEFAULT_QUOTA_BYTES, Clock.systemUTC())

    constructor(root: Path, quotaBytes: Long) : this(root, quotaBytes, Clock.systemUTC())

    fun root(): Path = rootValue

    fun quotaBytes(): Long = quotaBytesValue

    @Synchronized
    fun usedBytes(): Long = usedBytesValue

    @Synchronized
    @Throws(IOException::class)
    fun beginJob(workspaceId: String, requestId: String, estimatedBytes: Long): JobTransaction {
        require(estimatedBytes >= 0) { "estimatedBytes must not be negative" }
        if (estimatedBytes > quotaBytesValue) {
            throw JobTooLargeException(estimatedBytes, quotaBytesValue)
        }

        verifyRootIdentity()
        val workspace = rootValue.resolve(segment("workspace", workspaceId))
        val directory = workspace.resolve(segment("request", requestId))
        val temporary = directory.resolveSibling(directory.fileName.toString() + ".tmp")
        OwnedPathIdentity.createDirectoriesSafely(workspace)
        verifyRootIdentity()
        val workspaceIdentity = OwnedPathIdentity.captureDirectory(workspace)
        val previous = completed[directory]
        expireUnreported()
        if (previous != null && !unreported.containsKey(directory)) {
            ArtifactFiles.deleteTree(directory, previous.workspaceIdentity)
            completed.remove(directory)
            usedBytesValue = Math.subtractExact(usedBytesValue, previous.bytes)
        }
        if (Files.exists(directory, NOFOLLOW_LINKS) || Files.exists(temporary, NOFOLLOW_LINKS)) {
            throw IOException("Artifact job already exists.")
        }
        evictUntilWithinQuota(estimatedBytes)
        verifyStorageIdentity(workspace, workspaceIdentity)
        Files.createDirectory(temporary)
        val transaction = try {
            verifyStorageIdentity(workspace, workspaceIdentity)
            JobTransaction(temporary, directory, workspaceIdentity)
        } catch (exception: IOException) {
            ArtifactFiles.deleteTree(temporary, workspaceIdentity)
            throw exception
        } catch (exception: RuntimeException) {
            ArtifactFiles.deleteTree(temporary, workspaceIdentity)
            throw exception
        }
        reservations[transaction] = estimatedBytes
        reservedBytes = Math.addExact(reservedBytes, estimatedBytes)
        return transaction
    }

    @Synchronized
    fun markReported(workspaceId: String, requestId: String) {
        val workspace = rootValue.resolve(segment("workspace", workspaceId))
        val directory = workspace.resolve(segment("request", requestId))
        unreported.remove(directory)
    }

    @Throws(IOException::class)
    override fun writeShaderLog(
        workspaceId: String,
        requestId: String,
        diagnostics: List<ReloadResult.Diagnostic>,
    ): ArtifactMetadata {
        val bytes = logText(diagnostics).toByteArray(StandardCharsets.UTF_8)
        val committed = beginJob(workspaceId, requestId, bytes.size.toLong()).use { transaction ->
            transaction.open("shader.log").use { output -> output.write(bytes) }
            transaction.commit()
        }
        val log = committed.artifacts()["shader.log"]!!
        return ArtifactMetadata.newBuilder()
            .setArtifactId(segment("artifact", workspaceId + '\u0000' + requestId + "\u0000shader.log"))
            .setFileName("shader.log")
            .setKind(ArtifactKind.ARTIFACT_KIND_SHADER_COMPILE_LOG)
            .setFormat(ArtifactFormat.ARTIFACT_FORMAT_TEXT)
            .setMediaType("text/plain; charset=utf-8")
            .setByteSize(bytes.size.toLong())
            .setPath(log.toString())
            .build()
    }

    @Synchronized
    @Throws(IOException::class)
    fun reserve(transaction: ArtifactJobTransaction, bytes: Long) {
        val current = reservations[transaction]
            ?: throw IOException("Artifact job is no longer active.")
        if (bytes > quotaBytesValue) {
            throw JobTooLargeException(bytes, quotaBytesValue)
        }
        if (bytes <= current) {
            return
        }
        val additional = Math.subtractExact(bytes, current)
        evictUntilWithinQuota(additional)
        reservations[transaction] = bytes
        reservedBytes = Math.addExact(reservedBytes, additional)
    }

    @Synchronized
    fun complete(transaction: ArtifactJobTransaction, bytes: Long, completedAt: Long) {
        val reservation = reservations.remove(transaction)
        if (reservation != null) {
            reservedBytes = Math.subtractExact(reservedBytes, reservation)
        }
        completed[transaction.directory()] = CompletedJob(
            transaction.directory(),
            bytes,
            completedAt,
            completionSequence++,
            transaction.workspaceIdentity(),
        )
        unreported[transaction.directory()] = protectionDeadline()
        usedBytesValue = Math.addExact(usedBytesValue, bytes)
    }

    @Synchronized
    fun abort(transaction: ArtifactJobTransaction) {
        val reservation = reservations.remove(transaction)
        if (reservation != null) {
            reservedBytes = Math.subtractExact(reservedBytes, reservation)
        }
    }

    @Throws(IOException::class)
    fun verifyStorageIdentity(workspace: Path, workspaceIdentity: OwnedPathIdentity) {
        verifyRootIdentity()
        if (!workspaceIdentity.matchesDirectory(workspace)) {
            throw IOException("Artifact workspace directory changed identity.")
        }
    }

    @Throws(IOException::class)
    private fun evictUntilWithinQuota(additionalBytes: Long) {
        expireUnreported()
        while (exceedsQuota(additionalBytes)) {
            val oldest = completed.values
                .asSequence()
                .filter { !unreported.containsKey(it.directory) }
                .minWithOrNull(
                    compareBy<CompletedJob>({ it.completedAt }, { it.sequence }, { it.directory.toString() }),
                )
                ?: throw QuotaExceededException(quotaBytesValue)
            verifyRootIdentity()
            ArtifactFiles.deleteTree(oldest.directory, oldest.workspaceIdentity)
            completed.remove(oldest.directory)
            usedBytesValue = Math.subtractExact(usedBytesValue, oldest.bytes)
        }
    }

    @Throws(IOException::class)
    private fun verifyRootIdentity() {
        if (!rootIdentity.matchesDirectory(rootValue)) {
            throw IOException("Artifact root directory changed identity.")
        }
    }

    private fun expireUnreported() {
        val now = clock.millis()
        unreported.entries.removeIf { it.value <= now }
    }

    private fun protectionDeadline(): Long =
        try {
            Math.addExact(clock.millis(), UNREPORTED_TTL.toMillis())
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

    private fun exceedsQuota(additionalBytes: Long): Boolean =
        try {
            Math.addExact(Math.addExact(usedBytesValue, reservedBytes), additionalBytes) > quotaBytesValue
        } catch (_: ArithmeticException) {
            true
        }

    class CommittedJob(
        directory: Path,
        manifest: Path,
        artifacts: Map<String, Path>,
        fileByteSizes: Map<String, Long>,
        private val byteSizeValue: Long,
    ) {
        private val directoryValue = directory.toAbsolutePath().normalize()
        private val manifestValue = manifest.toAbsolutePath().normalize()
        private val artifactsValue = java.util.Map.copyOf(artifacts)
        private val fileByteSizesValue = java.util.Map.copyOf(fileByteSizes)

        fun directory(): Path = directoryValue

        fun manifest(): Path = manifestValue

        fun artifacts(): Map<String, Path> = artifactsValue

        fun fileByteSizes(): Map<String, Long> = fileByteSizesValue

        fun byteSize(): Long = byteSizeValue

        override fun equals(other: Any?): Boolean =
            this === other ||
                other is CommittedJob &&
                directoryValue == other.directoryValue &&
                manifestValue == other.manifestValue &&
                artifactsValue == other.artifactsValue &&
                fileByteSizesValue == other.fileByteSizesValue &&
                byteSizeValue == other.byteSizeValue

        override fun hashCode(): Int {
            var result = directoryValue.hashCode()
            result = 31 * result + manifestValue.hashCode()
            result = 31 * result + artifactsValue.hashCode()
            result = 31 * result + fileByteSizesValue.hashCode()
            return 31 * result + byteSizeValue.hashCode()
        }

        override fun toString(): String =
            "CommittedJob[directory=$directoryValue, manifest=$manifestValue, artifacts=$artifactsValue, " +
                "fileByteSizes=$fileByteSizesValue, byteSize=$byteSizeValue]"
    }

    class JobTooLargeException(bytes: Long, quotaBytes: Long) :
        IOException("Artifact job requires $bytes bytes, exceeding quota $quotaBytes.")

    class QuotaExceededException(quotaBytes: Long) :
        IOException("Artifact quota $quotaBytes bytes is occupied by protected jobs.")

    inner class JobTransaction internal constructor(
        temporary: Path,
        directory: Path,
        workspaceIdentity: OwnedPathIdentity,
    ) : ArtifactJobTransaction(this@ArtifactManager, temporary, directory, workspaceIdentity)

    private data class CompletedJob(
        val directory: Path,
        val bytes: Long,
        val completedAt: Long,
        val sequence: Long,
        val workspaceIdentity: OwnedPathIdentity,
    )

    companion object {
        const val DEFAULT_QUOTA_BYTES = 3L * 1024 * 1024 * 1024

        @JvmField
        val UNREPORTED_TTL: Duration = Duration.ofMinutes(10)

        private fun logText(diagnostics: List<ReloadResult.Diagnostic>): String {
            if (diagnostics.isEmpty()) {
                return "Shader reload failed."
            }
            val output = StringBuilder()
            for (diagnostic in diagnostics) {
                output.append('[').append(diagnostic.severity).append("] ").append(diagnostic.source)
                if (diagnostic.line > 0) {
                    output.append(':').append(diagnostic.line)
                }
                output.append(": ").append(diagnostic.message).append(System.lineSeparator())
            }
            return output.toString()
        }

        private fun segment(kind: String, value: String): String =
            UUID.nameUUIDFromBytes((kind + '\u0000' + value).toByteArray(StandardCharsets.UTF_8)).toString()
    }
}