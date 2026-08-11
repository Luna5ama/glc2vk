package dev.vibris.core

import dev.vibris.api.ArtifactSink
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.LinkedHashMap
import java.util.Locale

internal open class ArtifactJobTransaction internal constructor(
    private val manager: ArtifactManager,
    private val temporary: Path,
    private val directoryValue: Path,
    private val workspaceIdentityValue: OwnedPathIdentity,
    private val workspaceId: String,
    private val jobId: String,
    private val requestId: String,
    private val recipe: String,
    private val createdAtUnixMs: Long,
    private val expiresAtUnixMs: Long,
) : ArtifactSink, AutoCloseable {
    private val temporaryIdentity = OwnedPathIdentity.captureDirectory(temporary)
    private val artifacts = LinkedHashMap<String, ArtifactRecord>()
    private val artifactNames = HashSet<String>()
    private val openStreams = HashSet<ArtifactOutputStream>()
    private var state = State.ACTIVE
    private var artifactBytes = 0L
    private var writeFailed = false

    internal fun directory(): Path = directoryValue

    internal fun workspaceIdentity(): OwnedPathIdentity = workspaceIdentityValue

    @Synchronized
    @Throws(IOException::class)
    override fun open(artifactName: String): OutputStream {
        requireActive()
        val path = artifactPath(artifactName)
        val canonicalName = canonical(artifactName)
        if (!artifactNames.add(canonicalName)) {
            throw IOException("Artifact name is already open or written.")
        }
        try {
            val output = ArtifactOutputStream(this, artifactName, path)
            openStreams.add(output)
            return output
        } catch (exception: IOException) {
            artifactNames.remove(canonicalName)
            throw exception
        } catch (exception: RuntimeException) {
            artifactNames.remove(canonicalName)
            throw exception
        }
    }

    @Synchronized
    fun checkpoint(): Checkpoint {
        requireActive()
        return Checkpoint(java.util.Set.copyOf(artifactNames), artifactBytes, writeFailed)
    }

    @Synchronized
    @Throws(IOException::class)
    fun rollback(checkpoint: Checkpoint) {
        requireActive()
        var failure: IOException? = null
        for (stream in java.util.List.copyOf(openStreams)) {
            if (canonical(stream.name()) in checkpoint.artifactNames) continue
            try {
                stream.close()
            } catch (exception: IOException) {
                failure = appendFailure(failure, exception)
            }
        }
        val created = artifacts.entries.filter { canonical(it.key) !in checkpoint.artifactNames }
        for ((name, artifact) in created) {
            try {
                ArtifactFiles.verifiedSize(artifact.path, artifact.identity, artifact.bytes)
                Files.delete(artifact.path)
                artifacts.remove(name)
                artifactNames.remove(canonical(name))
            } catch (exception: IOException) {
                failure = appendFailure(failure, exception)
            }
        }
        if (failure != null) throw failure
        artifactBytes = checkpoint.artifactBytes
        writeFailed = checkpoint.writeFailed
    }

    @Synchronized
    @Throws(IOException::class)
    fun commit(specifications: Map<String, ArtifactManifest.FileSpec>): ArtifactManager.CommittedJob {
        requireActive()
        if (openStreams.isNotEmpty()) {
            throw IOException("Artifact streams must be closed before commit.")
        }
        if (writeFailed) {
            throw IOException("Artifact output did not flush successfully.")
        }
        requireSpecifications(specifications)
        verifyTemporaryAndArtifacts()
        state = State.FINALIZING
        val artifactSizes = artifactSizes()
        val completedAt = manager.now()
        val entries = artifacts.map { (name, artifact) ->
            ArtifactManifest.FileEntry(
                manager.artifactId(workspaceId, jobId, requestId, name),
                name,
                specifications.getValue(name),
                artifact.bytes,
                ArtifactManifest.sha256(artifact.path),
                createdAtUnixMs,
                expiresAtUnixMs,
            )
        }
        val encoded = ArtifactManifest.encode(
            ArtifactManifest.Document(
                manager.manifestId(workspaceId, jobId, requestId),
                workspaceId,
                jobId,
                requestId,
                recipe,
                createdAtUnixMs,
                completedAt,
                expiresAtUnixMs,
                0,
                entries,
            ),
        )
        val manifestBytes = encoded.bytes
        try {
            manager.reserve(this, encoded.document.totalBytes)
            val manifest = temporary.resolve(MANIFEST)
            lateinit var manifestIdentity: ArtifactFiles.RegularFileIdentity
            FileChannel.open(manifest, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                Channels.newOutputStream(channel).use { output ->
                    manifestIdentity = ArtifactFiles.captureRegularFile(manifest)
                    output.write(manifestBytes)
                    output.flush()
                    channel.force(true)
                }
            }
            ArtifactFiles.verifyIdentity(manifest, manifestIdentity)
            manifestIdentity = ArtifactFiles.captureRegularFile(manifest)
            ArtifactFiles.verifiedSize(manifest, manifestIdentity, manifestBytes.size.toLong())
            verifyTemporaryAndArtifacts()
            ArtifactFiles.verifiedSize(manifest, manifestIdentity, manifestBytes.size.toLong())
            manager.verifyStorageIdentity(temporary.parent, workspaceIdentityValue)
            try {
                Files.move(temporary, directoryValue, StandardCopyOption.ATOMIC_MOVE)
            } catch (exception: AtomicMoveNotSupportedException) {
                throw IOException("Artifact filesystem does not support atomic job finalize.", exception)
            }
            state = State.COMMITTED
            manager.complete(this, encoded)
            val finalArtifacts = LinkedHashMap<String, Path>()
            artifacts.keys.forEach { name -> finalArtifacts[name] = directoryValue.resolve(name) }
            val fileByteSizes = LinkedHashMap(artifactSizes)
            fileByteSizes[MANIFEST] = manifestBytes.size.toLong()
            val metadata = LinkedHashMap<String, dev.vibris.protocol.v2.ArtifactMetadata>()
            entries.forEach { entry ->
                metadata[entry.relativePath] = entry.metadata(jobId, requestId, directoryValue.resolve(entry.relativePath))
            }
            return ArtifactManager.CommittedJob(
                directoryValue,
                directoryValue.resolve(MANIFEST),
                finalArtifacts,
                fileByteSizes,
                metadata,
                encoded.document.manifestId,
                encoded.sha256,
                expiresAtUnixMs,
                encoded.document.totalBytes,
            )
        } catch (exception: IOException) {
            abortAfterFailure(exception)
        } catch (exception: RuntimeException) {
            abortAfterFailure(exception)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    internal fun readableArtifact(artifactName: String?): Path {
        requireActive()
        val artifact = artifacts[artifactName]
            ?: throw IOException("Artifact is not available for comparison.")
        ArtifactFiles.verifiedSize(artifact.path, artifact.identity, artifact.bytes)
        return artifact.path
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        if (state == State.COMMITTED || state == State.ABORTED) {
            return
        }
        var failure: IOException? = null
        for (stream in java.util.List.copyOf(openStreams)) {
            try {
                stream.close()
            } catch (exception: IOException) {
                if (failure == null) {
                    failure = exception
                } else {
                    failure?.addSuppressed(exception)
                }
            }
        }
        try {
            abortInternal()
        } catch (exception: IOException) {
            if (failure == null) {
                failure = exception
            } else {
                failure?.addSuppressed(exception)
            }
        }
        failure?.let { throw it }
    }

    @Synchronized
    @Throws(IOException::class)
    internal fun write(stream: ArtifactOutputStream, bytes: ByteArray, offset: Int, length: Int) {
        requireActive()
        manager.reserve(this, Math.addExact(artifactBytes, length.toLong()))
        try {
            stream.writeDirect(bytes, offset, length)
        } catch (exception: IOException) {
            writeFailed = true
            throw exception
        }
        artifactBytes = Math.addExact(artifactBytes, length.toLong())
        stream.addBytes(length)
    }

    @Synchronized
    internal fun closed(stream: ArtifactOutputStream, failed: Boolean) {
        openStreams.remove(stream)
        artifacts[stream.name()] = ArtifactRecord(stream.path(), stream.bytes(), stream.identity())
        if (failed) {
            writeFailed = true
        }
    }

    @Throws(IOException::class)
    private fun artifactPath(artifactName: String?): Path {
        if (
            artifactName == null ||
            artifactName.isBlank() ||
            artifactName == "." ||
            artifactName == ".." ||
            canonical(artifactName) == MANIFEST
        ) {
            throw IOException("Artifact name must be one safe file name.")
        }
        try {
            if (Path.of(artifactName).fileName.toString() != artifactName) {
                throw IOException("Artifact name must be one safe file name.")
            }
        } catch (exception: InvalidPathException) {
            throw IOException("Artifact name is invalid.", exception)
        }
        val path = temporary.resolve(artifactName).normalize()
        if (path.parent != temporary) {
            throw IOException("Artifact path escapes its job directory.")
        }
        return path
    }

    @Throws(IOException::class)
    private fun requireActive() {
        if (state != State.ACTIVE) {
            throw IOException("Artifact job is not active.")
        }
    }

    @Throws(IOException::class)
    private fun abortInternal() {
        state = State.ABORTED
        manager.abort(this)
        manager.verifyStorageIdentity(temporary.parent, workspaceIdentityValue)
        if (!temporaryIdentity.matchesDirectory(temporary)) {
            throw IOException("Artifact job directory changed before cleanup.")
        }
        ArtifactFiles.deleteTree(temporary, workspaceIdentityValue)
    }

    @Throws(IOException::class)
    private fun verifyTemporaryAndArtifacts() {
        manager.verifyStorageIdentity(temporary.parent, workspaceIdentityValue)
        if (!temporaryIdentity.matchesDirectory(temporary)) {
            throw IOException("Artifact job directory changed before finalize.")
        }
        for (artifact in artifacts.values) {
            ArtifactFiles.verifiedSize(artifact.path, artifact.identity, artifact.bytes)
        }
    }

    @Throws(IOException::class)
    private fun requireSpecifications(specifications: Map<String, ArtifactManifest.FileSpec>) {
        val expected = HashSet<String>()
        for ((name, specification) in specifications) {
            artifactPath(name)
            if (!expected.add(canonical(name))) throw IOException("Artifact specifications are repeated.")
            if (specification.kind.name.endsWith("UNSPECIFIED") ||
                specification.format.name.endsWith("UNSPECIFIED") ||
                specification.role.name.endsWith("UNSPECIFIED") ||
                specification.mediaType.isBlank()
            ) throw IOException("Artifact specification is incomplete.")
        }
        if (expected != artifactNames) throw IOException("Runtime did not write the specified artifacts.")
    }

    private fun artifactSizes(): Map<String, Long> {
        val sizes = LinkedHashMap<String, Long>()
        artifacts.forEach { (name, artifact) -> sizes[name] = artifact.bytes }
        return sizes
    }

    private fun abortAfterFailure(exception: Exception): Nothing {
        try {
            abortInternal()
        } catch (cleanupFailure: IOException) {
            exception.addSuppressed(cleanupFailure)
        }
        throw exception
    }

    private data class ArtifactRecord(
        val path: Path,
        val bytes: Long,
        val identity: ArtifactFiles.RegularFileIdentity,
    )

    data class Checkpoint internal constructor(
        internal val artifactNames: Set<String>,
        internal val artifactBytes: Long,
        internal val writeFailed: Boolean,
    )

    private enum class State {
        ACTIVE,
        FINALIZING,
        COMMITTED,
        ABORTED,
    }

    private companion object {
        const val MANIFEST = "manifest.json"

        fun canonical(name: String): String = name.lowercase(Locale.ROOT)

        private fun appendFailure(original: IOException?, additional: IOException): IOException {
            if (original == null) return additional
            original.addSuppressed(additional)
            return original
        }
    }
}
