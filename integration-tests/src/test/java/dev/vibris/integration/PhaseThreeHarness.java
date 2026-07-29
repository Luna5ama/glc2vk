package dev.vibris.integration;

import dev.vibris.protocol.v1.Action;
import dev.vibris.protocol.v1.ActionSequence;
import dev.vibris.protocol.v1.Capability;
import dev.vibris.protocol.v1.CancelJob;
import dev.vibris.protocol.v1.ClientHello;
import dev.vibris.protocol.v1.ClientMessage;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobCompleted;
import dev.vibris.protocol.v1.JobFailed;
import dev.vibris.protocol.v1.JobProgress;
import dev.vibris.protocol.v1.JobStage;
import dev.vibris.protocol.v1.JobSummary;
import dev.vibris.protocol.v1.JobTimeouts;
import dev.vibris.protocol.v1.PreparedSourceRef;
import dev.vibris.protocol.v1.ProtocolVersion;
import dev.vibris.protocol.v1.ResumeRequest;
import dev.vibris.protocol.v1.ResumeState;
import dev.vibris.protocol.v1.SceneContext;
import dev.vibris.protocol.v1.ServerMessage;
import dev.vibris.protocol.v1.SourceOrigin;
import dev.vibris.protocol.v1.SubmitJob;
import dev.vibris.protocol.v1.VibrisControlGrpc;
import dev.vibris.protocol.v1.WaitFrames;
import dev.vibris.protocol.v1.WorkspaceOrigin;
import dev.vibris.testruntime.FakeVibrisServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class PhaseThreeHarness {
    static final Duration WAIT = Duration.ofSeconds(5);
    private static final ProtocolVersion V1 = ProtocolVersion.newBuilder().setMajor(1).setMinor(0).build();

    private PhaseThreeHarness() {
    }

    static Source createSource(Path pendingRoot, String requestId) throws IOException {
        String uuid = UUID.nameUUIDFromBytes(requestId.getBytes(StandardCharsets.UTF_8)).toString();
        Path directory = Files.createDirectories(pendingRoot.resolve(uuid));
        byte[] content = ("// " + requestId + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("main.glsl"), content);
        PreparedSourceRef reference = PreparedSourceRef.newBuilder()
            .setUuid(uuid)
            .setFileCount(1)
            .setTotalBytes(content.length)
            .setOrigin(SourceOrigin.newBuilder().setWorkspace(
                WorkspaceOrigin.newBuilder().setDisplayName(requestId)))
            .build();
        return new Source(directory, reference);
    }

    static SceneContext context(String id) {
        return SceneContext.newBuilder()
            .setSaveId("test-save")
            .setDimensionId("minecraft:overworld")
            .setTimePresetId("noon")
            .setWeatherPresetId("clear")
            .setCameraPresetId("origin")
            .setFov(60.0 + id.length())
            .setResolution(dev.vibris.protocol.v1.Resolution.newBuilder().setWidth(320).setHeight(180))
            .setSettingsPresetId(id)
            .build();
    }

    static SubmitJob job(String requestId, String workspaceId, SceneContext context, PreparedSourceRef source,
                         long executionTimeoutMs, int frames) {
        ActionSequence actions = ActionSequence.newBuilder()
            .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(frames)))
            .build();
        return SubmitJob.newBuilder()
            .setRequestId(requestId)
            .setWorkspaceId(workspaceId)
            .setContext(context)
            .addSources(source)
            .setActions(actions)
            .setTimeouts(JobTimeouts.newBuilder().setExecutionTimeoutMs(executionTimeoutMs).setTotalTimeoutMs(10_000))
            .build();
    }

    static PreparedSourceRef invalidSource(String uuid) {
        return PreparedSourceRef.newBuilder()
            .setUuid(uuid)
            .setFileCount(1)
            .setTotalBytes(1)
            .setOrigin(SourceOrigin.newBuilder().setWorkspace(
                WorkspaceOrigin.newBuilder().setDisplayName("invalid")))
            .build();
    }

    static void assertDirectoryEmpty(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var children = Files.list(root)) {
            assertTrue(children.findAny().isEmpty(), () -> "Expected empty directory: " + root);
        }
    }

    record Source(Path directory, PreparedSourceRef reference) {
    }

    static final class Client implements AutoCloseable {
        private final ManagedChannel channel;
        private final String workspaceId;
        private final List<ServerMessage> messages = new ArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final StreamObserver<ClientMessage> requests;
        private int messageSequence;
        private boolean disconnected;

        Client(FakeVibrisServer server, String workspaceId) throws InterruptedException {
            this.workspaceId = workspaceId;
            channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
            requests = VibrisControlGrpc.newStub(channel).control(new StreamObserver<>() {
                @Override
                public void onNext(ServerMessage message) {
                    synchronized (messages) {
                        messages.add(message);
                        messages.notifyAll();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.set(throwable);
                    synchronized (messages) {
                        messages.notifyAll();
                    }
                }

                @Override
                public void onCompleted() {
                    synchronized (messages) {
                        messages.notifyAll();
                    }
                }
            });
            send(ClientMessage.newBuilder()
                .setClientHello(ClientHello.newBuilder()
                    .setProtocolVersion(V1)
                    .setMcpVersion("phase-three-red")
                    .setWorkspaceId(workspaceId)
                    .setProcessInstanceUuid(UUID.randomUUID().toString())
                    .addCapabilities(Capability.CAPABILITY_CONTROL_STREAM)
                    .addCapabilities(Capability.CAPABILITY_RESUME)
                    .addCapabilities(Capability.CAPABILITY_PREPARED_SOURCES)));
            await(ServerMessage::hasServerHello, "ServerHello");
        }

        void submit(SubmitJob job) {
            send(ClientMessage.newBuilder().setRequestId(job.getRequestId()).setSubmitJob(job));
        }

        void cancel(String requestId) {
            send(ClientMessage.newBuilder()
                .setRequestId(requestId)
                .setCancelJob(CancelJob.newBuilder().setRequestId(requestId).setReason("integration test")));
        }

        JobSummary resume(String requestId) throws InterruptedException {
            ResumeState response = resumeState(requestId);
            return response.getJobsList().stream()
                .filter(job -> job.getRequestId().equals(requestId))
                .findFirst()
                .orElseThrow();
        }

        ResumeState resumeState(String requestId) throws InterruptedException {
            send(ClientMessage.newBuilder()
                .setRequestId(requestId)
                .setResumeRequest(ResumeRequest.newBuilder().addRequestIds(requestId)));
            return await(ServerMessage::hasResumeState, "ResumeState for " + requestId).getResumeState();
        }

        void sendWorkspaceViolation(String requestId) {
            requests.onNext(ClientMessage.newBuilder()
                .setProtocolVersion(V1)
                .setMessageId(workspaceId + "-violation")
                .setRequestId(requestId)
                .setWorkspaceId(workspaceId + "-foreign")
                .setResumeRequest(ResumeRequest.newBuilder().addRequestIds(requestId))
                .build());
        }

        void awaitStreamFailure() throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            synchronized (messages) {
                while (failure.get() == null) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new AssertionError("Control stream did not fail.");
                    TimeUnit.NANOSECONDS.timedWait(messages, remaining);
                }
            }
        }

        void awaitAccepted(String requestId) throws InterruptedException {
            await(message -> message.hasJobAccepted()
                && message.getJobAccepted().getRequestId().equals(requestId), "JobAccepted for " + requestId);
        }

        JobProgress awaitProgress(String requestId, JobStage stage) throws InterruptedException {
            return await(message -> message.hasJobProgress()
                && message.getJobProgress().getRequestId().equals(requestId)
                && message.getJobProgress().getStage() == stage, stage + " for " + requestId).getJobProgress();
        }

        JobCompleted awaitCompleted(String requestId) throws InterruptedException {
            return await(message -> message.hasJobCompleted()
                && message.getJobCompleted().getRequestId().equals(requestId),
                "JobCompleted for " + requestId).getJobCompleted();
        }

        JobFailed awaitFailed(String requestId, ErrorCode code) throws InterruptedException {
            return await(message -> message.hasJobFailed()
                && message.getJobFailed().getRequestId().equals(requestId)
                && message.getJobFailed().getError().getCode() == code,
                "JobFailed(" + code + ") for " + requestId).getJobFailed();
        }

        void disconnect() throws InterruptedException {
            disconnected = true;
            requests.onError(Status.CANCELLED.withDescription("test disconnect").asRuntimeException());
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }

        private void send(ClientMessage.Builder message) {
            requests.onNext(message
                .setProtocolVersion(V1)
                .setMessageId(workspaceId + "-" + ++messageSequence)
                .setWorkspaceId(workspaceId)
                .build());
        }

        private ServerMessage await(
            Predicate<ServerMessage> predicate,
            String description
        ) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            synchronized (messages) {
                while (true) {
                    for (int index = 0; index < messages.size(); index++) {
                        ServerMessage message = messages.get(index);
                        if (predicate.test(message)) {
                            messages.remove(index);
                            return message;
                        }
                    }
                    if (failure.get() != null) {
                        throw new AssertionError(
                            "Control stream failed while waiting for " + description,
                            failure.get()
                        );
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new AssertionError(
                            "Phase 3 server did not emit " + description + "; received " + messages
                        );
                    }
                    TimeUnit.NANOSECONDS.timedWait(messages, remaining);
                }
            }
        }

        @Override
        public void close() throws InterruptedException {
            if (!disconnected) requests.onCompleted();
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    static final class Probe {
        private final Object delegate;

        Probe(FakeVibrisServer server) {
            delegate = invoke(server, "phaseThreeProbe", new Class<?>[0]);
        }

        void pauseExecution() {
            invoke(delegate, "pauseExecution", new Class<?>[0]);
        }

        void resumeExecution() {
            invoke(delegate, "resumeExecution", new Class<?>[0]);
        }

        @SuppressWarnings("unchecked")
        List<String> strings(String method) {
            return List.copyOf((List<String>) invoke(delegate, method, new Class<?>[0]));
        }

        @SuppressWarnings("unchecked")
        List<SceneContext> contexts() {
            return List.copyOf((List<SceneContext>) invoke(delegate, "contextSnapshots", new Class<?>[0]));
        }

        List<String> sourceStates(String uuid) {
            @SuppressWarnings("unchecked")
            List<String> states = (List<String>) invoke(delegate, "sourceStates", new Class<?>[]{String.class}, uuid);
            return List.copyOf(states);
        }

        int integer(String method) {
            return (Integer) invoke(delegate, method, new Class<?>[0]);
        }

        int executionCount(String requestId) {
            return (Integer) invoke(delegate, "executionCount", new Class<?>[]{String.class}, requestId);
        }

        void assertRegistriesBounded() {
            assertTrue(integer("requestRegistrySize") <= integer("requestRegistryCapacity"));
            assertTrue(integer("sourceRegistrySize") <= integer("sourceRegistryCapacity"));
            assertTrue(integer("queueSize") <= integer("queueCapacity"));
        }

        private static Object invoke(Object target, String name, Class<?>[] parameters, Object... arguments) {
            try {
                Method method = target.getClass().getMethod(name, parameters);
                return method.invoke(target, arguments);
            } catch (NoSuchMethodException exception) {
                throw new AssertionError("Missing Phase 3 fake-runtime test seam: " + name, exception);
            } catch (IllegalAccessException exception) {
                throw new AssertionError("Inaccessible Phase 3 fake-runtime test seam: " + name, exception);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new AssertionError("Phase 3 fake-runtime test seam failed: " + name, cause);
            }
        }
    }
}