package dev.vibris.core;

import dev.vibris.api.SceneContext;
import dev.vibris.api.ScenePreset;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.GetStatusRequest;
import dev.vibris.protocol.v1.GetStatusResponse;
import dev.vibris.protocol.v1.ValidateContextRequest;
import dev.vibris.protocol.v1.ValidateContextResponse;
import dev.vibris.protocol.v1.VibrisControlGrpc;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class VibrisBootstrapTest {
    @TempDir
    Path temp;

    @Test
    void configPreservesJavaRecordAbi() {
        assertTrue(VibrisBootstrap.Config.class.isRecord());
        assertEquals(java.lang.Record.class, VibrisBootstrap.Config.class.getSuperclass());
        assertEquals(
            List.of("port", "pendingShadersRoot", "artifactRoot", "shaderpackRoot"),
            java.util.Arrays.stream(VibrisBootstrap.Config.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList()
        );
    }

    @Test
    void configCanonicalConstructorNormalizesPaths() {
        Path relativePending = Path.of("build", ".", "pending-relative");
        Path relativeArtifacts = Path.of("build", "intermediate", "..", "artifacts-relative");
        Path absoluteShaderpack = temp.resolve("shaderpack-parent").resolve("..").resolve("shaderpack");

        VibrisBootstrap.Config config = new VibrisBootstrap.Config(
            50_051,
            relativePending,
            relativeArtifacts,
            absoluteShaderpack
        );

        assertEquals(relativePending.toAbsolutePath().normalize(), config.pendingShadersRoot());
        assertEquals(relativeArtifacts.toAbsolutePath().normalize(), config.artifactRoot());
        assertEquals(absoluteShaderpack.toAbsolutePath().normalize(), config.shaderpackRoot());
    }

    @Test
    void missingServerConfigStartsListenerInNotReadyState() throws Exception {
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        AtomicReference<BindableService> captured = new AtomicReference<>();

        VibrisBootstrap bootstrap = VibrisBootstrap.start(temp, runtime, (address, service) -> {
            assertEquals(new InetSocketAddress("127.0.0.1", 50051), address);
            captured.set(service);
            return new TestListener();
        });

        assertFalse(bootstrap.ready());
        GetStatusResponse status = status(captured.get());
        assertFalse(status.getReady());
        assertEquals(ErrorCode.SERVER_NOT_READY, status.getErrors(0).getCode());
        assertTrue(status.getErrors(0).getMessage().contains("server.json"));
        bootstrap.close();
        assertEquals(1, runtime.closeCount);
    }

    @Test
    void missingConfiguredRootUsesConfiguredAddressAndReportsReason() throws Exception {
        Path pending = temp.resolve("missing-pending");
        Path artifacts = Files.createDirectory(temp.resolve("configured-artifacts"));
        Path shaderpack = Files.createDirectory(temp.resolve("configured-shaderpack"));
        writeServerConfig(temp, pending, artifacts, shaderpack, 50123);
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        AtomicReference<BindableService> captured = new AtomicReference<>();

        VibrisBootstrap bootstrap = VibrisBootstrap.start(temp, runtime, (address, service) -> {
            assertEquals(new InetSocketAddress("127.0.0.1", 50123), address);
            captured.set(service);
            return new TestListener();
        });

        assertFalse(bootstrap.ready());
        GetStatusResponse status = status(captured.get());
        assertEquals(ErrorCode.SERVER_NOT_READY, status.getErrors(0).getCode());
        assertTrue(status.getErrors(0).getMessage().contains("pending_shaders_root"));
        bootstrap.close();
    }

    @Test
    void configuredRootUsesARealWriteProbe() throws Exception {
        String externalRoot = System.getenv("VIBRIS_TEST_WRITABLE_ROOT");
        Path pending = externalRoot == null
            ? Files.createDirectory(temp.resolve("writable-pending"))
            : Path.of(externalRoot);
        Path artifacts = Files.createDirectory(temp.resolve("writable-artifacts"));
        Path shaderpack = Files.createDirectory(temp.resolve("writable-shaderpack"));
        writeServerConfig(temp, pending, artifacts, shaderpack, 50123);

        ServerConfiguration configuration = ServerConfiguration.Companion.load(temp);

        assertEquals(pending.toAbsolutePath().normalize(), configuration.getPaths().pendingShadersRoot());
    }

    @Test
    void notReadyStatusIsQueryableOverLoopbackGrpc() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        Path pending = temp.resolve("offline-ram-root");
        Path artifacts = Files.createDirectory(temp.resolve("grpc-artifacts"));
        Path shaderpack = Files.createDirectory(temp.resolve("grpc-shaderpack"));
        writeServerConfig(temp, pending, artifacts, shaderpack, port);
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();

        VibrisBootstrap bootstrap = VibrisBootstrap.start(temp, runtime);
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", bootstrap.port())
            .usePlaintext()
            .build();
        try {
            GetStatusResponse response = VibrisControlGrpc.newBlockingStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .getStatus(GetStatusRequest.getDefaultInstance());
            assertFalse(response.getReady());
            assertEquals(ErrorCode.SERVER_NOT_READY, response.getErrors(0).getCode());
            assertTrue(response.getErrors(0).getMessage().contains("pending_shaders_root"));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            bootstrap.close();
        }
    }

    @Test
    void serverConfigStartsConfiguredService() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("configured-pending"));
        Path artifacts = Files.createDirectory(temp.resolve("ready-artifacts"));
        Path shaderpack = Files.createDirectory(temp.resolve("ready-shaderpack"));
        writeServerConfig(temp, pending, artifacts, shaderpack, 50124);
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();

        VibrisBootstrap bootstrap = VibrisBootstrap.start(temp, runtime, (address, service) -> {
            assertEquals(new InetSocketAddress("127.0.0.1", 50124), address);
            assertTrue(service instanceof VibrisControlService);
            return new TestListener();
        });

        assertTrue(bootstrap.ready());
        assertEquals(pending.toAbsolutePath().normalize(), bootstrap.pendingShadersRoot());
        bootstrap.close();
    }

    @Test
    void validatedContextIsAppliedWhenMinecraftStartsAgain() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("auto-enter-pending"));
        Path artifacts = Files.createDirectory(temp.resolve("auto-enter-artifacts"));
        Path shaderpack = Files.createDirectory(temp.resolve("auto-enter-shaderpack"));
        writeServerConfig(temp, pending, artifacts, shaderpack, 50125);
        AtomicReference<BindableService> captured = new AtomicReference<>();
        RuntimeTestAdapter configuredRuntime = new RuntimeTestAdapter();
        VibrisBootstrap configured = VibrisBootstrap.start(temp, configuredRuntime, (address, service) -> {
            captured.set(service);
            return new TestListener();
        });
        dev.vibris.protocol.v1.SceneContext context = dev.vibris.protocol.v1.SceneContext.newBuilder()
            .setSaveId("shader-test-world")
            .setDimensionId("minecraft:overworld")
            .setTimePresetId("rooftop")
            .setCameraPresetId("rooftop")
            .setFov(70.0)
            .build();

        ValidateContextResponse validation = validate(captured.get(), context);
        configured.close();

        assertTrue(validation.getValid());
        RuntimeTestAdapter restartedRuntime = new RuntimeTestAdapter();
        restartedRuntime.presets = List.of(new ScenePreset(
            "rooftop",
            "Rooftop",
            new SceneContext(
                "shader-test-world",
                "minecraft:overworld",
                "rooftop",
                "clear",
                "rooftop",
                70.0,
                new SceneContext.Resolution(1280, 720),
                "default"
            )
        ));
        VibrisBootstrap restarted = VibrisBootstrap.start(temp, restartedRuntime, (address, service) ->
            new TestListener());
        assertEquals(List.of("context"), restartedRuntime.events);
        assertEquals("shader-test-world", restartedRuntime.lastContext.saveId());
        assertEquals("rooftop", restartedRuntime.lastContext.cameraPresetId());
        assertEquals("clear", restartedRuntime.lastContext.weatherPresetId());
        assertEquals(new SceneContext.Resolution(1280, 720), restartedRuntime.lastContext.resolution());
        restarted.close();
    }

    @Test
    void startupCleansLinkAndPendingRootBeforeListenerStarts() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending"));
        Path stale = Files.createDirectory(pending.resolve("stale"));
        Files.writeString(stale.resolve("main.glsl"), "stale");
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("sentinel.txt"), "user");
        Files.createSymbolicLink(pending.resolve("stale-link"), outside);
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack"));
        Files.createSymbolicLink(shaderpack.resolve("shaders"), stale);
        Path artifacts = temp.resolve("artifacts");
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        AtomicBoolean listened = new AtomicBoolean();
        TestListener listener = new TestListener(runtime.events);
        VibrisBootstrap.Config config = new VibrisBootstrap.Config(0, pending, artifacts, shaderpack);

        // When
        VibrisBootstrap bootstrap = VibrisBootstrap.start(config, runtime, (address, service) -> {
            assertEquals(new InetSocketAddress("127.0.0.1", 0), address);
            assertDirectoryEmpty(pending);
            assertFalse(Files.exists(shaderpack.resolve("shaders"), NOFOLLOW_LINKS));
            listened.set(true);
            return listener;
        });

        // Then
        assertTrue(listened.get());
        bootstrap.close();
        bootstrap.close();
        assertEquals(1, listener.stopCount);
        assertEquals(1, listener.awaitCount);
        assertEquals(1, runtime.closeCount);
        assertEquals(List.of("listener-stop", "close", "listener-await"), runtime.events);
        assertTrue(Files.isRegularFile(sentinel));
    }

    @Test
    void startupCreatesMissingOwnedRootsBeforeListening() throws Exception {
        Path pending = temp.resolve("pending-new");
        Path artifacts = temp.resolve("artifacts-new");
        Path shaderpack = temp.resolve("shaderpack-new");
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();

        VibrisBootstrap bootstrap = VibrisBootstrap.start(
            new VibrisBootstrap.Config(0, pending, artifacts, shaderpack), runtime,
            (address, service) -> new TestListener());

        assertTrue(Files.isDirectory(pending));
        assertTrue(Files.isDirectory(artifacts));
        assertTrue(Files.isDirectory(shaderpack));
        bootstrap.close();
    }

    @Test
    void startupFailsBeforeListenWhenActivePathIsOrdinaryDirectory() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending-fail"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-fail"));
        Path active = Files.createDirectory(shaderpack.resolve("shaders"));
        Path sentinel = Files.writeString(active.resolve("sentinel.txt"), "user");
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        AtomicBoolean listened = new AtomicBoolean();
        VibrisBootstrap.Config config = new VibrisBootstrap.Config(
            0, pending, temp.resolve("artifacts-fail"), shaderpack);

        // When
        assertThrows(VibrisBootstrap.Failure.class, () -> VibrisBootstrap.start(config, runtime, (address, service) -> {
            listened.set(true);
            return new TestListener();
        }));

        // Then
        assertFalse(listened.get());
        assertTrue(Files.isRegularFile(sentinel));
        assertEquals(1, runtime.closeCount);
    }

    @Test
    void closeDetachesLinkThenClearsPendingRoot() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending-close"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-close"));
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        VibrisBootstrap.Config config = new VibrisBootstrap.Config(
            0, pending, temp.resolve("artifacts-close"), shaderpack);
        VibrisBootstrap bootstrap = VibrisBootstrap.start(config, runtime, (address, service) -> new TestListener());
        Path source = Files.createDirectory(pending.resolve(java.util.UUID.randomUUID().toString()));
        Files.writeString(source.resolve("main.glsl"), "active");
        Files.createSymbolicLink(shaderpack.resolve("shaders"), source);

        // When
        bootstrap.close();

        // Then
        assertFalse(Files.exists(shaderpack.resolve("shaders"), NOFOLLOW_LINKS));
        assertDirectoryEmpty(pending);
    }

    @Test
    void closeStillAwaitsListenerAndClearsPendingWhenRuntimeCloseFails() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-close-fail"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-close-fail"));
        Files.createDirectory(pending.resolve("stale"));
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        runtime.closeFailure = new IllegalStateException("runtime close failed");
        TestListener listener = new TestListener();
        VibrisBootstrap bootstrap = VibrisBootstrap.start(new VibrisBootstrap.Config(
            0, pending, temp.resolve("artifacts-close-fail"), shaderpack), runtime, (address, service) -> listener);
        Files.createDirectory(pending.resolve("shutdown-stale"));

        VibrisBootstrap.Failure failure = assertThrows(VibrisBootstrap.Failure.class, bootstrap::close);

        assertEquals(ErrorCode.INTERNAL_ERROR, failure.code());
        assertEquals(1, listener.awaitCount);
        assertDirectoryEmpty(pending);
    }

    private static void assertDirectoryEmpty(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    private static GetStatusResponse status(BindableService service) {
        AtomicReference<GetStatusResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ((VibrisControlGrpc.VibrisControlImplBase) service).getStatus(
            GetStatusRequest.getDefaultInstance(),
            new StreamObserver<>() {
                @Override
                public void onNext(GetStatusResponse value) {
                    response.set(value);
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.set(throwable);
                }

                @Override
                public void onCompleted() {
                }
            }
        );
        assertTrue(failure.get() == null, () -> "GetStatus failed: " + failure.get());
        return response.get();
    }

    private static ValidateContextResponse validate(
        BindableService service,
        dev.vibris.protocol.v1.SceneContext context
    ) {
        AtomicReference<ValidateContextResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ((VibrisControlGrpc.VibrisControlImplBase) service).validateContext(
            ValidateContextRequest.newBuilder().setContext(context).build(),
            new StreamObserver<>() {
                @Override
                public void onNext(ValidateContextResponse value) {
                    response.set(value);
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.set(throwable);
                }

                @Override
                public void onCompleted() {
                }
            }
        );
        assertTrue(failure.get() == null, () -> "ValidateContext failed: " + failure.get());
        return response.get();
    }

    private static void writeServerConfig(
        Path game,
        Path pending,
        Path artifacts,
        Path shaderpack,
        int port
    ) throws IOException {
        Path config = game.resolve("config/vibris/server.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            {
              "schema_version": 1,
              "listen_address": "127.0.0.1:%d",
              "pending_shaders_root": "%s",
              "artifact_root": "%s",
              "artifact_quota_bytes": 3221225472,
              "shaderpack_root": "%s",
              "max_source_bytes": 536870912,
              "max_source_files": 100000,
              "max_global_queue": 32,
              "max_actions_per_job": 64
            }
            """.formatted(port, jsonPath(pending), jsonPath(artifacts), jsonPath(shaderpack)));
    }

    private static String jsonPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class TestListener implements VibrisBootstrap.Listener {
        private final List<String> events;
        private int stopCount;
        private int awaitCount;

        TestListener() {
            this(new java.util.ArrayList<>());
        }

        TestListener(List<String> events) {
            this.events = events;
        }

        @Override
        public int port() {
            return 0;
        }

        @Override
        public void stopAdmission() {
            events.add("listener-stop");
            stopCount++;
        }

        @Override
        public void awaitTermination() {
            events.add("listener-await");
            awaitCount++;
        }
    }
}
