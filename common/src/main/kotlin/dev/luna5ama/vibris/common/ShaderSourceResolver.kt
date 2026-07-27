package dev.luna5ama.vibris.common

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

data class ResolvedShaderSource(
    val source: String,
    val path: Path,
    val isOverride: Boolean
)

class ShaderSourceResolver(
    private val captureDir: Path,
    private val shaderOverridePath: Path?,
    private val shaderPasses: Set<String> = emptySet()
) {
    fun resolve(metadata: CaptureMetadata, shaderIndex: Int): ResolvedShaderSource {
        val override = shaderOverridePath
        if (override == null) {
            return resolveCaptured(shaderIndex)
        }

        if (override.isRegularFile()) {
            return ResolvedShaderSource(override.readText(), override, true)
        }

        check(override.isDirectory()) { "Shader override path is neither a file nor a directory: $override" }

        val shaderMetadata = metadata.shaderMetadata(shaderIndex)
        val passName = shaderMetadata.passName
            ?: if (shaderPasses.isNotEmpty()) {
                return resolveCaptured(shaderIndex)
            } else {
                error("Capture shader $shaderIndex has no passName metadata; cannot resolve from override directory $override")
            }
        if (shaderPasses.isNotEmpty() && passName !in shaderPasses) {
            return resolveCaptured(shaderIndex)
        }

        val root = override.resolve("shaders").takeIf { it.isDirectory() } ?: override
        val candidate = resolveDirectoryCandidate(root, shaderMetadata, passName)
            ?: error("Could not find override shader for pass '$passName' in $override")

        val source = if (candidate.startsWith(root)) {
            resolveIncludes(candidate, root)
        } else {
            candidate.readText()
        }
        return ResolvedShaderSource(source, candidate, true)
    }

    private fun resolveCaptured(shaderIndex: Int): ResolvedShaderSource {
        val indexed = captureDir.resolve("shader_$shaderIndex.comp.glsl")
        if (indexed.exists()) {
            return ResolvedShaderSource(indexed.readText(), indexed, false)
        }
        val legacy = captureDir.resolve("shader.comp.glsl")
        check(legacy.exists()) { "Capture shader source does not exist: $indexed or $legacy" }
        return ResolvedShaderSource(legacy.readText(), legacy, false)
    }

    private fun resolveDirectoryCandidate(
        root: Path,
        shaderMetadata: ShaderMetadata,
        passName: String
    ): Path? {
        shaderMetadata.sourcePath
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { sourcePath ->
                val normalized = sourcePath.replace('\\', '/').trimStart('/')
                val candidate = root.resolve(normalized)
                if (candidate.exists()) return candidate
                val fileNameCandidate = root.resolve(Path.of(normalized).fileName)
                if (fileNameCandidate.exists()) return fileNameCandidate
            }

        val direct = root.resolve("$passName.csh")
        if (direct.exists()) return direct

        Files.list(root).use { stream ->
            return stream
                .filter { it.isRegularFile() }
                .filter { it.name.matches(Regex("""(?:\d+_)?${Regex.escape(passName)}\.csh""")) }
                .findFirst()
                .orElse(null)
        }
    }

    private fun resolveIncludes(path: Path, root: Path): String {
        val visiting = LinkedHashSet<Path>()
        val included = HashSet<Path>()

        fun visit(file: Path): String {
            val normalized = file.toAbsolutePath().normalize()
            check(visiting.add(normalized)) { "Include cycle detected while resolving $path: $normalized" }

            val parent = file.parent ?: root
            val builder = StringBuilder()
            file.readText().lineSequence().forEach { line ->
                val include = INCLUDE_REGEX.matchEntire(line.trim())?.groupValues?.get(1)
                if (include == null) {
                    builder.appendLine(line)
                    return@forEach
                }

                val includePath = if (include.startsWith("/")) {
                    root.resolve(include.drop(1))
                } else {
                    parent.resolve(include)
                }.normalize()

                if (included.add(includePath.toAbsolutePath().normalize())) {
                    builder.append(visit(includePath))
                }
            }

            visiting.remove(normalized)
            return builder.toString()
        }

        return visit(path)
    }

    companion object {
        private val INCLUDE_REGEX = Regex("""#include\s+"([^"]+)"""")
    }
}
