package dev.vibris.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal data class OwnedPathIdentity(
    val fileKey: Any?,
    val creationTime: FileTime,
) {
    fun fileKey(): Any? = fileKey

    fun creationTime(): FileTime = creationTime

    fun matchesDirectory(path: Path): Boolean {
        return try {
            val current = captureDirectory(path)
            if (fileKey != null || current.fileKey != null) {
                fileKey != null && fileKey == current.fileKey
            } else {
                creationTime == current.creationTime
            }
        } catch (_: IOException) {
            false
        }
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun createDirectoriesSafely(path: Path) {
            val normalized = path.toAbsolutePath().normalize()
            var existing: Path? = normalized
            while (existing != null) {
                try {
                    captureDirectory(existing)
                    break
                } catch (_: NoSuchFileException) {
                    existing = existing.parent
                }
            }
            requireOrdinaryAncestors(existing ?: throw IOException("owned path has no ordinary ancestor"))
            Files.createDirectories(normalized)
            requireOrdinaryAncestors(normalized)
        }

        @JvmStatic
        @Throws(IOException::class)
        fun captureDirectory(path: Path): OwnedPathIdentity {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.isOther ||
                Files.isSymbolicLink(path)
            ) {
                throw IOException("owned path is not an ordinary directory")
            }
            return OwnedPathIdentity(attributes.fileKey(), attributes.creationTime())
        }

        @Throws(IOException::class)
        private fun requireOrdinaryAncestors(path: Path) {
            var current: Path? = path
            while (current != null) {
                captureDirectory(current)
                current = current.parent
            }
        }
    }
}