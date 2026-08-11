package dev.vibris.core

import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.ClientMessage
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.GetServerInfoRequest
import dev.vibris.protocol.v2.GetServerInfoResponse
import dev.vibris.protocol.v2.GetStatusRequest
import dev.vibris.protocol.v2.GetStatusResponse
import dev.vibris.protocol.v2.Pong
import dev.vibris.protocol.v2.RuntimeFailure
import dev.vibris.protocol.v2.RuntimePhase
import dev.vibris.protocol.v2.RuntimeReadiness
import dev.vibris.protocol.v2.ServerHello
import dev.vibris.protocol.v2.ServerMessage
import dev.vibris.protocol.v2.ServerState
import dev.vibris.protocol.v2.ServerStatus
import dev.vibris.protocol.v2.VibrisControlGrpc
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.util.concurrent.atomic.AtomicBoolean

internal class UnavailableVibrisControlService(
    private val runtime: VibrisRuntimeAdapter,
    reason: String,
) : VibrisControlGrpc.VibrisControlImplBase(), AutoCloseable {
    private val closed = AtomicBoolean()
    private val error = RuntimeFailure.newBuilder()
        .setCode(ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE)
        .setMessage(reason.take(512))
        .setRetryable(true)
        .setFailedAtUnixMs(System.currentTimeMillis())
        .setPhase(RuntimePhase.RUNTIME_PHASE_FAILED.name)
        .setRecoveryAction("Reconnect the runtime bridge and retry status.")
        .build()
    private val status = ServerStatus.newBuilder()
        .setState(ServerState.SERVER_STATE_FAILED)
        .setReadiness(
            RuntimeReadiness.newBuilder()
                .setCoreOnline(false)
                .setPhase(RuntimePhase.RUNTIME_PHASE_FAILED)
                .setDetail(error.message),
        )
        .setCanAcceptJob(false)
        .setCanStartJob(false)
        .setLastError(error)
        .build()
    private val hello = ServerHello.newBuilder()
        .setServerVersion("vibris-core")
        .setStatus(status)
        .build()

    override fun getServerInfo(
        request: GetServerInfoRequest,
        observer: StreamObserver<GetServerInfoResponse>,
    ) {
        observer.onNext(
            GetServerInfoResponse.newBuilder()
                .setProtocolVersion(ProtocolMessages.V2)
                .setServer(hello)
                .build(),
        )
        observer.onCompleted()
    }

    override fun getStatus(request: GetStatusRequest, observer: StreamObserver<GetStatusResponse>) {
        observer.onNext(
            GetStatusResponse.newBuilder()
                .setProtocolVersion(ProtocolMessages.V2)
                .setStatus(status)
                .build(),
        )
        observer.onCompleted()
    }

    override fun control(responses: StreamObserver<ServerMessage>): StreamObserver<ClientMessage> =
        object : StreamObserver<ClientMessage> {
            private var workspaceId: String? = null

            override fun onNext(message: ClientMessage) {
                val workspace = workspaceId
                if (workspace == null) {
                    greet(message)
                } else if (message.hasPing()) {
                    responses.onNext(
                        ProtocolMessages.envelope(message.messageId, message.requestId, workspace)
                            .setPong(
                                Pong.newBuilder()
                                    .setSequence(message.ping.sequence)
                                    .setClientTimeUnixMs(message.ping.clientTimeUnixMs)
                                    .setServerTimeUnixMs(message.ping.clientTimeUnixMs),
                            )
                            .build(),
                    )
                } else {
                    responses.onNext(
                        ProtocolMessages.immediateFailure(
                            message,
                            workspace,
                            ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE,
                            error.message,
                        ),
                    )
                }
            }

            private fun greet(message: ClientMessage) {
                if (!message.hasClientHello() || message.workspaceId.isBlank()) {
                    responses.onError(
                        Status.INVALID_ARGUMENT.withDescription("CLIENT_HELLO_REQUIRED").asRuntimeException(),
                    )
                    return
                }
                workspaceId = message.workspaceId
                responses.onNext(
                    ProtocolMessages.envelope(message.messageId, message.requestId, message.workspaceId)
                        .setServerHello(hello)
                        .build(),
                )
            }

            override fun onError(throwable: Throwable) = Unit

            override fun onCompleted() = responses.onCompleted()
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runtime.close()
        }
    }
}
