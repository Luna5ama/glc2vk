package dev.vibris.core;

import dev.vibris.api.ReloadResult;
import dev.vibris.protocol.v1.ArtifactMetadata;
import dev.vibris.protocol.v1.ErrorCode;

import java.io.IOException;

final class ShaderReloadFailure {
    private ShaderReloadFailure() {
    }

    static RuntimeJobExecutor.Failure create(ShaderLogSink logs, CoreJob job, ReloadResult reload) {
        String message = reload.diagnostics().stream()
            .filter(diagnostic -> diagnostic.severity() == ReloadResult.Severity.ERROR)
            .map(ReloadResult.Diagnostic::message)
            .findFirst()
            .orElse("Shader reload failed.");
        try {
            ArtifactMetadata artifact = logs.writeShaderLog(
                job.workspaceId, job.requestId, reload.diagnostics());
            return new RuntimeJobExecutor.Failure(ErrorCode.SHADER_COMPILE_FAILED, message, artifact);
        } catch (IOException exception) {
            return new RuntimeJobExecutor.Failure(ErrorCode.SHADER_COMPILE_FAILED, message);
        }
    }
}