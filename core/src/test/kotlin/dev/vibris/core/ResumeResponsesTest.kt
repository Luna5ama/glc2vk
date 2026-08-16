package dev.vibris.core

import dev.vibris.core.request.RequestRegistry
import dev.vibris.core.request.RequestState
import dev.vibris.protocol.v2.ClientMessage
import dev.vibris.protocol.v2.ErrorCode
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration

class ResumeResponsesTest {
    @Test
    fun unknownJobAfterServerRestartReturnsRetryableTerminalFailure() {
        val message = resumeMessage("lost-request")
        val response = ResumeResponses.create(
            session(),
            message,
            registry(),
            emptyMap(),
        ).single()

        assertTrue(response.hasJobFailed())
        assertEquals(ErrorCode.ERROR_CODE_SERVER_RESTARTED, response.jobFailed.error.code)
        assertTrue(response.jobFailed.error.retryable)
        assertEquals("lost-request", response.jobFailed.requestId)
    }

    @Test
    fun cachedTerminalResumeReplaysTerminalPayloadInsteadOfNonterminalSnapshot() {
        val requests = registry()
        requests.accept("completed-request", "workspace")
        requests.finish(
            "completed-request",
            RequestState.FAILED,
            ProtocolMessages.failure(
                "completed-request",
                "completed-request",
                ErrorCode.ERROR_CODE_SHADER_COMPILE_FAILED,
                "fixture failure",
            ),
        )

        val response = ResumeResponses.create(
            session(),
            resumeMessage("completed-request"),
            requests,
            emptyMap(),
        ).single()

        assertTrue(response.hasJobFailed())
        assertEquals(ErrorCode.ERROR_CODE_SHADER_COMPILE_FAILED, response.jobFailed.error.code)
    }

    private fun registry(): RequestRegistry<TerminalResult> = RequestRegistry(
        4,
        4,
        Duration.ofMinutes(5),
        Clock.systemUTC(),
    )

    private fun resumeMessage(requestId: String): ClientMessage = ClientMessage.newBuilder()
        .setProtocolVersion(ProtocolMessages.V2)
        .setMessageId("resume-$requestId")
        .setRequestId(requestId)
        .setWorkspaceId("workspace")
        .setResumeJob(dev.vibris.protocol.v2.ResumeJob.newBuilder().setJobId(requestId))
        .build()

    private fun session(): ControlSession = ControlSession(object : StreamObserver<dev.vibris.protocol.v2.ServerMessage> {
        override fun onNext(value: dev.vibris.protocol.v2.ServerMessage) = Unit
        override fun onError(error: Throwable) = Unit
        override fun onCompleted() = Unit
    }).also { it.identify("workspace", "process") }
}
