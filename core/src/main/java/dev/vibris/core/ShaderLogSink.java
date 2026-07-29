package dev.vibris.core;

import dev.vibris.api.ReloadResult;
import dev.vibris.protocol.v1.ArtifactMetadata;

import java.io.IOException;
import java.util.List;

interface ShaderLogSink {
    ArtifactMetadata writeShaderLog(
        String workspaceId,
        String requestId,
        List<ReloadResult.Diagnostic> diagnostics
    ) throws IOException;

    static ShaderLogSink none() {
        return (workspaceId, requestId, diagnostics) -> {
            throw new IOException("Shader log storage is unavailable.");
        };
    }
}