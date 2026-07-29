package dev.vibris.core;

import dev.vibris.protocol.v1.JobCompleted;
import dev.vibris.protocol.v1.JobResult;
import dev.vibris.protocol.v1.ServerMessage;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactTerminalDeliveryTest {
    @TempDir
    Path temp;

    @Test
    void observerSeesCompletedJobOnlyAfterAtomicFinalize() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("delivered"), 1_000);
        ArtifactManager.CommittedJob committed = commit(manager, "request", 600);
        AtomicBoolean observed = new AtomicBoolean();
        ControlSession session = new ControlSession(new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage message) {
                assertTrue(message.hasJobCompleted());
                Path manifest = Path.of(message.getJobCompleted().getResult().getManifestPath());
                assertTrue(Files.isDirectory(committed.directory()));
                assertTrue(Files.isRegularFile(manifest));
                assertFalse(hasTemporaryDirectory(manager.root()));
                observed.set(true);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onCompleted() {
            }
        });

        new TerminalDelivery(manager).send(session, completedMessage(committed, "request"), "workspace", "request");

        assertTrue(observed.get());
        try (ArtifactManager.JobTransaction ignored = manager.beginJob("workspace", "next", 400)) {
            assertFalse(Files.exists(committed.directory()), "successful delivery makes the completed job evictable");
        }
    }

    @Test
    void failedSendProtectsJobOnlyUntilTerminalTtl() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T00:00:00Z"));
        ArtifactManager manager = new ArtifactManager(temp.resolve("failed-send"), 1_000, clock);
        ArtifactManager.CommittedJob committed = commit(manager, "request", 600);
        ControlSession session = new ControlSession(new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage message) {
                throw new IllegalStateException("observer disconnected");
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onCompleted() {
            }
        });

        new TerminalDelivery(manager).send(session, completedMessage(committed, "request"), "workspace", "request");

        assertFalse(session.connected());
        assertThrows(ArtifactManager.QuotaExceededException.class,
            () -> manager.beginJob("workspace", "blocked", 400));
        clock.advance(ArtifactManager.UNREPORTED_TTL);
        try (ArtifactManager.JobTransaction ignored = manager.beginJob("workspace", "after-ttl", 400)) {
            assertFalse(Files.exists(committed.directory()));
        }
    }

    private static ArtifactManager.CommittedJob commit(ArtifactManager manager, String requestId, int bytes)
        throws Exception {
        try (ArtifactManager.JobTransaction job = manager.beginJob("workspace", requestId, bytes)) {
            try (OutputStream output = job.open("payload.bin")) {
                output.write(new byte[bytes]);
            }
            return job.commit();
        }
    }

    private static ServerMessage completedMessage(ArtifactManager.CommittedJob committed, String requestId) {
        JobResult result = JobResult.newBuilder().setManifestPath(committed.manifest().toString()).build();
        return TerminalResult.completed(JobCompleted.newBuilder()
            .setRequestId(requestId).setResult(result).build()).message("message", requestId, "workspace");
    }

    private static boolean hasTemporaryDirectory(Path root) {
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}