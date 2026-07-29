package dev.vibris.core;

import dev.vibris.protocol.v1.ClientMessage;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobAccepted;
import dev.vibris.protocol.v1.JobFailed;
import dev.vibris.protocol.v1.JobProgress;
import dev.vibris.protocol.v1.JobStage;
import dev.vibris.protocol.v1.JobState;
import dev.vibris.protocol.v1.ProtocolError;
import dev.vibris.protocol.v1.ProtocolVersion;
import dev.vibris.protocol.v1.ServerMessage;

final class ProtocolMessages {
    static final ProtocolVersion V1 = ProtocolVersion.newBuilder().setMajor(1).setMinor(0).build();

    private ProtocolMessages() {
    }

    static ServerMessage accepted(CoreJob job, int queuePosition) {
        return envelope(job.messageId, job.requestId, job.workspaceId)
            .setJobAccepted(JobAccepted.newBuilder()
                .setRequestId(job.requestId)
                .setQueuePosition(queuePosition))
            .build();
    }

    static ServerMessage progress(CoreJob job, JobStage stage) {
        return envelope(job.messageId, job.requestId, job.workspaceId)
            .setJobProgress(JobProgress.newBuilder()
                .setRequestId(job.requestId)
                .setStage(stage))
            .build();
    }

    static TerminalResult failure(String requestId, ErrorCode code, String message) {
        ProtocolError error = ProtocolError.newBuilder()
            .setCode(code)
            .setMessage(message)
            .setRetryable(code == ErrorCode.QUEUE_FULL || code == ErrorCode.QUEUE_TIMEOUT ||
                code == ErrorCode.EXECUTION_TIMEOUT)
            .build();
        return TerminalResult.failed(JobFailed.newBuilder()
            .setRequestId(requestId)
            .setError(error)
            .build());
    }

    static ServerMessage immediateFailure(ClientMessage message, String workspaceId, ErrorCode code, String detail) {
        return failure(message.getRequestId(), code, detail)
            .message(message.getMessageId(), message.getRequestId(), workspaceId);
    }

    static JobState jobState(dev.vibris.core.request.RequestState state) {
        return switch (state) {
            case ACCEPTED -> JobState.JOB_STATE_QUEUED;
            case RUNNING -> JobState.JOB_STATE_RUNNING;
            case COMPLETED -> JobState.JOB_STATE_COMPLETED;
            case FAILED -> JobState.JOB_STATE_FAILED;
            case CANCELLED -> JobState.JOB_STATE_CANCELLED;
        };
    }

    static ServerMessage.Builder envelope(String messageId, String requestId, String workspaceId) {
        return ServerMessage.newBuilder()
            .setProtocolVersion(V1)
            .setMessageId(messageId)
            .setRequestId(requestId)
            .setWorkspaceId(workspaceId);
    }
}