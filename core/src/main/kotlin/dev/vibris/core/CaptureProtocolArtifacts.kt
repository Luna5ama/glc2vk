package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.ArtifactFormat
import dev.vibris.protocol.v2.ArtifactKind
import dev.vibris.protocol.v2.ArtifactMetadata
import dev.vibris.protocol.v2.ArtifactRole
import dev.vibris.protocol.v2.CompareReceipt
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobResult
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.LinkedHashSet
import java.util.UUID

internal class CaptureProtocolArtifacts {
    @Throws(Exception::class)
    fun commit(
        job: CoreJob,
        plans: List<CapturePlan>,
        captured: List<CaptureResult>,
        transaction: ArtifactManager.JobTransaction,
        diagnostics: List<ReloadResult.Diagnostic>,
        comparison: CompareReceipt?,
        additionalArtifacts: List<GeneratedArtifact>,
    ): JobResult {
        if (plans.size != captured.size) {
            throw RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_CAPTURE_FAILED,
                "Runtime capture count did not match its plan.",
            )
        }
        plans.indices.forEach { index -> validateResult(plans[index], captured[index]) }
        val committed = transaction.commit(expectedArtifacts(plans, comparison != null, additionalArtifacts))
        val result = JobResult.newBuilder().setResultManifestId(job.submission.jobId + "-manifest")
        for (index in plans.indices) {
            val plan = plans[index]
            for (group in captured[index].groups) {
                val target = plan.targets.first { it.artifactName == group.name }
                for (artifact in group.artifacts) {
                    result.addArtifacts(
                        captureArtifact(
                            job,
                            target,
                            group.resource,
                            artifact,
                            requireArtifact(committed, artifact.fileName),
                        ),
                    )
                }
            }
        }
        if (comparison != null) {
            result.addArtifacts(
                fileArtifact(
                    job,
                    AbArtifactComparator.METRICS_FILE,
                    ArtifactKind.ARTIFACT_KIND_BENCHMARK_METRICS,
                    ArtifactFormat.ARTIFACT_FORMAT_JSON,
                    ArtifactRole.ARTIFACT_ROLE_DIAGNOSTIC,
                    "application/json",
                    requireArtifact(committed, AbArtifactComparator.METRICS_FILE),
                ),
            )
            AbArtifactComparator.heatmapFiles(plans.first()).forEach { fileName ->
                result.addArtifacts(
                    fileArtifact(
                        job,
                        fileName,
                        ArtifactKind.ARTIFACT_KIND_HEATMAP,
                        ArtifactFormat.ARTIFACT_FORMAT_PNG,
                        ArtifactRole.ARTIFACT_ROLE_DIAGNOSTIC,
                        "image/png",
                        requireArtifact(committed, fileName),
                    ),
                )
            }
        }
        additionalArtifacts.forEach { artifact ->
            result.addArtifacts(
                fileArtifact(
                    job,
                    artifact.fileName,
                    artifact.kind,
                    artifact.format,
                    ArtifactRole.ARTIFACT_ROLE_PRIMARY,
                    artifact.mediaType,
                    requireArtifact(committed, artifact.fileName),
                ),
            )
        }
        result.addArtifacts(
            fileArtifact(
                job,
                "shader.log",
                ArtifactKind.ARTIFACT_KIND_SHADER_COMPILE_LOG,
                ArtifactFormat.ARTIFACT_FORMAT_TEXT,
                ArtifactRole.ARTIFACT_ROLE_DIAGNOSTIC,
                "text/plain; charset=utf-8",
                requireArtifact(committed, "shader.log"),
            ),
        )
        result.addArtifacts(
            fileArtifact(
                job,
                "manifest.json",
                ArtifactKind.ARTIFACT_KIND_MANIFEST,
                ArtifactFormat.ARTIFACT_FORMAT_JSON,
                ArtifactRole.ARTIFACT_ROLE_METADATA,
                "application/json",
                requireManifest(committed),
            ),
        )
        return result.build()
    }

    private fun validateResult(plan: CapturePlan, result: CaptureResult) {
        if (result.groups.size != plan.targets.size) {
            throw RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_CAPTURE_FAILED,
                "Runtime capture groups did not match its plan.",
            )
        }
        for (target in plan.targets) {
            val group = result.groups.firstOrNull { it.name == target.artifactName }
            val resource = group?.resource
            if (resource == null || resource.kind != target.kind || resource.logicalName != target.logicalName ||
                resource.frameId != result.frameId ||
                group.artifacts.map { artifact ->
                    listOf(artifact.fileName, artifact.format, artifact.role, artifact.subresourceIndex)
                }.toSet() != target.outputs.map { output ->
                    listOf(output.fileName, output.format, output.role, output.subresourceIndex)
                }.toSet()
            ) {
                throw RuntimeJobExecutor.Failure(
                    ErrorCode.ERROR_CODE_CAPTURE_FAILED,
                    "Runtime capture result did not match its plan.",
                )
            }
        }
    }

    private fun captureArtifact(
        job: CoreJob,
        target: CapturePlan.Target,
        resource: ResourceCatalog.ResourceDescriptor,
        artifact: CaptureResult.CapturedArtifact,
        file: CommittedFile,
    ): ArtifactMetadata = fileArtifact(
        job,
        artifact.fileName,
        kind(target.kind),
        protocolFormat(artifact.format),
        protocolRole(artifact.role),
        mediaType(artifact.format),
        file,
    ).toBuilder()
        .setResource(toProtocol(resource, target))
        .build()

    private fun fileArtifact(
        job: CoreJob,
        fileName: String,
        kind: ArtifactKind,
        format: ArtifactFormat,
        role: ArtifactRole,
        mediaType: String,
        file: CommittedFile,
    ): ArtifactMetadata = ArtifactMetadata.newBuilder()
        .setArtifactId(
            UUID.nameUUIDFromBytes(
                (job.workspaceId + '\u0000' + job.requestId + '\u0000' + fileName)
                    .toByteArray(StandardCharsets.UTF_8),
            ).toString(),
        )
        .setJobId(job.submission.jobId)
        .setRequestId(job.requestId)
        .setRelativePath(file.path.toString())
        .setKind(kind)
        .setFormat(format)
        .setRole(role)
        .setMediaType(mediaType)
        .setByteSize(file.byteSize)
        .setCreatedAtUnixMs(System.currentTimeMillis())
        .build()

    private fun toProtocol(
        resource: ResourceCatalog.ResourceDescriptor,
        target: CapturePlan.Target,
    ): dev.vibris.protocol.v2.ResourceDescriptor = dev.vibris.protocol.v2.ResourceDescriptor.newBuilder()
        .setLogicalName(resource.logicalName)
        .setKind(
            when (resource.kind) {
                ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER ->
                    dev.vibris.protocol.v2.ResourceKind.RESOURCE_KIND_FINAL_FRAMEBUFFER
                ResourceCatalog.ResourceKind.TEXTURE -> dev.vibris.protocol.v2.ResourceKind.RESOURCE_KIND_TEXTURE
                ResourceCatalog.ResourceKind.BUFFER -> dev.vibris.protocol.v2.ResourceKind.RESOURCE_KIND_BUFFER
                ResourceCatalog.ResourceKind.PATCHED_SHADERS ->
                    dev.vibris.protocol.v2.ResourceKind.RESOURCE_KIND_PATCHED_SHADERS
            },
        )
        .setWidth(resource.width)
        .setHeight(resource.height)
        .setDepth(resource.depth)
        .setMipLevels(resource.mipLevels)
        .setLayers(resource.layers)
        .setInternalFormat(resource.internalFormat)
        .setChannelCount(resource.channelCount)
        .setScalarType(dev.vibris.protocol.v2.ScalarType.valueOf("SCALAR_TYPE_" + resource.scalarType.name))
        .setByteSize(resource.byteSize)
        .setFrameId(resource.frameId)
        .setPhysicalName(resource.semanticLabel.ifBlank { target.logicalName })
        .build()

    private fun expectedArtifacts(
        plans: List<CapturePlan>,
        comparison: Boolean,
        additionalArtifacts: List<GeneratedArtifact>,
    ): Set<String> = LinkedHashSet<String>().apply {
        add("shader.log")
        plans.flatMap { it.targets }.flatMap { it.outputs }.forEach { add(it.fileName) }
        if (comparison) {
            add(AbArtifactComparator.METRICS_FILE)
            addAll(AbArtifactComparator.heatmapFiles(plans.first()))
        }
        additionalArtifacts.forEach { add(it.fileName) }
    }

    private fun requireArtifact(committed: ArtifactManager.CommittedJob, name: String): CommittedFile {
        val path = committed.artifacts()[name]
        val byteSize = committed.fileByteSizes()[name]
        if (path == null || byteSize == null) throw IOException("Runtime did not write $name.")
        return CommittedFile(path, byteSize)
    }

    private fun requireManifest(committed: ArtifactManager.CommittedJob): CommittedFile {
        val byteSize = committed.fileByteSizes()["manifest.json"]
            ?: throw IOException("Artifact manifest size is unavailable.")
        return CommittedFile(committed.manifest(), byteSize)
    }

    private fun protocolFormat(format: CapturePlan.ArtifactFormat): ArtifactFormat =
        ArtifactFormat.valueOf("ARTIFACT_FORMAT_" + format.name)

    private fun kind(kind: ResourceCatalog.ResourceKind): ArtifactKind = when (kind) {
        ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER -> ArtifactKind.ARTIFACT_KIND_SCREENSHOT
        ResourceCatalog.ResourceKind.TEXTURE -> ArtifactKind.ARTIFACT_KIND_TEXTURE
        ResourceCatalog.ResourceKind.BUFFER -> ArtifactKind.ARTIFACT_KIND_BUFFER
        ResourceCatalog.ResourceKind.PATCHED_SHADERS -> ArtifactKind.ARTIFACT_KIND_PATCHED_SHADER
    }

    private fun mediaType(format: CapturePlan.ArtifactFormat): String = when (format) {
        CapturePlan.ArtifactFormat.PNG -> "image/png"
        CapturePlan.ArtifactFormat.EXR -> "image/x-exr"
        CapturePlan.ArtifactFormat.BIN -> "application/octet-stream"
        CapturePlan.ArtifactFormat.TEXT -> "text/plain; charset=utf-8"
        CapturePlan.ArtifactFormat.JSON -> "application/json"
    }

    private fun protocolRole(role: CapturePlan.ArtifactRole): ArtifactRole = when (role) {
        CapturePlan.ArtifactRole.PRIMARY -> ArtifactRole.ARTIFACT_ROLE_PRIMARY
        CapturePlan.ArtifactRole.SUBRESOURCE -> ArtifactRole.ARTIFACT_ROLE_SUBRESOURCE
        CapturePlan.ArtifactRole.METADATA -> ArtifactRole.ARTIFACT_ROLE_METADATA
    }

    private data class CommittedFile(val path: Path, val byteSize: Long)
}
