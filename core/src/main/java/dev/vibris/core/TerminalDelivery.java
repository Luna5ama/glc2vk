package dev.vibris.core;

import dev.vibris.protocol.v1.ServerMessage;

final class TerminalDelivery {
    private final ArtifactManager artifacts;

    TerminalDelivery(ShaderLogSink shaderLogs) {
        artifacts = shaderLogs instanceof ArtifactManager manager ? manager : null;
    }

    void send(ControlSession session, ServerMessage message, String workspaceId, String requestId) {
        boolean delivered = session.send(message);
        if (delivered && artifacts != null && (message.hasJobCompleted() || message.hasJobFailed())) {
            artifacts.markReported(workspaceId, requestId);
        }
    }
}