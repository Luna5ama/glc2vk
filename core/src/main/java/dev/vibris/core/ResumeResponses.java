package dev.vibris.core;

import dev.vibris.core.request.RequestRegistry;
import dev.vibris.protocol.v1.ClientMessage;
import dev.vibris.protocol.v1.JobSummary;
import dev.vibris.protocol.v1.ResumeState;
import dev.vibris.protocol.v1.ServerMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ResumeResponses {
    private ResumeResponses() {
    }

    static List<ServerMessage> create(
        ControlSession session,
        ClientMessage message,
        RequestRegistry<TerminalResult> requests,
        Map<String, CoreJob> liveJobs
    ) {
        ResumeState.Builder result = ResumeState.newBuilder();
        List<ServerMessage> terminalReplay = new ArrayList<>();
        for (String requestId : message.getResumeRequest().getRequestIdsList()) {
            requests.resume(requestId, session.workspaceId()).ifPresent(snapshot -> {
                CoreJob job = liveJobs.get(requestId);
                if (job != null && job.workspaceId.equals(session.workspaceId())) job.bind(session);
                result.addJobs(JobSummary.newBuilder()
                    .setRequestId(requestId)
                    .setState(ProtocolMessages.jobState(snapshot.state())));
                if (snapshot.state().terminal()) {
                    terminalReplay.add(snapshot.result().message(
                        message.getMessageId(), requestId, session.workspaceId()));
                }
            });
        }
        List<ServerMessage> responses = new ArrayList<>(terminalReplay.size() + 1);
        responses.add(ProtocolMessages.envelope(
            message.getMessageId(), message.getRequestId(), session.workspaceId())
            .setResumeState(result)
            .build());
        responses.addAll(terminalReplay);
        return responses;
    }
}