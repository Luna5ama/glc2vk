package dev.vibris.core;

import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResourceNotFoundException;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class CaptureJobExecutor {
    private final ArtifactManager artifacts;
    private final CapturePlanBuilder plans = new CapturePlanBuilder();
    private final CaptureProtocolArtifacts protocol = new CaptureProtocolArtifacts();

    CaptureJobExecutor(ArtifactManager artifacts) {
        this.artifacts = artifacts;
    }

    int waitFrames(CoreJob job) throws RuntimeJobExecutor.Failure {
        return plans.waitFrames(job);
    }

    Prepared prepare(CoreJob job, ResourceCatalog catalog, List<ReloadResult.Diagnostic> diagnostics)
        throws RuntimeJobExecutor.Failure {
        CapturePlanBuilder.Plan planned = plans.build(job, catalog);
        if (planned.capture().targets().isEmpty()) return null;
        if (artifacts == null) {
            throw new RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, "Artifact storage is unavailable.");
        }
        byte[] shaderLog = shaderLog(diagnostics);
        ArtifactManager.JobTransaction transaction = null;
        try {
            transaction = artifacts.beginJob(
                job.workspaceId, job.requestId, Math.addExact(planned.estimatedBytes(), shaderLog.length));
            try (OutputStream output = transaction.open("shader.log")) {
                output.write(shaderLog);
            }
            return new Prepared(transaction, planned.capture());
        } catch (Exception exception) {
            closeAfterFailure(transaction, exception);
            throw failure(exception);
        }
    }

    JobResult commit(CoreJob job, Prepared prepared, CaptureResult captured)
        throws RuntimeJobExecutor.Failure {
        try {
            return protocol.commit(job, prepared.plan, captured, prepared.transaction);
        } catch (Exception exception) {
            throw failure(exception);
        }
    }

    static RuntimeJobExecutor.Failure failure(Throwable exception) {
        for (Throwable cause = exception; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof ArtifactManager.JobTooLargeException || cause instanceof ArithmeticException) {
                return new RuntimeJobExecutor.Failure(
                    ErrorCode.ARTIFACT_JOB_TOO_LARGE, "Artifact job is too large.");
            }
            if (cause instanceof ArtifactManager.QuotaExceededException) {
                return new RuntimeJobExecutor.Failure(ErrorCode.ARTIFACT_QUOTA_EXCEEDED, cause.getMessage());
            }
            if (cause instanceof CaptureResourceNotFoundException) {
                return new RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_RESOURCE_NOT_FOUND, cause.getMessage());
            }
            if (cause instanceof RuntimeJobExecutor.Failure failure) return failure;
        }
        return new RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, "Capture artifact creation failed.");
    }

    private static void closeAfterFailure(ArtifactManager.JobTransaction transaction, Exception original) {
        if (transaction == null) return;
        try {
            transaction.close();
        } catch (IOException closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    private static byte[] shaderLog(List<ReloadResult.Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) return "Shader reload succeeded.\n".getBytes(StandardCharsets.UTF_8);
        StringBuilder output = new StringBuilder();
        for (ReloadResult.Diagnostic diagnostic : diagnostics) {
            output.append('[').append(diagnostic.severity()).append("] ").append(diagnostic.source());
            if (diagnostic.line() > 0) output.append(':').append(diagnostic.line());
            output.append(": ").append(diagnostic.message()).append(System.lineSeparator());
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    final class Prepared implements AutoCloseable {
        private final ArtifactManager.JobTransaction transaction;
        private final CapturePlan plan;

        private Prepared(ArtifactManager.JobTransaction transaction, CapturePlan plan) {
            this.transaction = transaction;
            this.plan = plan;
        }

        CapturePlan plan() {
            return plan;
        }

        ArtifactManager.JobTransaction sink() {
            return transaction;
        }

        @Override
        public void close() throws IOException {
            transaction.close();
        }
    }
}