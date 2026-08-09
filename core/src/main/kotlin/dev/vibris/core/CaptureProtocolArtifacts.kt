package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v1.AbComparisonResult
import dev.vibris.protocol.v1.ArtifactKind
import dev.vibris.protocol.v1.ArtifactMetadata
import dev.vibris.protocol.v1.ArtifactRole
import dev.vibris.protocol.v1.DiagnosticSeverity
import dev.vibris.protocol.v1.ErrorCode
import dev.vibris.protocol.v1.JobResult
import dev.vibris.protocol.v1.JobResultKind
import dev.vibris.protocol.v1.ShaderDiagnostic
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
        comparison: AbComparisonResult?,
        additionalArtifacts: List<GeneratedArtifact>,
    ): JobResult {
        if (plans.size != captured.size) {
            throw RuntimeJobExecutor.Failure(
                ErrorCode.CAPTURE_FAILED,
                "Runtime capture count did not match its plan.",
            )
        }
        for (index in plans.indices) {
            validateResult(plans[index], captured[index])
        }
        val committed = transaction.commit(expectedArtifacts(plans, comparison != null, additionalArtifacts))
        val result = JobResult.newBuilder()
            .setKind(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE)
            .setManifestPath(committed.manifest().toString())
        for (index in plans.indices) {
            val plan = plans[index]
            val capture = captured[index]
            result.addFrameIds(capture.frameId)
            for (group in capture.groups) {
                val target = plan.targets.first { it.artifactName == group.name }
                val protocolGroup = dev.vibris.protocol.v1.ArtifactGroup.newBuilder()
                    .setName(group.name)
                    .setResource(toProtocol(group.resource, target, null))
                for (artifact in group.artifacts) {
                    protocolGroup.addArtifacts(
                        captureArtifact(
                            job,
                            target,
                            group.resource,
                            artifact,
                            requireArtifact(committed, artifact.fileName),
                        ),
                    )
                }
                result.addArtifactGroups(protocolGroup)
            }
        }
        if (comparison != null) {
            addComparison(job, committed, result, comparison, plans.first(), captured.first())
        }
        for (artifact in additionalArtifacts) {
            result.addArtifacts(
                fileArtifact(
                    job,
                    artifact.fileName,
                    artifact.kind,
                    artifact.format,
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
                dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_TEXT,
                "text/plain; charset=utf-8",
                requireArtifact(committed, "shader.log"),
            ),
        )
        result.addArtifacts(
            fileArtifact(
                job,
                "manifest.json",
                ArtifactKind.ARTIFACT_KIND_MANIFEST,
                dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_JSON,
                "application/json",
                requireManifest(committed),
            ),
        )
        addDiagnostics(result, diagnostics, requireArtifact(committed, "shader.log").path.toString())
        return result.build()
    }

    private data class CommittedFile(val path: Path, val byteSize: Long)

    companion object {
        @JvmStatic
        fun addDiagnostics(
            result: JobResult.Builder,
            diagnostics: List<ReloadResult.Diagnostic>,
            logPath: String,
        ) {
            for (diagnostic in diagnostics) {
                result.addShaderDiagnostics(
                    ShaderDiagnostic.newBuilder()
                        .setSeverity(
                            DiagnosticSeverity.valueOf(
                                "DIAGNOSTIC_SEVERITY_" + diagnostic.severity.name,
                            ),
                        )
                        .setFileName(diagnostic.source)
                        .setLine(diagnostic.line)
                        .setMessage(diagnostic.message)
                        .setLogPath(logPath),
                )
            }
        }

        private fun expectedArtifacts(
            plans: List<CapturePlan>,
            comparison: Boolean,
            additionalArtifacts: List<GeneratedArtifact>,
        ): Set<String> {
            val expected = LinkedHashSet<String>()
            expected.add("shader.log")
            for (plan in plans) {
                for (target in plan.targets) {
                    target.outputs.forEach { expected.add(it.fileName) }
                }
            }
            if (comparison) {
                expected.add(AbArtifactComparator.METRICS_FILE)
                expected.addAll(AbArtifactComparator.heatmapFiles(plans.first()))
            }
            additionalArtifacts.forEach { expected.add(it.fileName) }
            return expected
        }

        private fun addComparison(
            job: CoreJob,
            committed: ArtifactManager.CommittedJob,
            result: JobResult.Builder,
            comparison: AbComparisonResult,
            baseline: CapturePlan,
            baselineCapture: CaptureResult,
        ) {
            result.setComparison(comparison)
            result.addArtifacts(
                fileArtifact(
                    job,
                    AbArtifactComparator.METRICS_FILE,
                    ArtifactKind.ARTIFACT_KIND_AB_METRICS,
                    dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_JSON,
                    "application/json",
                    requireArtifact(committed, AbArtifactComparator.METRICS_FILE),
                ),
            )
            val heatmaps = AbArtifactComparator.heatmapFiles(baseline)
            if (heatmaps.isNotEmpty()) {
                val baselineTarget = baseline.targets.first { target ->
                    target.outputs.any { it.role != CapturePlan.ArtifactRole.METADATA &&
                        it.format == CapturePlan.ArtifactFormat.PNG }
                }
                val baselineResource = baselineCapture.groups.first { it.name == baselineTarget.artifactName }.resource
                val group = dev.vibris.protocol.v1.ArtifactGroup.newBuilder()
                    .setName("diff-heatmap")
                    .setResource(toProtocol(baselineResource, baselineTarget, null).toBuilder()
                        .setLogicalName("comparison.diff_heatmap")
                        .setCategory("comparison"))
                val pngOutputs = baseline.targets.flatMap { target ->
                    target.outputs.filter { it.role != CapturePlan.ArtifactRole.METADATA &&
                        it.format == CapturePlan.ArtifactFormat.PNG }
                }
                for (index in heatmaps.indices) {
                    val output = pngOutputs[index]
                    group.addArtifacts(fileArtifact(
                        job, heatmaps[index], ArtifactKind.ARTIFACT_KIND_HEATMAP,
                        dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_PNG,
                        "image/png", requireArtifact(committed, heatmaps[index]),
                    ).toBuilder()
                        .setRole(if (output.subresourceIndex == null) ArtifactRole.ARTIFACT_ROLE_PRIMARY
                            else ArtifactRole.ARTIFACT_ROLE_SUBRESOURCE)
                        .apply { output.subresourceIndex?.let { setSubresourceIndex(it) } })
                }
                result.addArtifactGroups(group)
            }
        }

        private fun validateResult(plan: CapturePlan, result: CaptureResult) {
            if (result.groups.size != plan.targets.size) {
                throw RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, "Runtime capture groups did not match its plan.")
            }
            for (target in plan.targets) {
                val group = result.groups.firstOrNull { it.name == target.artifactName }
                val resource = group?.resource
                if (
                    resource == null ||
                    resource.kind != target.kind ||
                    resource.logicalName != target.logicalName ||
                    resource.frameId != result.frameId ||
                    group.artifacts.map { artifact ->
                        listOf(artifact.fileName, artifact.format, artifact.role, artifact.subresourceIndex)
                    }.toSet() != target.outputs.map { output ->
                        listOf(output.fileName, output.format, output.role, output.subresourceIndex)
                    }.toSet()
                ) {
                    throw RuntimeJobExecutor.Failure(
                        ErrorCode.CAPTURE_FAILED,
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
        ): ArtifactMetadata =
            fileArtifact(
                job,
                artifact.fileName,
                kind(target.kind),
                protocolFormat(artifact.format),
                mediaType(artifact.format),
                file,
            ).toBuilder()
                .setRole(protocolRole(artifact.role))
                .apply { artifact.subresourceIndex?.let { setSubresourceIndex(it) } }
                .setResource(toProtocol(resource, target, artifact.subresourceIndex))
                .build()

        private fun fileArtifact(
            job: CoreJob,
            fileName: String,
            kind: ArtifactKind,
            format: dev.vibris.protocol.v1.ArtifactFormat,
            mediaType: String,
            file: CommittedFile,
        ): ArtifactMetadata =
            ArtifactMetadata.newBuilder()
                .setArtifactId(
                    UUID.nameUUIDFromBytes(
                        (job.workspaceId + '\u0000' + job.requestId + '\u0000' + fileName)
                            .toByteArray(StandardCharsets.UTF_8),
                    ).toString(),
                )
                .setFileName(fileName)
                .setKind(kind)
                .setFormat(format)
                .setMediaType(mediaType)
                .setByteSize(file.byteSize)
                .setPath(file.path.toString())
                .build()

        private fun toProtocol(
            resource: ResourceCatalog.ResourceDescriptor,
            target: CapturePlan.Target,
            subresourceIndex: Int?,
        ): dev.vibris.protocol.v1.ResourceDescriptor =
            dev.vibris.protocol.v1.ResourceDescriptor.newBuilder()
                .setLogicalName(resource.logicalName)
                .setKind(
                    when (resource.kind) {
                        ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER ->
                            dev.vibris.protocol.v1.ResourceKind.RESOURCE_KIND_FINAL_FRAMEBUFFER
                        ResourceCatalog.ResourceKind.TEXTURE ->
                            dev.vibris.protocol.v1.ResourceKind.RESOURCE_KIND_TEXTURE
                        ResourceCatalog.ResourceKind.BUFFER ->
                            dev.vibris.protocol.v1.ResourceKind.RESOURCE_KIND_BUFFER
                        ResourceCatalog.ResourceKind.PATCHED_SHADERS ->
                            dev.vibris.protocol.v1.ResourceKind.RESOURCE_KIND_PATCHED_SHADERS
                    },
                )
                .setWidth(resource.width)
                .setHeight(resource.height)
                .setDepth(resource.depth)
                .setMipLevel(target.mipLevel)
                .setLayer(subresourceIndex ?: target.layer)
                .setInternalFormat(resource.internalFormat)
                .setChannelCount(resource.channelCount)
                .setScalarType(
                    dev.vibris.protocol.v1.ScalarType.valueOf(
                        "SCALAR_TYPE_" + resource.scalarType.name,
                    ),
                )
                .setByteSize(resource.byteSize)
                .setFrameId(resource.frameId)
                .setSemanticLabel(resource.semanticLabel)
                .setCategory(resource.category)
                .setTextureTarget(resource.textureTarget)
                .setChannelLayout(resource.channelLayout)
                .setNumericClass(resource.numericClass)
                .setComponentBits(resource.componentBits)
                .setReadbackFormat(resource.readbackFormat)
                .setReadbackType(resource.readbackType)
                .setMipLevels(resource.mipLevels)
                .build()

        private fun requireArtifact(
            committed: ArtifactManager.CommittedJob,
            name: String,
        ): CommittedFile {
            val path = committed.artifacts()[name]
            val byteSize = committed.fileByteSizes()[name]
            if (path == null || byteSize == null) {
                throw IOException("Runtime did not write $name.")
            }
            return CommittedFile(path, byteSize)
        }

        private fun requireManifest(committed: ArtifactManager.CommittedJob): CommittedFile {
            val byteSize = committed.fileByteSizes()["manifest.json"]
                ?: throw IOException("Artifact manifest size is unavailable.")
            return CommittedFile(committed.manifest(), byteSize)
        }

        private fun protocolFormat(
            format: CapturePlan.ArtifactFormat,
        ): dev.vibris.protocol.v1.ArtifactFormat =
            dev.vibris.protocol.v1.ArtifactFormat.valueOf("ARTIFACT_FORMAT_" + format.name)

        private fun kind(kind: ResourceCatalog.ResourceKind): ArtifactKind =
            when (kind) {
                ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER ->
                    ArtifactKind.ARTIFACT_KIND_SCREENSHOT
                ResourceCatalog.ResourceKind.TEXTURE -> ArtifactKind.ARTIFACT_KIND_TEXTURE
                ResourceCatalog.ResourceKind.BUFFER -> ArtifactKind.ARTIFACT_KIND_BUFFER
                ResourceCatalog.ResourceKind.PATCHED_SHADERS -> ArtifactKind.ARTIFACT_KIND_PATCHED_SHADER
            }

        private fun mediaType(format: CapturePlan.ArtifactFormat): String =
            when (format) {
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

    }
}
