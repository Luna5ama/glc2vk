package dev.vibris.core

import dev.vibris.protocol.v2.ErrorCode
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal class OwnedSourceTree(
    pendingRoot: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
) {
    private val pendingRoot = pendingRoot.toAbsolutePath().normalize()
    private var pendingRootIdentity: OwnedPathIdentity? = null

    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxFiles > 0) { "maxFiles must be positive" }
    }

    @Throws(SourceRegistry.Failure::class)
    fun inspect(uuid: String): Inspection {
        requireSafePendingRoot()
        val directory = pendingRoot.resolve(uuid).normalize()
        if (pendingRoot != directory.parent) {
            throw SourceRegistry.Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "Source UUID escapes the pending root.")
        }
        val stats = scan(directory)
        return Inspection(directory, stats.files.toLong(), stats.bytes)
    }

    @Synchronized
    @Throws(SourceRegistry.Failure::class)
    fun reserve(directory: Path, fileCount: Long, totalBytes: Long): Reservation {
        requireSafePendingRoot()
        val stats = scan(directory)
        if (stats.files.toLong() != fileCount || stats.bytes != totalBytes) {
            throw SourceRegistry.Failure(
                ErrorCode.ERROR_CODE_INVALID_SOURCE,
                "Source changed before ownership transfer.",
            )
        }
        return try {
            Reservation(
                Ownership(
                    checkNotNull(pendingRootIdentity),
                    OwnedPathIdentity.captureDirectory(directory),
                ),
                sha256(stats, directory),
            )
        } catch (_: IOException) {
            throw SourceRegistry.Failure(
                ErrorCode.ERROR_CODE_SOURCE_CONTAINS_REPARSE_POINT,
                "Source changed before ownership transfer.",
            )
        }
    }

    fun stillOwned(directory: Path, ownership: Ownership): Boolean {
        return ownership.rootIdentity.matchesDirectory(pendingRoot) &&
            ownership.directoryIdentity.matchesDirectory(directory)
    }

    @Throws(SourceRegistry.Failure::class)
    fun matchesSnapshot(directory: Path, snapshotSha256: String): Boolean {
        val stats = scan(directory)
        return sha256(stats, directory) == snapshotSha256
    }

    @Throws(SourceRegistry.Failure::class)
    private fun scan(directory: Path): FileStats {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw SourceRegistry.Failure(
                ErrorCode.ERROR_CODE_INVALID_SOURCE,
                "Prepared source directory is missing.",
            )
        }
        val stats = FileStats(maxBytes, maxFiles)
        try {
            Files.walkFileTree(
                directory,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        path: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        requireOrdinary(path, attributes)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                        requireOrdinary(path, attributes)
                        if (!attributes.isRegularFile) {
                            throw IOException("non-ordinary source entry")
                        }
                        stats.add(path, attributes.size())
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: IOException) {
            throw SourceRegistry.Failure(
                ErrorCode.ERROR_CODE_SOURCE_CONTAINS_REPARSE_POINT,
                "Prepared source is not an ordinary tree.",
            )
        }
        if (stats.files == 0) {
            throw SourceRegistry.Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, "Prepared source is empty.")
        }
        return stats
    }

    @Throws(SourceRegistry.Failure::class)
    private fun sha256(stats: FileStats, directory: Path): String = try {
        stats.sha256(directory)
    } catch (_: IOException) {
        throw SourceRegistry.Failure(
            ErrorCode.ERROR_CODE_SOURCE_CONTAINS_REPARSE_POINT,
            "Prepared source changed while its snapshot hash was computed.",
        )
    }

    @Synchronized
    @Throws(SourceRegistry.Failure::class)
    private fun requireSafePendingRoot() {
        var current: Path? = pendingRoot
        try {
            val identity = OwnedPathIdentity.captureDirectory(pendingRoot)
            if (pendingRootIdentity == null) {
                pendingRootIdentity = identity
            }
            if (!checkNotNull(pendingRootIdentity).matchesDirectory(pendingRoot)) {
                throw SourceRegistry.Failure(
                    ErrorCode.ERROR_CODE_SOURCE_CONTAINS_REPARSE_POINT,
                    "Pending source root identity changed.",
                )
            }
            while (current != null) {
                OwnedPathIdentity.captureDirectory(current)
                current = current.parent
            }
        } catch (_: IOException) {
            val code = if (Files.exists(pendingRoot, NOFOLLOW_LINKS)) {
                ErrorCode.ERROR_CODE_SOURCE_CONTAINS_REPARSE_POINT
            } else {
                ErrorCode.ERROR_CODE_INVALID_SOURCE
            }
            throw SourceRegistry.Failure(code, "Pending source root is missing or unsafe.")
        }
    }

    data class Inspection(
        val directory: Path,
        val fileCount: Long,
        val totalBytes: Long,
    ) {
        fun directory(): Path = directory

        fun fileCount(): Long = fileCount

        fun totalBytes(): Long = totalBytes
    }

    data class Reservation(
        val ownership: Ownership,
        val snapshotSha256: String,
    )

    data class Ownership(
        val rootIdentity: OwnedPathIdentity,
        val directoryIdentity: OwnedPathIdentity,
    ) {
        fun rootIdentity(): OwnedPathIdentity = rootIdentity

        fun directoryIdentity(): OwnedPathIdentity = directoryIdentity
    }

    private class FileStats(private val maxBytes: Long, private val maxFiles: Int) {
        var bytes = 0L
        var files = 0
        private val paths = ArrayList<Path>()

        @Throws(IOException::class)
        fun add(path: Path, size: Long) {
            files++
            if (files > maxFiles || size > maxBytes - bytes) {
                throw IOException("source limit exceeded")
            }
            bytes += size
            paths.add(path)
        }

        @Throws(IOException::class)
        fun sha256(root: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("vibris-source-tree-v1\u0000".toByteArray(Charsets.UTF_8))
            paths.sortedBy { root.relativize(it).toString().replace('\\', '/') }.forEach { path ->
                val relative = root.relativize(path).toString().replace('\\', '/').toByteArray(Charsets.UTF_8)
                digest.update('F'.code.toByte())
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(relative.size).array())
                digest.update(relative)
                val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                requireOrdinary(path, before)
                digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(before.size()).array())
                Files.newInputStream(path).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                requireOrdinary(path, after)
                if (before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
                    before.fileKey() != after.fileKey()) {
                    throw IOException("source changed while hashing")
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    companion object {
        private const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024
        private const val DEFAULT_MAX_FILES = 100_000

        @JvmStatic
        fun delete(root: Path): Boolean {
            return try {
                Files.walkFileTree(
                    root,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            directory: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            requireOrdinary(directory, attributes)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                            requireOrdinary(file, attributes)
                            if (!attributes.isRegularFile) {
                                throw IOException("non-ordinary owned source entry")
                            }
                            Files.delete(file)
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                            if (failure != null) {
                                throw failure
                            }
                            Files.delete(directory)
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
                true
            } catch (_: IOException) {
                false
            }
        }

        @Throws(IOException::class)
        private fun requireOrdinary(path: Path, attributes: BasicFileAttributes) {
            if (Files.isSymbolicLink(path) || attributes.isSymbolicLink || attributes.isOther) {
                throw IOException("link-like source entry")
            }
        }
    }
}