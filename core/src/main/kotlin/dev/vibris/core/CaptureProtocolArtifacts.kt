package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v1.AbComparisonResult
import dev.vibris.protocol.v1.ArtifactKind
import dev.vibris.protocol.v1.ArtifactMetadata
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
        val committed = transaction.commit(expectedArtifacts(plans, comparison != null))
        val result = JobResult.newBuilder()
            .setKind(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE)
            .setManifestPath(committed.manifest().toString())
        for (index in plans.indices) {
            val plan = plans[index]
            val capture = captured[index]
            result.addFrameIds(capture.frameId)
            for (target in plan.targets) {
                val resource = capture.artifacts[target.artifactName]
                val file = requireArtifact(committed, target.fileName())
                if (
                    target.format == CapturePlan.ArtifactFormat.RAW ||
                    target.format == CapturePlan.ArtifactFormat.BIN
                ) {
                    requireArtifact(committed, target.metadataFileName())
                }
                result.addArtifacts(captureArtifact(job, target, resource!!, file))
            }
        }
        if (comparison != null) {
            addComparison(job, committed, result, comparison)
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
        ): Set<String> {
            val expected = LinkedHashSet<String>()
            expected.add("shader.log")
            for (plan in plans) {
                for (target in plan.targets) {
                    expected.add(target.fileName())
                    if (
                        target.format == CapturePlan.ArtifactFormat.RAW ||
                        target.format == CapturePlan.ArtifactFormat.BIN
                    ) {
                        expected.add(target.metadataFileName())
                    }
                }
            }
            if (comparison) {
                expected.add(AbArtifactComparator.METRICS_FILE)
                expected.add(AbArtifactComparator.HEATMAP_FILE)
            }
            return expected
        }

        private fun addComparison(
            job: CoreJob,
            committed: ArtifactManager.CommittedJob,
            result: JobResult.Builder,
            comparison: AbComparisonResult,
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
            result.addArtifacts(
                fileArtifact(
                    job,
                    AbArtifactComparator.HEATMAP_FILE,
                    ArtifactKind.ARTIFACT_KIND_HEATMAP,
                    dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_PNG,
                    "image/png",
                    requireArtifact(committed, AbArtifactComparator.HEATMAP_FILE),
                ),
            )
        }

        private fun validateResult(plan: CapturePlan, result: CaptureResult) {
            for (target in plan.targets) {
                val resource = result.artifacts[target.artifactName]
                if (
                    resource == null ||
                    resource.kind != target.kind ||
                    resource.logicalName != target.logicalName ||
                    resource.frameId != result.frameId
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
            file: CommittedFile,
        ): ArtifactMetadata =
            fileArtifact(
                job,
                target.fileName(),
                kind(target.kind),
                protocolFormat(target.format),
                mediaType(target.format),
                file,
            ).toBuilder()
                .setResource(toProtocol(resource, target))
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
                    },
                )
                .setWidth(resource.width)
                .setHeight(resource.height)
                .setDepth(resource.depth)
                .setMipLevel(target.mipLevel)
                .setLayer(target.layer)
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
            }

        private fun mediaType(format: CapturePlan.ArtifactFormat): String =
            when (format) {
                CapturePlan.ArtifactFormat.PNG -> "image/png"
                CapturePlan.ArtifactFormat.EXR -> "image/x-exr"
                CapturePlan.ArtifactFormat.RAW,
                CapturePlan.ArtifactFormat.BIN,
                -> "application/octet-stream"
            }

    }
}
