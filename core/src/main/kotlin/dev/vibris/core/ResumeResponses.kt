package dev.vibris.core

import dev.vibris.core.request.RequestRegistry
import dev.vibris.protocol.v1.ClientMessage
import dev.vibris.protocol.v1.JobSummary
import dev.vibris.protocol.v1.ResumeState
import dev.vibris.protocol.v1.ServerMessage

internal object ResumeResponses {
    @JvmStatic
    fun create(
        session: ControlSession,
        message: ClientMessage,
        requests: RequestRegistry<TerminalResult>,
        liveJobs: Map<String, CoreJob>,
    ): List<ServerMessage> {
        val result = ResumeState.newBuilder()
        val terminalReplay = ArrayList<ServerMessage>()
        for (requestId in message.resumeRequest.requestIdsList) {
            requests.resume(requestId, session.workspaceId()).ifPresent { snapshot ->
                val job = liveJobs[requestId]
                if (job != null && job.workspaceId == session.workspaceId()) {
                    job.bind(session)
                }
                result.addJobs(
                    JobSummary.newBuilder()
                        .setRequestId(requestId)
                        .setState(ProtocolMessages.jobState(snapshot.state)),
                )
                if (snapshot.state.terminal()) {
                    terminalReplay.add(
                        snapshot.result!!.message(message.messageId, requestId, session.workspaceId()),
                    )
                }
            }
        }
        val responses = ArrayList<ServerMessage>(terminalReplay.size + 1)
        responses.add(
            ProtocolMessages.envelope(message.messageId, message.requestId, session.workspaceId())
                .setResumeState(result)
                .build(),
        )
        responses.addAll(terminalReplay)
        return responses
    }
}