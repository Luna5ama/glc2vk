package dev.vibris.core

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object ShaderConfigFile {
    private const val MAX_BYTES = 64 * 1024
    private val KEY = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")

    @JvmStatic
    fun write(target: Path, scratch: Path, values: Map<String, String>) {
        val content = values.toSortedMap().entries.joinToString(
            separator = "\n",
            postfix = if (values.isEmpty()) "" else "\n",
        ) { (key, value) ->
            require(KEY.matches(key)) { "Invalid shader config option: $key" }
            require(value.length <= 4096 && value.all { character -> character in ' '..'~' }) {
                "Invalid shader config value for $key"
            }
            "$key=$value"
        }
        val bytes = content.toByteArray(StandardCharsets.ISO_8859_1)
        require(bytes.size <= MAX_BYTES) { "Shader config exceeds 64 KiB" }

        val actualTarget = target.toAbsolutePath().normalize()
        val actualScratch = scratch.toAbsolutePath().normalize()
        writeAtomically(actualScratch, bytes)
        if (actualTarget == actualScratch) return

        Files.createDirectories(actualTarget.parent)
        Files.deleteIfExists(actualTarget)
        try {
            Files.createSymbolicLink(actualTarget, actualScratch)
        } catch (_: UnsupportedOperationException) {
            writeAtomically(actualTarget, bytes)
        } catch (_: SecurityException) {
            writeAtomically(actualTarget, bytes)
        } catch (_: java.io.IOException) {
            writeAtomically(actualTarget, bytes)
        }
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".vibris-config-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
