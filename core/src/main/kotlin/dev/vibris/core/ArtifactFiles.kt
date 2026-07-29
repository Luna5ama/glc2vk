package dev.vibris.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.ArrayList
import java.util.UUID

internal object ArtifactFiles {
    const val MAX_RECOVERED_JOBS = 16_384
    private const val MANIFEST = "manifest.json"

    @JvmStatic
    @Throws(IOException::class)
    fun recover(root: Path, rootIdentity: OwnedPathIdentity): List<RecoveredJob> {
        return recover(root, rootIdentity, MAX_RECOVERED_JOBS)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun verifyWritable(root: Path, rootIdentity: OwnedPathIdentity) {
        val probe = root.resolve(".vibris-write-probe-${UUID.randomUUID()}.tmp")
        var identity: RegularFileIdentity? = null
        var failure: IOException? = null
        try {
            requireDirectoryIdentity(root, rootIdentity)
            FileChannel.open(probe, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                identity = captureRegularFile(probe)
                val marker = ByteBuffer.wrap(byteArrayOf(0))
                while (marker.hasRemaining()) {
                    channel.write(marker)
                }
                channel.force(true)
            }
            verifyIdentity(probe, checkNotNull(identity))
        } catch (exception: IOException) {
            failure = exception
            throw exception
        } finally {
            val captured = identity
            if (captured != null) {
                try {
                    requireDirectoryIdentity(root, rootIdentity)
                    verifyIdentity(probe, captured)
                    Files.delete(probe)
                } catch (cleanupFailure: IOException) {
                    val original = failure
                    if (original == null) {
                        throw cleanupFailure
                    }
                    original.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun recover(root: Path, rootIdentity: OwnedPathIdentity, maxJobs: Int): List<RecoveredJob> {
        require(maxJobs >= 1) { "maxJobs must be positive" }
        requireDirectoryIdentity(root, rootIdentity)
        val recovered = ArrayList<RecoveredJob>()
        var visitedJobs = 0
        Files.newDirectoryStream(root).use { workspaces ->
            for (workspace in workspaces) {
                requireDirectoryIdentity(root, rootIdentity)
                if (workspace.fileName.toString().endsWith(".tmp")) {
                    deleteTree(workspace, rootIdentity)
                    continue
                }
                if (!Files.isDirectory(workspace, NOFOLLOW_LINKS)) {
                    continue
                }
                val workspaceIdentity = OwnedPathIdentity.captureDirectory(workspace)
                Files.newDirectoryStream(workspace).use { jobs ->
                    for (job in jobs) {
                        requireDirectoryIdentity(root, rootIdentity)
                        requireDirectoryIdentity(workspace, workspaceIdentity)
                        if (job.fileName.toString().endsWith(".tmp")) {
                            deleteTree(job, workspaceIdentity)
                            continue
                        }
                        if (!Files.isDirectory(job, NOFOLLOW_LINKS)) {
                            continue
                        }
                        visitedJobs++
                        if (visitedJobs > maxJobs) {
                            throw IOException("Artifact recovery job limit exceeded.")
                        }
                        try {
                            val manifest = job.resolve(MANIFEST)
                            if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS)) {
                                throw IOException("Artifact job manifest is missing or not a regular file.")
                            }
                            recovered.add(
                                RecoveredJob(
                                    job,
                                    directorySize(job),
                                    Files.getLastModifiedTime(manifest, NOFOLLOW_LINKS).toMillis(),
                                    workspaceIdentity,
                                ),
                            )
                        } catch (malformed: IOException) {
                            try {
                                requireDirectoryIdentity(root, rootIdentity)
                                deleteTree(job, workspaceIdentity)
                            } catch (cleanupFailure: IOException) {
                                cleanupFailure.addSuppressed(malformed)
                                throw cleanupFailure
                            }
                        }
                    }
                }
            }
        }
        requireDirectoryIdentity(root, rootIdentity)
        return recovered
    }

    @JvmStatic
    @Throws(IOException::class)
    fun deleteTree(directory: Path, parentIdentity: OwnedPathIdentity) {
        val parent = directory.toAbsolutePath().normalize().parent
            ?: throw IOException("Artifact deletion target has no parent.")
        requireDirectoryIdentity(parent, parentIdentity)
        if (!Files.exists(directory, NOFOLLOW_LINKS)) {
            return
        }
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    requireDirectoryIdentity(parent, parentIdentity)
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(current: Path, failure: IOException?): FileVisitResult {
                    if (failure != null) {
                        throw failure
                    }
                    requireDirectoryIdentity(parent, parentIdentity)
                    Files.delete(current)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    @JvmStatic
    @Throws(IOException::class)
    fun captureRegularFile(file: Path): RegularFileIdentity {
        val attributes = regularFileAttributes(file)
        return RegularFileIdentity(
            attributes.fileKey(),
            attributes.creationTime(),
            attributes.lastModifiedTime(),
        )
    }

    @JvmStatic
    @Throws(IOException::class)
    fun verifiedSize(file: Path, identity: RegularFileIdentity, expectedBytes: Long): Long {
        val attributes = regularFileAttributes(file)
        if (!matchesIdentity(identity, attributes) || identity.lastModifiedTime != attributes.lastModifiedTime()) {
            throw IOException("Artifact file changed before finalize.")
        }
        if (attributes.size() != expectedBytes) {
            throw IOException("Artifact file size changed before finalize.")
        }
        return attributes.size()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun verifyIdentity(file: Path, identity: RegularFileIdentity) {
        if (!matchesIdentity(identity, regularFileAttributes(file))) {
            throw IOException("Artifact file changed while it was open.")
        }
    }

    @Throws(IOException::class)
    private fun directorySize(directory: Path): Long {
        var bytes = 0L
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    current: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (current != directory) {
                        throw IOException("Artifact job contains a nested directory.")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.isOther) {
                        throw IOException("Artifact job contains a non-regular file.")
                    }
                    try {
                        bytes = Math.addExact(bytes, attributes.size())
                    } catch (exception: ArithmeticException) {
                        throw IOException("Artifact job size overflowed.", exception)
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return bytes
    }

    @Throws(IOException::class)
    private fun regularFileAttributes(file: Path): BasicFileAttributes {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.isOther ||
            Files.isSymbolicLink(file)
        ) {
            throw IOException("Artifact path is not an ordinary regular file.")
        }
        return attributes
    }

    private fun matchesIdentity(identity: RegularFileIdentity, attributes: BasicFileAttributes): Boolean {
        val matchingKey = if (identity.fileKey != null || attributes.fileKey() != null) {
            identity.fileKey != null && identity.fileKey == attributes.fileKey()
        } else {
            identity.creationTime == attributes.creationTime()
        }
        return matchingKey && identity.creationTime == attributes.creationTime()
    }

    @Throws(IOException::class)
    private fun requireDirectoryIdentity(directory: Path, identity: OwnedPathIdentity) {
        if (!identity.matchesDirectory(directory)) {
            throw IOException("Artifact storage directory changed identity.")
        }
    }

    data class RegularFileIdentity(
        val fileKey: Any?,
        val creationTime: FileTime,
        val lastModifiedTime: FileTime,
    ) {
        fun fileKey(): Any? = fileKey

        fun creationTime(): FileTime = creationTime

        fun lastModifiedTime(): FileTime = lastModifiedTime
    }

    data class RecoveredJob(
        val directory: Path,
        val bytes: Long,
        val completedAt: Long,
        val workspaceIdentity: OwnedPathIdentity,
    ) {
        fun directory(): Path = directory

        fun bytes(): Long = bytes

        fun completedAt(): Long = completedAt

        fun workspaceIdentity(): OwnedPathIdentity = workspaceIdentity
    }
}