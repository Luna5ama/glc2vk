package dev.vibris.core;

import dev.vibris.api.RuntimeStatus;
import dev.vibris.api.ReloadResult;
import dev.vibris.protocol.v1.Action;
import dev.vibris.protocol.v1.ActionSequence;
import dev.vibris.protocol.v1.ActivateSource;
import dev.vibris.protocol.v1.Capability;
import dev.vibris.protocol.v1.ClientMessage;
import dev.vibris.protocol.v1.JobActionKind;
import dev.vibris.protocol.v1.JobStage;
import dev.vibris.protocol.v1.PreparedSourceRef;
import dev.vibris.protocol.v1.RuntimeState;
import dev.vibris.protocol.v1.SceneContext;
import dev.vibris.protocol.v1.ServerMessage;
import dev.vibris.protocol.v1.ServerState;
import dev.vibris.protocol.v1.SubmitJob;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerDescriptorTest {
    @TempDir
    Path temp;

    @Test
    void reportsRuntimeStatusInsteadOfHardCodedWorldState() {
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        runtime.status = new RuntimeStatus(false, "actual-save", "minecraft:the_nether", "runtime-source");
        Path pending = temp.resolve("pending").toAbsolutePath();
        VibrisCoreEngine engine = new VibrisCoreEngine(pending, runtime);
        ServerDescriptor descriptor = new ServerDescriptor(
            pending, new ArtifactManager(temp.resolve("artifacts")), runtime);

        var status = descriptor.status(engine);

        assertFalse(status.getRuntimeReady());
        assertEquals(ServerState.SERVER_STATE_FAILED, status.getState());
        assertEquals(RuntimeState.RUNTIME_STATE_FAILED, status.getRuntimeState());
        assertEquals("actual-save", status.getCurrentSaveId());
        assertEquals("minecraft:the_nether", status.getCurrentDimensionId());
        assertEquals("runtime-source", status.getActiveSourceUuid());
        engine.close();
    }

    @Test
    void advertisesOneUnifiedJobActionSurface() {
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        Path pending = temp.resolve("pending").toAbsolutePath();
        VibrisCoreEngine engine = new VibrisCoreEngine(pending, runtime);
        ServerDescriptor descriptor = new ServerDescriptor(
            pending, new ArtifactManager(temp.resolve("artifacts")), runtime);

        var hello = descriptor.hello(engine);

        assertIterableEquals(
            java.util.List.of(
                JobActionKind.JOB_ACTION_KIND_WAIT_FRAMES,
                JobActionKind.JOB_ACTION_KIND_TAKE_SCREENSHOT,
                JobActionKind.JOB_ACTION_KIND_DUMP_TEXTURE_V2,
                JobActionKind.JOB_ACTION_KIND_DUMP_BUFFER,
                JobActionKind.JOB_ACTION_KIND_ACTIVATE_SOURCE,
                JobActionKind.JOB_ACTION_KIND_COMPARE_CAPTURES,
                JobActionKind.JOB_ACTION_KIND_GET_CAPTURE_STATUS,
                JobActionKind.JOB_ACTION_KIND_CAPTURE_PASS,
                JobActionKind.JOB_ACTION_KIND_CAPTURE_MULTI,
                JobActionKind.JOB_ACTION_KIND_INSPECT_SHADER,
                JobActionKind.JOB_ACTION_KIND_GET_GPU_METRICS,
                JobActionKind.JOB_ACTION_KIND_LIST_TEXTURES_V2,
                JobActionKind.JOB_ACTION_KIND_LIST_BUFFERS,
                JobActionKind.JOB_ACTION_KIND_GET_PATCHED_SHADERS,
                JobActionKind.JOB_ACTION_KIND_LOAD_SHADER),
            hello.getSupportedJobActionsList());
        assertIterableEquals(
            java.util.List.of(
                Capability.CAPABILITY_CONTROL_STREAM,
                Capability.CAPABILITY_RESUME,
                Capability.CAPABILITY_PREPARED_SOURCES,
                Capability.CAPABILITY_ACTION_SEQUENCE,
                Capability.CAPABILITY_ARTIFACT_METADATA),
            hello.getCapabilitiesList());
        assertTrue(hello.getStatus().getSupportedJobActionsList()
            .contains(JobActionKind.JOB_ACTION_KIND_LOAD_SHADER));
        engine.close();
    }

    @Test
    void longShaderReloadReportsBusyWithoutBlockingRuntimeStatusQuery() throws Exception {
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        Path pending = temp.resolve("pending").toAbsolutePath();
        Files.createDirectories(pending);
        VibrisCoreEngine engine = new VibrisCoreEngine(pending, runtime);
        ServerDescriptor descriptor = new ServerDescriptor(
            pending, new ArtifactManager(temp.resolve("artifacts")), runtime);
        descriptor.status(engine);
        assertEquals(1, runtime.statusCalls);
        CompletableFuture<ReloadResult> reload = new CompletableFuture<>();
        runtime.reloadStages.add(reload);
        CountDownLatch reloading = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        ControlSession session = new ControlSession(new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage message) {
                if (message.hasJobProgress() &&
                    message.getJobProgress().getStage() == JobStage.JOB_STAGE_RELOADING_SHADERS) {
                    reloading.countDown();
                }
                if (message.hasJobCompleted() || message.hasJobFailed()) terminal.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onCompleted() {
            }
        });
        String workspaceId = "11111111-1111-4111-8111-111111111111";
        session.identify(workspaceId, "process");
        PreparedSourceRef source = source(pending);
        SubmitJob submission = SubmitJob.newBuilder()
            .setRequestId("long-reload")
            .setWorkspaceId(workspaceId)
            .setContext(SceneContext.newBuilder().setSaveId("save")
                .setDimensionId("minecraft:overworld").setFov(70.0))
            .addSources(source)
            .setActions(ActionSequence.newBuilder().addActions(Action.newBuilder().setActivateSource(
                ActivateSource.newBuilder().setSourceUuid(source.getUuid()))))
            .build();
        ClientMessage message = ClientMessage.newBuilder()
            .setProtocolVersion(ProtocolMessages.V1)
            .setMessageId("message")
            .setRequestId(submission.getRequestId())
            .setWorkspaceId(workspaceId)
            .setSubmitJob(submission)
            .build();

        submit(engine, session, message);
        assertTrue(reloading.await(2, TimeUnit.SECONDS));
        runtime.status = new RuntimeStatus(false, "", "", "");
        var status = descriptor.status(engine);

        assertEquals(1, runtime.statusCalls);
        assertTrue(status.getRuntimeReady());
        assertEquals(ServerState.SERVER_STATE_BUSY, status.getState());
        assertEquals(RuntimeState.RUNTIME_STATE_RELOADING_SHADERS, status.getRuntimeState());
        assertEquals("long-reload", status.getActiveRequestId());
        reload.complete(ReloadResult.success(List.of()));
        assertTrue(terminal.await(2, TimeUnit.SECONDS));
        engine.close();
    }

    private static PreparedSourceRef source(Path pending) throws Exception {
        String uuid = UUID.randomUUID().toString();
        Path source = Files.createDirectory(pending.resolve(uuid));
        Path file = Files.writeString(source.resolve("main.glsl"), "fixture");
        return PreparedSourceRef.newBuilder()
            .setUuid(uuid)
            .setRequestedRevision("workspace")
            .setResolvedRevision("a".repeat(40))
            .setOrigin(dev.vibris.protocol.v1.SourceOrigin.newBuilder()
                .setWorkspace(dev.vibris.protocol.v1.WorkspaceOrigin.newBuilder().setDisplayName("fixture")))
            .setFileCount(1)
            .setTotalBytes(Files.size(file))
            .build();
    }

    private static void submit(VibrisCoreEngine engine, ControlSession session, ClientMessage message)
        throws Exception {
        for (var method : VibrisCoreEngine.class.getDeclaredMethods()) {
            if (method.getName().startsWith("submit") && method.getParameterCount() == 2 &&
                method.getParameterTypes()[0] == ControlSession.class &&
                method.getParameterTypes()[1] == ClientMessage.class) {
                method.setAccessible(true);
                method.invoke(engine, session, message);
                return;
            }
        }
        throw new AssertionError("VibrisCoreEngine submit method was not found");
    }
}
