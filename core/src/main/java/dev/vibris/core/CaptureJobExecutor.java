package dev.vibris.core;

import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResourceNotFoundException;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobResult;
import dev.vibris.protocol.v1.AbComparisonResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class CaptureJobExecutor {
    private final ArtifactManager artifacts;
    private final CapturePlanBuilder plans = new CapturePlanBuilder();
    private final CaptureProgramBuilder programs = new CaptureProgramBuilder();
    private final CaptureProtocolArtifacts protocol = new CaptureProtocolArtifacts();
    private final AbArtifactComparator comparisons = new AbArtifactComparator();

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
        return prepare(job, List.of(planned.capture()), planned.estimatedBytes(), diagnostics);
    }

    ActionPrepared prepareActions(CoreJob job, ResourceCatalog catalog, List<ReloadResult.Diagnostic> diagnostics)
        throws RuntimeJobExecutor.Failure {
        CaptureProgramBuilder.ActionProgram program = programs.actions(job, catalog);
        List<CapturePlan> captures = program.steps().stream()
            .filter(step -> step.type() == CaptureProgramBuilder.ActionType.CAPTURE)
            .map(CaptureProgramBuilder.ActionStep::capture).toList();
        Prepared prepared = captures.isEmpty() ? null
            : prepare(job, captures, program.estimatedBytes(), diagnostics);
        return new ActionPrepared(program, prepared);
    }

    AbPrepared prepareAb(CoreJob job, ResourceCatalog catalog, List<ReloadResult.Diagnostic> diagnostics)
        throws RuntimeJobExecutor.Failure {
        CaptureProgramBuilder.AbProgram program = programs.ab(job, catalog);
        return new AbPrepared(program, prepare(
            job, List.of(program.baseline(), program.candidate()), program.estimatedBytes(), diagnostics));
    }

    private Prepared prepare(CoreJob job, List<CapturePlan> capturePlans, long estimate,
        List<ReloadResult.Diagnostic> diagnostics) throws RuntimeJobExecutor.Failure {
        if (artifacts == null) {
            throw new RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, "Artifact storage is unavailable.");
        }
        byte[] shaderLog = shaderLog(diagnostics);
        ArtifactManager.JobTransaction transaction = null;
        try {
            transaction = artifacts.beginJob(
                job.workspaceId, job.requestId, Math.addExact(estimate, shaderLog.length));
            return new Prepared(transaction, capturePlans, diagnostics);
        } catch (Exception exception) {
            closeAfterFailure(transaction, exception);
            throw failure(exception);
        }
    }

    JobResult commit(CoreJob job, Prepared prepared, CaptureResult captured)
        throws RuntimeJobExecutor.Failure {
        return commit(job, prepared, List.of(captured), null);
    }

    JobResult commit(CoreJob job, Prepared prepared, List<CaptureResult> captured,
        AbComparisonResult comparison) throws RuntimeJobExecutor.Failure {
        try {
            prepared.writeShaderLog();
            return protocol.commit(
                job, prepared.plans, captured, prepared.transaction, prepared.diagnostics, comparison);
        } catch (Exception exception) {
            throw failure(exception);
        }
    }

    AbComparisonResult compare(CoreJob job, AbPrepared prepared) throws RuntimeJobExecutor.Failure {
        var recipe = job.submission.getRecipe().getAbCompare();
        try {
            return comparisons.compare(prepared.prepared.transaction,
                prepared.program.baseline(), prepared.program.candidate(),
                recipe.getBaseline().getLabel(), recipe.getCandidate().getLabel());
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
        private final List<CapturePlan> plans;
        private final List<ReloadResult.Diagnostic> diagnostics;
        private boolean shaderLogWritten;

        private Prepared(ArtifactManager.JobTransaction transaction, List<CapturePlan> plans,
            List<ReloadResult.Diagnostic> diagnostics) {
            this.transaction = transaction;
            this.plans = List.copyOf(plans);
            this.diagnostics = new ArrayList<>(diagnostics);
        }

        CapturePlan plan() {
            return plans.getFirst();
        }

        ArtifactManager.JobTransaction sink() {
            return transaction;
        }

        void addDiagnostics(List<ReloadResult.Diagnostic> additional) {
            if (shaderLogWritten) throw new IllegalStateException("Shader log has already been finalized.");
            diagnostics.addAll(additional);
        }

        private void writeShaderLog() throws IOException {
            if (shaderLogWritten) throw new IOException("Shader log has already been written.");
            try (OutputStream output = transaction.open("shader.log")) {
                output.write(shaderLog(diagnostics));
            }
            shaderLogWritten = true;
        }

        @Override
        public void close() throws IOException {
            transaction.close();
        }
    }

    record ActionPrepared(CaptureProgramBuilder.ActionProgram program, Prepared prepared) {
    }

    record AbPrepared(CaptureProgramBuilder.AbProgram program, Prepared prepared) {
    }
}