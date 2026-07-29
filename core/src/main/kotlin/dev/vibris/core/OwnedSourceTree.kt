package dev.vibris.core

import dev.vibris.protocol.v1.ErrorCode
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

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
            throw SourceRegistry.Failure(ErrorCode.INVALID_SOURCE_UUID, "Source UUID escapes the pending root.")
        }
        val stats = scan(directory)
        return Inspection(directory, stats.files.toLong(), stats.bytes)
    }

    @Synchronized
    @Throws(SourceRegistry.Failure::class)
    fun reserve(directory: Path, fileCount: Long, totalBytes: Long): Ownership {
        requireSafePendingRoot()
        val stats = scan(directory)
        if (stats.files.toLong() != fileCount || stats.bytes != totalBytes) {
            throw SourceRegistry.Failure(
                ErrorCode.SOURCE_DIRECTORY_MISSING,
                "Source changed before ownership transfer.",
            )
        }
        return try {
            Ownership(
                checkNotNull(pendingRootIdentity),
                OwnedPathIdentity.captureDirectory(directory),
            )
        } catch (_: IOException) {
            throw SourceRegistry.Failure(
                ErrorCode.SOURCE_CONTAINS_REPARSE_POINT,
                "Source changed before ownership transfer.",
            )
        }
    }

    fun stillOwned(directory: Path, ownership: Ownership): Boolean {
        return ownership.rootIdentity.matchesDirectory(pendingRoot) &&
            ownership.directoryIdentity.matchesDirectory(directory)
    }

    @Throws(SourceRegistry.Failure::class)
    private fun scan(directory: Path): FileStats {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw SourceRegistry.Failure(
                ErrorCode.SOURCE_DIRECTORY_MISSING,
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
                        stats.add(attributes.size())
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: IOException) {
            throw SourceRegistry.Failure(
                ErrorCode.SOURCE_CONTAINS_REPARSE_POINT,
                "Prepared source is not an ordinary tree.",
            )
        }
        if (stats.files == 0) {
            throw SourceRegistry.Failure(ErrorCode.SOURCE_DIRECTORY_MISSING, "Prepared source is empty.")
        }
        return stats
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
                    ErrorCode.SOURCE_CONTAINS_REPARSE_POINT,
                    "Pending source root identity changed.",
                )
            }
            while (current != null) {
                OwnedPathIdentity.captureDirectory(current)
                current = current.parent
            }
        } catch (_: IOException) {
            val code = if (Files.exists(pendingRoot, NOFOLLOW_LINKS)) {
                ErrorCode.SOURCE_CONTAINS_REPARSE_POINT
            } else {
                ErrorCode.SOURCE_DIRECTORY_MISSING
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

        @Throws(IOException::class)
        fun add(size: Long) {
            files++
            if (files > maxFiles || size > maxBytes - bytes) {
                throw IOException("source limit exceeded")
            }
            bytes += size
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