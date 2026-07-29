package dev.vibris.core;

import dev.vibris.api.VibrisRuntimeAdapter;
import dev.vibris.protocol.v1.ClientMessage;
import dev.vibris.protocol.v1.GetServerInfoRequest;
import dev.vibris.protocol.v1.GetServerInfoResponse;
import dev.vibris.protocol.v1.GetStatusRequest;
import dev.vibris.protocol.v1.GetStatusResponse;
import dev.vibris.protocol.v1.ListPresetsRequest;
import dev.vibris.protocol.v1.ListPresetsResponse;
import dev.vibris.protocol.v1.Pong;
import dev.vibris.protocol.v1.SceneContext;
import dev.vibris.protocol.v1.ScenePreset;
import dev.vibris.protocol.v1.ServerHello;
import dev.vibris.protocol.v1.ServerMessage;
import dev.vibris.protocol.v1.ValidateContextRequest;
import dev.vibris.protocol.v1.ValidateContextResponse;
import dev.vibris.protocol.v1.VibrisControlGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.nio.file.Path;

public final class VibrisControlService extends VibrisControlGrpc.VibrisControlImplBase implements AutoCloseable {
    private final ServerDescriptor descriptor;
    private final VibrisCoreEngine engine;
    private final ArtifactManager artifacts;

    public VibrisControlService(Path pendingRoot, Path artifactRoot, VibrisRuntimeAdapter runtime) {
        this(pendingRoot, artifactRoot, runtime, ShaderLink.transientLink());
    }

    VibrisControlService(
        Path pendingRoot,
        Path artifactRoot,
        VibrisRuntimeAdapter runtime,
        ShaderLink shaderLink
    ) {
        Path pending = pendingRoot.toAbsolutePath().normalize();
        artifacts = new ArtifactManager(artifactRoot);
        engine = new VibrisCoreEngine(pending, runtime, shaderLink, artifacts);
        descriptor = new ServerDescriptor(pending, artifacts, runtime);
    }

    public CoreProbe probe() {
        return engine.probe();
    }

    @Override
    public void getServerInfo(GetServerInfoRequest request, StreamObserver<GetServerInfoResponse> observer) {
        observer.onNext(GetServerInfoResponse.newBuilder().setServer(descriptor.hello(engine)).build());
        observer.onCompleted();
    }

    @Override
    public void listPresets(ListPresetsRequest request, StreamObserver<ListPresetsResponse> observer) {
        SceneContext context = SceneContext.newBuilder()
            .setSaveId("test-save")
            .setDimensionId("minecraft:overworld")
            .setTimePresetId("noon")
            .setCameraPresetId("origin")
            .setFov(70.0)
            .build();
        observer.onNext(ListPresetsResponse.newBuilder()
            .addPresets(ScenePreset.newBuilder().setPresetId("default").setDisplayName("Default").setContext(context))
            .build());
        observer.onCompleted();
    }

    @Override
    public void validateContext(ValidateContextRequest request, StreamObserver<ValidateContextResponse> observer) {
        observer.onNext(ValidateContextResponse.newBuilder().setValid(request.hasContext()).build());
        observer.onCompleted();
    }

    @Override
    public void getStatus(GetStatusRequest request, StreamObserver<GetStatusResponse> observer) {
        var status = descriptor.status(engine);
        observer.onNext(GetStatusResponse.newBuilder().setReady(status.getRuntimeReady()).setStatus(status).build());
        observer.onCompleted();
    }

    @Override
    public StreamObserver<ClientMessage> control(StreamObserver<ServerMessage> responses) {
        ControlSession session = new ControlSession(responses);
        return new StreamObserver<>() {
            private boolean greeted;
            private boolean terminated;

            @Override
            public void onNext(ClientMessage message) {
                if (terminated) return;
                if (!greeted) {
                    greet(message);
                    return;
                }
                if (message.getProtocolVersion().getMajor() != 1) {
                    fail(Status.FAILED_PRECONDITION.withDescription("PROTOCOL_MISMATCH"));
                    return;
                }
                if (!message.getWorkspaceId().equals(session.workspaceId())) {
                    fail(Status.PERMISSION_DENIED.withDescription("WORKSPACE_MISMATCH"));
                    return;
                }
                if (message.hasCancelJob() &&
                    !message.getRequestId().equals(message.getCancelJob().getRequestId())) {
                    fail(Status.INVALID_ARGUMENT.withDescription("REQUEST_ID_MISMATCH"));
                    return;
                }
                if (message.hasPing()) {
                    Pong pong = Pong.newBuilder()
                        .setSequence(message.getPing().getSequence())
                        .setClientTimeUnixMs(message.getPing().getClientTimeUnixMs())
                        .setServerTimeUnixMs(message.getPing().getClientTimeUnixMs())
                        .build();
                    session.send(ProtocolMessages.envelope(
                        message.getMessageId(), message.getRequestId(), session.workspaceId()).setPong(pong).build());
                } else if (message.hasSubmitJob()) {
                    engine.submit(session, message);
                } else if (message.hasCancelJob()) {
                    engine.cancel(session, message.getCancelJob().getRequestId());
                } else if (message.hasResumeRequest()) {
                    engine.resume(session, message);
                }
            }

            private void greet(ClientMessage message) {
                if (!message.hasClientHello()) {
                    fail(Status.INVALID_ARGUMENT.withDescription("CLIENT_HELLO_REQUIRED"));
                    return;
                }
                if (message.getClientHello().getProtocolVersion().getMajor() != 1) {
                    fail(Status.FAILED_PRECONDITION.withDescription("PROTOCOL_MISMATCH"));
                    return;
                }
                String workspace = message.getClientHello().getWorkspaceId();
                if (workspace.isBlank()) {
                    fail(Status.INVALID_ARGUMENT.withDescription("WORKSPACE_ID_REQUIRED"));
                    return;
                }
                greeted = true;
                session.identify(workspace, message.getClientHello().getProcessInstanceUuid());
                session.send(ProtocolMessages.envelope(
                    message.getMessageId(), message.getRequestId(), workspace)
                    .setServerHello(descriptor.hello(engine)).build());
            }

            private void fail(Status status) {
                if (terminated) return;
                terminated = true;
                engine.disconnected(session);
                responses.onError(status.asRuntimeException());
            }

            @Override
            public void onError(Throwable throwable) {
                if (terminated) return;
                terminated = true;
                engine.disconnected(session);
            }

            @Override
            public void onCompleted() {
                if (terminated) return;
                terminated = true;
                session.complete();
                engine.disconnected(session);
            }
        };
    }

    @Override
    public void close() {
        engine.close();
    }
}