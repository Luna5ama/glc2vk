package dev.vibris.core

import dev.vibris.core.request.RequestState
import dev.vibris.protocol.v1.ArtifactMetadata
import dev.vibris.protocol.v1.ClientMessage
import dev.vibris.protocol.v1.ErrorCode
import dev.vibris.protocol.v1.JobAccepted
import dev.vibris.protocol.v1.JobFailed
import dev.vibris.protocol.v1.JobProgress
import dev.vibris.protocol.v1.JobStage
import dev.vibris.protocol.v1.JobState
import dev.vibris.protocol.v1.ProtocolError
import dev.vibris.protocol.v1.ProtocolVersion
import dev.vibris.protocol.v1.ServerMessage

internal object ProtocolMessages {
    @JvmField
    val V1: ProtocolVersion = ProtocolVersion.newBuilder().setMajor(1).setMinor(0).build()

    @JvmStatic
    fun accepted(job: CoreJob, queuePosition: Int): ServerMessage =
        envelope(job.messageId, job.requestId, job.workspaceId)
            .setJobAccepted(
                JobAccepted.newBuilder()
                    .setRequestId(job.requestId)
                    .setQueuePosition(queuePosition),
            )
            .build()

    @JvmStatic
    fun progress(job: CoreJob, stage: JobStage): ServerMessage =
        envelope(job.messageId, job.requestId, job.workspaceId)
            .setJobProgress(
                JobProgress.newBuilder()
                    .setRequestId(job.requestId)
                    .setStage(stage),
            )
            .build()

    @JvmStatic
    fun failure(requestId: String, code: ErrorCode, message: String): TerminalResult =
        failure(requestId, code, message, emptyList())

    @JvmStatic
    fun failure(
        requestId: String,
        code: ErrorCode,
        message: String,
        artifacts: List<ArtifactMetadata>,
    ): TerminalResult {
        val error = ProtocolError.newBuilder()
            .setCode(code)
            .setMessage(message)
            .setRetryable(
                code == ErrorCode.QUEUE_FULL ||
                    code == ErrorCode.QUEUE_TIMEOUT ||
                    code == ErrorCode.EXECUTION_TIMEOUT,
            )
        if (artifacts.isNotEmpty()) {
            error.setLogPath(artifacts.first().path)
        }
        return TerminalResult.failed(
            JobFailed.newBuilder()
                .setRequestId(requestId)
                .setError(error)
                .addAllArtifacts(artifacts)
                .build(),
        )
    }

    @JvmStatic
    fun immediateFailure(
        message: ClientMessage,
        workspaceId: String,
        code: ErrorCode,
        detail: String,
    ): ServerMessage =
        failure(message.requestId, code, detail)
            .message(message.messageId, message.requestId, workspaceId)

    @JvmStatic
    fun jobState(state: RequestState): JobState =
        when (state) {
            RequestState.ACCEPTED -> JobState.JOB_STATE_QUEUED
            RequestState.RUNNING -> JobState.JOB_STATE_RUNNING
            RequestState.COMPLETED -> JobState.JOB_STATE_COMPLETED
            RequestState.FAILED -> JobState.JOB_STATE_FAILED
            RequestState.CANCELLED -> JobState.JOB_STATE_CANCELLED
        }

    @JvmStatic
    fun envelope(messageId: String, requestId: String, workspaceId: String): ServerMessage.Builder =
        ServerMessage.newBuilder()
            .setProtocolVersion(V1)
            .setMessageId(messageId)
            .setRequestId(requestId)
            .setWorkspaceId(workspaceId)
}