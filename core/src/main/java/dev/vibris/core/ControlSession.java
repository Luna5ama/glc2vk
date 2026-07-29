package dev.vibris.core;

import dev.vibris.protocol.v1.ServerMessage;
import io.grpc.stub.StreamObserver;

final class ControlSession {
    private final StreamObserver<ServerMessage> responses;
    private String workspaceId = "";
    private String processInstanceId = "";
    private boolean connected = true;

    ControlSession(StreamObserver<ServerMessage> responses) {
        this.responses = responses;
    }

    synchronized void identify(String workspaceId, String processInstanceId) {
        this.workspaceId = workspaceId;
        this.processInstanceId = processInstanceId;
    }

    synchronized String workspaceId() {
        return workspaceId;
    }

    synchronized String processInstanceId() {
        return processInstanceId;
    }

    synchronized boolean connected() {
        return connected;
    }

    synchronized void send(ServerMessage message) {
        if (!connected) return;
        try {
            responses.onNext(message);
        } catch (RuntimeException exception) {
            connected = false;
        }
    }

    synchronized void complete() {
        if (!connected) return;
        connected = false;
        responses.onCompleted();
    }

    synchronized void disconnect() {
        connected = false;
    }
}