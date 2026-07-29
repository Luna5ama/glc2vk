package dev.vibris.core

import dev.vibris.protocol.v1.ServerMessage

internal class TerminalDelivery(shaderLogs: ShaderLogSink) {
    private val artifacts = shaderLogs as? ArtifactManager

    fun send(
        session: ControlSession,
        message: ServerMessage,
        workspaceId: String,
        requestId: String,
    ) {
        val delivered = session.send(message)
        if (delivered && artifacts != null && (message.hasJobCompleted() || message.hasJobFailed())) {
            artifacts.markReported(workspaceId, requestId)
        }
    }
}