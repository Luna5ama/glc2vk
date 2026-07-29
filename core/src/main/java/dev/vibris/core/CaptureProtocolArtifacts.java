package dev.vibris.core;

import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.protocol.v1.AbComparisonResult;
import dev.vibris.protocol.v1.ArtifactKind;
import dev.vibris.protocol.v1.ArtifactMetadata;
import dev.vibris.protocol.v1.DiagnosticSeverity;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobResult;
import dev.vibris.protocol.v1.JobResultKind;
import dev.vibris.protocol.v1.ShaderDiagnostic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class CaptureProtocolArtifacts {
    JobResult commit(CoreJob job, List<CapturePlan> plans, List<CaptureResult> captured,
        ArtifactManager.JobTransaction transaction, List<ReloadResult.Diagnostic> diagnostics,
        AbComparisonResult comparison) throws Exception {
        if (plans.size() != captured.size()) throw new RuntimeJobExecutor.Failure(
            ErrorCode.CAPTURE_FAILED, "Runtime capture count did not match its plan.");
        for (int index = 0; index < plans.size(); index++) validateResult(plans.get(index), captured.get(index));
        ArtifactManager.CommittedJob committed = transaction.commit(expectedArtifacts(plans, comparison != null));
        JobResult.Builder result = JobResult.newBuilder()
            .setKind(resultKind(job))
            .setManifestPath(committed.manifest().toString());
        for (int index = 0; index < plans.size(); index++) {
            CapturePlan plan = plans.get(index);
            CaptureResult capture = captured.get(index);
            result.addFrameIds(capture.frameId());
            for (CapturePlan.Target target : plan.targets()) {
                ResourceCatalog.ResourceDescriptor resource = capture.artifacts().get(target.artifactName());
                CommittedFile file = requireArtifact(committed, target.fileName());
                if (target.format() == CapturePlan.ArtifactFormat.RAW ||
                    target.format() == CapturePlan.ArtifactFormat.BIN) {
                    requireArtifact(committed, target.metadataFileName());
                }
                result.addArtifacts(captureArtifact(job, target, resource, file));
            }
        }
        if (comparison != null) addComparison(job, committed, result, comparison);
        result.addArtifacts(fileArtifact(job, "shader.log", ArtifactKind.ARTIFACT_KIND_SHADER_COMPILE_LOG,
            dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_TEXT,
            "text/plain; charset=utf-8", requireArtifact(committed, "shader.log")));
        result.addArtifacts(fileArtifact(job, "manifest.json", ArtifactKind.ARTIFACT_KIND_MANIFEST,
            dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_JSON,
            "application/json", requireManifest(committed)));
        addDiagnostics(result, diagnostics, requireArtifact(committed, "shader.log").path.toString());
        return result.build();
    }

    static void addDiagnostics(JobResult.Builder result, List<ReloadResult.Diagnostic> diagnostics, String logPath) {
        for (ReloadResult.Diagnostic diagnostic : diagnostics) {
            result.addShaderDiagnostics(ShaderDiagnostic.newBuilder()
                .setSeverity(DiagnosticSeverity.valueOf("DIAGNOSTIC_SEVERITY_" + diagnostic.severity().name()))
                .setFileName(diagnostic.source())
                .setLine(diagnostic.line())
                .setMessage(diagnostic.message())
                .setLogPath(logPath));
        }
    }

    private static Set<String> expectedArtifacts(List<CapturePlan> plans, boolean comparison) {
        Set<String> expected = new LinkedHashSet<>();
        expected.add("shader.log");
        for (CapturePlan plan : plans) {
            for (CapturePlan.Target target : plan.targets()) {
                expected.add(target.fileName());
                if (target.format() == CapturePlan.ArtifactFormat.RAW ||
                    target.format() == CapturePlan.ArtifactFormat.BIN) {
                    expected.add(target.metadataFileName());
                }
            }
        }
        if (comparison) {
            expected.add(AbArtifactComparator.METRICS_FILE);
            expected.add(AbArtifactComparator.HEATMAP_FILE);
        }
        return expected;
    }

    private static void addComparison(CoreJob job, ArtifactManager.CommittedJob committed,
        JobResult.Builder result, AbComparisonResult comparison) throws java.io.IOException {
        result.setComparison(comparison);
        result.addArtifacts(fileArtifact(job, AbArtifactComparator.METRICS_FILE,
            ArtifactKind.ARTIFACT_KIND_AB_METRICS,
            dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_JSON,
            "application/json", requireArtifact(committed, AbArtifactComparator.METRICS_FILE)));
        result.addArtifacts(fileArtifact(job, AbArtifactComparator.HEATMAP_FILE,
            ArtifactKind.ARTIFACT_KIND_HEATMAP,
            dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_PNG,
            "image/png", requireArtifact(committed, AbArtifactComparator.HEATMAP_FILE)));
    }

    private static void validateResult(CapturePlan plan, CaptureResult result)
        throws RuntimeJobExecutor.Failure {
        for (CapturePlan.Target target : plan.targets()) {
            ResourceCatalog.ResourceDescriptor resource = result.artifacts().get(target.artifactName());
            if (resource == null || resource.kind() != target.kind() ||
                !resource.logicalName().equals(target.logicalName()) || resource.frameId() != result.frameId()) {
                throw new RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED,
                    "Runtime capture result did not match its plan.");
            }
        }
    }

    private static ArtifactMetadata captureArtifact(CoreJob job, CapturePlan.Target target,
        ResourceCatalog.ResourceDescriptor resource, CommittedFile file) {
        return fileArtifact(job, target.fileName(), kind(target.kind()), protocolFormat(target.format()),
            mediaType(target.format()), file).toBuilder().setResource(toProtocol(resource, target)).build();
    }

    private static ArtifactMetadata fileArtifact(CoreJob job, String fileName, ArtifactKind kind,
        dev.vibris.protocol.v1.ArtifactFormat format, String mediaType, CommittedFile file) {
        return ArtifactMetadata.newBuilder()
            .setArtifactId(UUID.nameUUIDFromBytes((job.workspaceId + '\0' + job.requestId + '\0' + fileName)
                .getBytes(StandardCharsets.UTF_8)).toString())
            .setFileName(fileName)
            .setKind(kind)
            .setFormat(format)
            .setMediaType(mediaType)
            .setByteSize(file.byteSize)
            .setPath(file.path.toString())
            .build();
    }

    private static dev.vibris.protocol.v1.ResourceDescriptor toProtocol(
        ResourceCatalog.ResourceDescriptor resource, CapturePlan.Target target) {
        return dev.vibris.protocol.v1.ResourceDescriptor.newBuilder()
            .setLogicalName(resource.logicalName())
            .setKind(switch (resource.kind()) {
                case FINAL_FRAMEBUFFER -> dev.vibris.protocol.v1.ResourceKind.RESOURCE_KIND_FINAL_FRAMEBUFFER;
                case TEXTURE -> dev.vibris.protocol.v1.ResourceKind.RESOURCE_KIND_TEXTURE;
                case BUFFER -> dev.vibris.protocol.v1.ResourceKind.RESOURCE_KIND_BUFFER;
            })
            .setWidth(resource.width())
            .setHeight(resource.height())
            .setDepth(resource.depth())
            .setMipLevel(target.mipLevel())
            .setLayer(target.layer())
            .setInternalFormat(resource.internalFormat())
            .setChannelCount(resource.channelCount())
            .setScalarType(dev.vibris.protocol.v1.ScalarType.valueOf("SCALAR_TYPE_" + resource.scalarType().name()))
            .setByteSize(resource.byteSize())
            .setFrameId(resource.frameId())
            .setSemanticLabel(resource.semanticLabel())
            .build();
    }

    private static CommittedFile requireArtifact(ArtifactManager.CommittedJob committed, String name)
        throws java.io.IOException {
        Path path = committed.artifacts().get(name);
        Long byteSize = committed.fileByteSizes().get(name);
        if (path == null || byteSize == null) throw new java.io.IOException("Runtime did not write " + name + '.');
        return new CommittedFile(path, byteSize);
    }

    private static CommittedFile requireManifest(ArtifactManager.CommittedJob committed) throws java.io.IOException {
        Long byteSize = committed.fileByteSizes().get("manifest.json");
        if (byteSize == null) throw new java.io.IOException("Artifact manifest size is unavailable.");
        return new CommittedFile(committed.manifest(), byteSize);
    }

    private static dev.vibris.protocol.v1.ArtifactFormat protocolFormat(CapturePlan.ArtifactFormat format) {
        return dev.vibris.protocol.v1.ArtifactFormat.valueOf("ARTIFACT_FORMAT_" + format.name());
    }

    private static ArtifactKind kind(ResourceCatalog.ResourceKind kind) {
        return switch (kind) {
            case FINAL_FRAMEBUFFER -> ArtifactKind.ARTIFACT_KIND_SCREENSHOT;
            case TEXTURE -> ArtifactKind.ARTIFACT_KIND_TEXTURE;
            case BUFFER -> ArtifactKind.ARTIFACT_KIND_BUFFER;
        };
    }

    private static String mediaType(CapturePlan.ArtifactFormat format) {
        return switch (format) {
            case PNG -> "image/png";
            case EXR -> "image/x-exr";
            case RAW, BIN -> "application/octet-stream";
        };
    }

    private static JobResultKind resultKind(CoreJob job) {
        if (job.submission.hasRecipe() && job.submission.getRecipe().hasReloadAndCapture()) {
            return JobResultKind.JOB_RESULT_KIND_RELOAD_AND_CAPTURE;
        }
        if (job.submission.hasRecipe() && job.submission.getRecipe().hasCaptureDebugBundle()) {
            return JobResultKind.JOB_RESULT_KIND_CAPTURE_DEBUG_BUNDLE;
        }
        if (job.submission.hasRecipe() && job.submission.getRecipe().hasAbCompare()) {
            return JobResultKind.JOB_RESULT_KIND_AB_COMPARE;
        }
        return JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE;
    }

    private record CommittedFile(Path path, long byteSize) {
    }
}