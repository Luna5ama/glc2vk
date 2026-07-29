package dev.vibris.core;

import dev.vibris.protocol.v1.JobCompleted;
import dev.vibris.protocol.v1.JobFailed;
import dev.vibris.protocol.v1.ProtocolVersion;
import dev.vibris.protocol.v1.ServerMessage;

record TerminalResult(JobCompleted completed, JobFailed failed) {
    private static final ProtocolVersion V1 = ProtocolVersion.newBuilder().setMajor(1).setMinor(0).build();

    static TerminalResult completed(JobCompleted value) {
        return new TerminalResult(value, null);
    }

    static TerminalResult failed(JobFailed value) {
        return new TerminalResult(null, value);
    }

    boolean successful() {
        return completed != null;
    }

    ServerMessage message(String messageId, String requestId, String workspaceId) {
        ServerMessage.Builder result = ServerMessage.newBuilder()
            .setProtocolVersion(V1)
            .setMessageId(messageId)
            .setRequestId(requestId)
            .setWorkspaceId(workspaceId);
        return successful() ? result.setJobCompleted(completed).build() : result.setJobFailed(failed).build();
    }
}