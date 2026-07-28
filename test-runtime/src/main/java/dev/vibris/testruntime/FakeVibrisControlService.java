package dev.vibris.testruntime;

import dev.vibris.protocol.v1.ActionKind;
import dev.vibris.protocol.v1.ArtifactFormat;
import dev.vibris.protocol.v1.Capability;
import dev.vibris.protocol.v1.ClientMessage;
import dev.vibris.protocol.v1.GetServerInfoRequest;
import dev.vibris.protocol.v1.GetServerInfoResponse;
import dev.vibris.protocol.v1.GetStatusRequest;
import dev.vibris.protocol.v1.GetStatusResponse;
import dev.vibris.protocol.v1.ListPresetsRequest;
import dev.vibris.protocol.v1.ListPresetsResponse;
import dev.vibris.protocol.v1.Pong;
import dev.vibris.protocol.v1.ProtocolVersion;
import dev.vibris.protocol.v1.RecipeKind;
import dev.vibris.protocol.v1.RuntimeState;
import dev.vibris.protocol.v1.SceneContext;
import dev.vibris.protocol.v1.ScenePreset;
import dev.vibris.protocol.v1.ServerHello;
import dev.vibris.protocol.v1.ServerLimits;
import dev.vibris.protocol.v1.ServerMessage;
import dev.vibris.protocol.v1.ServerState;
import dev.vibris.protocol.v1.ServerStatus;
import dev.vibris.protocol.v1.ValidateContextRequest;
import dev.vibris.protocol.v1.ValidateContextResponse;
import dev.vibris.protocol.v1.VibrisControlGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.nio.file.Path;

final class FakeVibrisControlService extends VibrisControlGrpc.VibrisControlImplBase {
    private static final ProtocolVersion V1 = ProtocolVersion.newBuilder().setMajor(1).setMinor(0).build();

    private final ServerStatus status;
    private final ServerHello hello;

    FakeVibrisControlService(Path pendingShadersRoot, Path artifactRoot) {
        status = ServerStatus.newBuilder()
            .setState(ServerState.SERVER_STATE_READY)
            .setRuntimeReady(true)
            .setRuntimeState(RuntimeState.RUNTIME_STATE_READY)
            .setCurrentSaveId("test-save")
            .setCurrentDimensionId("minecraft:overworld")
            .setPendingShadersRoot(pendingShadersRoot.toString())
            .setArtifactRoot(artifactRoot.toString())
            .setArtifactQuotaCapBytes(3L * 1024 * 1024 * 1024)
            .addSupportedRecipes(RecipeKind.RECIPE_KIND_RELOAD_AND_CAPTURE)
            .addSupportedRecipes(RecipeKind.RECIPE_KIND_CAPTURE_DEBUG_BUNDLE)
            .addSupportedRecipes(RecipeKind.RECIPE_KIND_AB_COMPARE)
            .addAllSupportedActions(java.util.List.of(
                ActionKind.ACTION_KIND_RESET_TEMPORAL_STATE,
                ActionKind.ACTION_KIND_WAIT_FRAMES,
                ActionKind.ACTION_KIND_CAPTURE_SCREENSHOT,
                ActionKind.ACTION_KIND_DUMP_TEXTURE,
                ActionKind.ACTION_KIND_DUMP_BUFFER
            ))
            .addAllSupportedFormats(java.util.List.of(
                ArtifactFormat.ARTIFACT_FORMAT_PNG,
                ArtifactFormat.ARTIFACT_FORMAT_EXR,
                ArtifactFormat.ARTIFACT_FORMAT_RAW,
                ArtifactFormat.ARTIFACT_FORMAT_BIN
            ))
            .build();
        hello = ServerHello.newBuilder()
            .setProtocolVersion(V1)
            .setServerVersion("vibris-test-runtime")
            .addCapabilities(Capability.CAPABILITY_CONTROL_STREAM)
            .addCapabilities(Capability.CAPABILITY_RESUME)
            .addCapabilities(Capability.CAPABILITY_PREPARED_SOURCES)
            .setReady(true)
            .setStatus(status)
            .setLimits(ServerLimits.newBuilder().setMaxSourceBytes(256L * 1024 * 1024).setMaxSourceFiles(100_000))
            .addAllSupportedRecipes(status.getSupportedRecipesList())
            .addAllSupportedActions(status.getSupportedActionsList())
            .addAllSupportedFormats(status.getSupportedFormatsList())
            .setPendingShadersRoot(pendingShadersRoot.toString())
            .setArtifactRoot(artifactRoot.toString())
            .build();
    }

    @Override
    public void getServerInfo(GetServerInfoRequest request, StreamObserver<GetServerInfoResponse> observer) {
        observer.onNext(GetServerInfoResponse.newBuilder().setServer(hello).build());
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
        observer.onNext(GetStatusResponse.newBuilder().setReady(true).setStatus(status).build());
        observer.onCompleted();
    }

    @Override
    public StreamObserver<ClientMessage> control(StreamObserver<ServerMessage> observer) {
        return new StreamObserver<>() {
            private boolean greeted;
            private boolean terminated;

            @Override
            public void onNext(ClientMessage message) {
                if (terminated) return;
                if (!greeted) {
                    handleFirst(message);
                    return;
                }
                if (message.hasPing()) {
                    Pong pong = Pong.newBuilder()
                        .setSequence(message.getPing().getSequence())
                        .setClientTimeUnixMs(message.getPing().getClientTimeUnixMs())
                        .setServerTimeUnixMs(message.getPing().getClientTimeUnixMs())
                        .build();
                    observer.onNext(envelope(message).setPong(pong).build());
                }
            }

            private void handleFirst(ClientMessage message) {
                if (!message.hasClientHello()) {
                    terminate(Status.INVALID_ARGUMENT.withDescription("CLIENT_HELLO_REQUIRED"));
                    return;
                }
                if (message.getClientHello().getProtocolVersion().getMajor() != 1) {
                    terminate(Status.FAILED_PRECONDITION.withDescription("PROTOCOL_MISMATCH"));
                    return;
                }
                greeted = true;
                observer.onNext(envelope(message).setServerHello(hello).build());
            }

            private ServerMessage.Builder envelope(ClientMessage message) {
                return ServerMessage.newBuilder()
                    .setProtocolVersion(V1)
                    .setMessageId(message.getMessageId())
                    .setRequestId(message.getRequestId())
                    .setWorkspaceId(message.getWorkspaceId());
            }

            private void terminate(Status status) {
                terminated = true;
                observer.onError(status.asRuntimeException());
            }

            @Override
            public void onError(Throwable throwable) {
                terminated = true;
            }

            @Override
            public void onCompleted() {
                if (!terminated) observer.onCompleted();
                terminated = true;
            }
        };
    }
}