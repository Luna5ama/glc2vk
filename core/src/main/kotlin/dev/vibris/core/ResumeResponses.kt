package dev.vibris.core

import dev.vibris.core.request.RequestRegistry
import dev.vibris.protocol.v2.ClientMessage
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobStateSnapshot
import dev.vibris.protocol.v2.JobSummary
import dev.vibris.protocol.v2.ServerMessage

internal object ResumeResponses {
    @JvmStatic
    fun create(
        session: ControlSession,
        message: ClientMessage,
        requests: RequestRegistry<TerminalResult>,
        liveJobs: Map<String, CoreJob>,
    ): List<ServerMessage> {
        val requestId = message.resumeJob.jobId
        val snapshot = requests.resume(requestId, session.workspaceId()).orElse(null)
            ?: return listOf(
                ProtocolMessages.failure(
                    requestId,
                    requestId,
                    ErrorCode.ERROR_CODE_SERVER_RESTARTED,
                    "The requested job is not present in this Vibris server instance and may be submitted again.",
                ).message(message.messageId, message.requestId, session.workspaceId()),
            )
        snapshot.result?.let { terminal ->
            return listOf(terminal.message(message.messageId, message.requestId, session.workspaceId()))
        }
        val job = liveJobs[requestId]
        if (job != null && job.workspaceId == session.workspaceId()) {
            job.bind(session)
        }
        val summary = JobSummary.newBuilder()
            .setJobId(job?.submission?.jobId ?: requestId)
            .setRequestId(requestId)
            .setWorkspaceId(session.workspaceId())
            .setOperation(job?.submission?.workloadCase?.name?.lowercase().orEmpty())
            .setState(ProtocolMessages.jobState(snapshot.state))
            .build()
        val state = JobStateSnapshot.newBuilder().setSummary(summary)
        snapshot.result?.completed?.result?.let(state::setResult)
        snapshot.result?.failed?.error?.let(state::setError)
        return listOf(
            ProtocolMessages.envelope(message.messageId, message.requestId, session.workspaceId())
                .setJobState(state)
                .build(),
        )
    }
}
