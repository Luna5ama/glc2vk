package dev.vibris.core

import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v1.ClientMessage
import dev.vibris.protocol.v1.ErrorCode
import dev.vibris.protocol.v1.GetServerInfoRequest
import dev.vibris.protocol.v1.GetServerInfoResponse
import dev.vibris.protocol.v1.GetStatusRequest
import dev.vibris.protocol.v1.GetStatusResponse
import dev.vibris.protocol.v1.ProtocolError
import dev.vibris.protocol.v1.Pong
import dev.vibris.protocol.v1.RuntimeState
import dev.vibris.protocol.v1.ServerHello
import dev.vibris.protocol.v1.ServerMessage
import dev.vibris.protocol.v1.ServerState
import dev.vibris.protocol.v1.ServerStatus
import dev.vibris.protocol.v1.VibrisControlGrpc
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.util.concurrent.atomic.AtomicBoolean

internal class UnavailableVibrisControlService(
    private val runtime: VibrisRuntimeAdapter,
    reason: String,
) : VibrisControlGrpc.VibrisControlImplBase(), AutoCloseable {
    private val closed = AtomicBoolean()
    private val error = ProtocolError.newBuilder()
        .setCode(ErrorCode.SERVER_NOT_READY)
        .setMessage(reason.take(512))
        .setRetryable(true)
        .build()
    private val status = ServerStatus.newBuilder()
        .setState(ServerState.SERVER_STATE_FAILED)
        .setRuntimeReady(false)
        .setRuntimeState(RuntimeState.RUNTIME_STATE_FAILED)
        .build()
    private val hello = ServerHello.newBuilder()
        .setProtocolVersion(ProtocolMessages.V1)
        .setServerVersion("vibris-core")
        .setReady(false)
        .setStatus(status)
        .build()

    override fun getServerInfo(
        request: GetServerInfoRequest,
        observer: StreamObserver<GetServerInfoResponse>,
    ) {
        observer.onNext(GetServerInfoResponse.newBuilder().setServer(hello).build())
        observer.onCompleted()
    }

    override fun getStatus(request: GetStatusRequest, observer: StreamObserver<GetStatusResponse>) {
        observer.onNext(
            GetStatusResponse.newBuilder()
                .setReady(false)
                .setStatus(status)
                .addErrors(error)
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
                            ErrorCode.SERVER_NOT_READY,
                            error.message,
                        ),
                    )
                }
            }

            private fun greet(message: ClientMessage) {
                if (!message.hasClientHello() || message.clientHello.workspaceId.isBlank()) {
                    responses.onError(
                        Status.INVALID_ARGUMENT.withDescription("CLIENT_HELLO_REQUIRED").asRuntimeException(),
                    )
                    return
                }
                workspaceId = message.clientHello.workspaceId
                responses.onNext(
                    ProtocolMessages.envelope(message.messageId, message.requestId, message.clientHello.workspaceId)
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