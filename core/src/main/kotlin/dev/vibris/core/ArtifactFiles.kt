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
import java.util.UUID

internal object ArtifactFiles {
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

}
