package dev.vibris.core;

import dev.vibris.protocol.v1.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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