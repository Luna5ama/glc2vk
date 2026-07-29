package dev.vibris.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal class PendingSourceRoot(root: Path) {
    private val root = root.toAbsolutePath().normalize()

    @Throws(Failure::class)
    fun prepare() {
        try {
            OwnedPathIdentity.createDirectoriesSafely(root)
            requireOrdinaryAncestors()
        } catch (exception: IOException) {
            throw Failure("Pending source root is missing or unsafe.", exception)
        }
        clear()
    }

    @Throws(Failure::class)
    fun clear() {
        try {
            requireOrdinaryAncestors()
            Files.list(root).use { children ->
                for (child in children.toList()) {
                    if (!deleteEntry(child)) {
                        throw Failure("Pending source root contains an unsafe entry.")
                    }
                }
            }
        } catch (exception: IOException) {
            throw Failure("Failed to clear the pending source root.", exception)
        }
    }

    @Throws(IOException::class)
    private fun deleteEntry(child: Path): Boolean {
        val attributes = Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (attributes.isSymbolicLink || attributes.isOther || Files.isSymbolicLink(child)) {
            Files.delete(child)
            return true
        }
        return OwnedSourceTree.delete(child)
    }

    @Throws(IOException::class)
    private fun requireOrdinaryAncestors() {
        var current: Path? = root
        while (current != null) {
            OwnedPathIdentity.captureDirectory(current)
            current = current.parent
        }
    }

    class Failure @JvmOverloads constructor(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)
}