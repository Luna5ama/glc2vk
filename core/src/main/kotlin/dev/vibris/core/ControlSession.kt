package dev.vibris.core

import dev.vibris.protocol.v1.ServerMessage
import io.grpc.stub.StreamObserver

internal class ControlSession(private val responses: StreamObserver<ServerMessage>) {
    private var workspaceIdValue = ""
    private var processInstanceIdValue = ""
    private var connectedValue = true

    @Synchronized
    fun identify(workspaceId: String, processInstanceId: String) {
        workspaceIdValue = workspaceId
        processInstanceIdValue = processInstanceId
    }

    @Synchronized
    fun workspaceId(): String = workspaceIdValue

    @Synchronized
    fun processInstanceId(): String = processInstanceIdValue

    @Synchronized
    fun connected(): Boolean = connectedValue

    @Synchronized
    fun send(message: ServerMessage): Boolean {
        if (!connectedValue) {
            return false
        }
        return try {
            responses.onNext(message)
            true
        } catch (_: RuntimeException) {
            connectedValue = false
            false
        }
    }

    @Synchronized
    fun complete() {
        if (!connectedValue) {
            return
        }
        connectedValue = false
        responses.onCompleted()
    }

    @Synchronized
    fun disconnect() {
        connectedValue = false
    }
}