package dev.luna5ama.vibris.capture

import dev.vibris.api.ArtifactSink
import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ResourceCatalog
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

internal object PatchedShaderCapture {
    data class Identity(
        val sha256: String,
        val fileCount: Int,
        val totalBytes: Long,
    )

    fun identity(host: ShaderDebugHost): Identity {
        check(host.debugShadersEnabled()) { "Iris shader debug output is disabled" }
        host.awaitPatchedShaderWrites()
        val directory = host.gameDirectory().resolve("patched_shaders").toAbsolutePath().normalize()
        val files = snapshot(directory)
        check(files.isNotEmpty()) { "Iris did not produce patched shader output" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("vibris-patched-shaders-v1\u0000".toByteArray(Charsets.UTF_8))
        var totalBytes = 0L
        files.forEach { file ->
            val name = file.fileName.toString().toByteArray(Charsets.UTF_8)
            digest.update(name.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(name)
            digest.update(0.toByte())
            val before = attributes(file)
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val after = attributes(file)
            check(before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
                before.fileKey() == after.fileKey()) {
                "Patched shader changed while its identity was computed: ${file.fileName}"
            }
            totalBytes = Math.addExact(totalBytes, after.size())
        }
        return Identity(digest.digest().joinToString("") { byte -> "%02x".format(byte) }, files.size, totalBytes)
    }

    fun capture(
        host: ShaderDebugHost,
        artifactName: String,
        sink: ArtifactSink,
        frameId: Long,
        cancellation: CancellationToken,
    ): CaptureResult {
        require(frameId >= 0) { "frameId must not be negative" }
        require(CapturePlan.Target(
            CapturePlan.ResourceSelector(
                ResourceCatalog.ResourceKind.PATCHED_SHADERS,
                "patched_shaders",
                null,
                0,
                0,
            ),
            CapturePlan.ArtifactFormat.TEXT,
            artifactName,
            emptyList(),
        ).artifactName == artifactName)
        if (!host.debugShadersEnabled()) {
            throw IllegalStateException("Iris shader debug output is disabled")
        }

        host.awaitPatchedShaderWrites()
        cancellation.throwIfCancellationRequested()
        val directory = host.gameDirectory().resolve("patched_shaders").toAbsolutePath().normalize()
        val files = snapshot(directory)
        val artifacts = ArrayList<CaptureResult.CapturedArtifact>(files.size)
        val names = HashSet<String>()
        var totalBytes = 0L
        files.forEachIndexed { index, file ->
            cancellation.throwIfCancellationRequested()
            val originalName = file.fileName.toString()
            val outputName = "$artifactName.$originalName"
            require(names.add(outputName.lowercase(Locale.ROOT))) {
                "Patched shader artifact names collide: $outputName"
            }
            val format = if (originalName.endsWith(".json", ignoreCase = true)) {
                CapturePlan.ArtifactFormat.JSON
            } else {
                CapturePlan.ArtifactFormat.TEXT
            }
            val spec = CapturePlan.ArtifactOutputSpec(
                outputName,
                format,
                CapturePlan.ArtifactRole.SUBRESOURCE,
                index,
            )
            val before = attributes(file)
            sink.open(spec.fileName).use { output ->
                Files.newInputStream(file).use { input -> input.copyTo(output) }
            }
            val after = attributes(file)
            check(before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
                before.fileKey() == after.fileKey()) {
                "Patched shader changed while it was being captured: $originalName"
            }
            totalBytes = Math.addExact(totalBytes, after.size())
            artifacts.add(CaptureResult.CapturedArtifact(
                spec.fileName,
                spec.format,
                spec.role,
                spec.subresourceIndex,
            ))
        }

        val resource = ResourceCatalog.ResourceDescriptor.of(
            "patched_shaders",
            ResourceCatalog.ResourceKind.PATCHED_SHADERS,
            emptyList(),
            0,
            0,
            0,
            0,
            files.size,
            "text",
            0,
            ResourceCatalog.ScalarType.UNSPECIFIED,
            totalBytes,
            frameId,
            "patched_shaders",
            "patched_shaders",
            "",
            "",
            "",
            0,
            "",
            "",
        )
        return CaptureResult(frameId, listOf(CaptureResult.ArtifactGroup(artifactName, resource, artifacts)))
    }

    private fun snapshot(directory: Path): List<Path> {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "Patched shader output is not an ordinary directory: $directory"
        }
        return Files.list(directory).use { stream ->
            stream.sorted(Comparator.comparing { it.fileName.toString() }).map { path ->
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    "Patched shader output contains a non-file entry: ${path.fileName}"
                }
                path
            }.toList()
        }
    }

    private fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
}
