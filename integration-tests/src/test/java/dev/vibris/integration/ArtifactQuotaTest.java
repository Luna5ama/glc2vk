package dev.vibris.integration;

import dev.vibris.core.ArtifactManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.SPARSE;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactQuotaTest {
    private static final String PROBE_ROOT = "VIBRIS_ARTIFACT_PROBE_ROOT";

    @TempDir
    Path temp;

    @Test
    void realSparseDirectoriesWholeJobLru() throws Exception {
        String probeRoot = System.getenv(PROBE_ROOT);
        if (probeRoot != null && !probeRoot.isBlank()) {
            runDefaultQuotaProbe(Path.of(probeRoot).toAbsolutePath().normalize());
            return;
        }

        Path root = temp.resolve("artifacts");
        ArtifactManager writer = new ArtifactManager(root, 1_048_576);
        ArtifactManager.CommittedJob oldest = commit(writer, "oldest", 16);
        ArtifactManager.CommittedJob newest = commit(writer, "newest", 16);
        writer.markReported("workspace", "oldest");
        writer.markReported("workspace", "newest");
        addSparsePadding(oldest.directory(), 65_536);
        addSparsePadding(newest.directory(), 32_768);
        Files.setLastModifiedTime(oldest.manifest(), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(newest.manifest(), FileTime.fromMillis(2_000));
        long newestBytes = directorySize(newest.directory());
        Path stale = Files.createDirectories(root.resolve("stale-workspace").resolve("orphan.tmp"));
        Files.writeString(stale.resolve("partial.bin"), "partial");

        ArtifactManager recovered = new ArtifactManager(root, newestBytes);

        assertFalse(Files.exists(stale));
        assertFalse(Files.exists(oldest.directory()));
        assertTrue(Files.isDirectory(newest.directory()));
        assertTrue(Files.isRegularFile(newest.directory().resolve("payload.bin")));
        assertEquals(newestBytes, recovered.usedBytes());
    }

    private static void runDefaultQuotaProbe(Path root) throws Exception {
        long quota = ArtifactManager.DEFAULT_QUOTA_BYTES;
        ArtifactManager writer = new ArtifactManager(root);
        assertEquals(quota, writer.quotaBytes());
        ArtifactManager.CommittedJob oldest = commit(writer, "oldest", 1);
        ArtifactManager.CommittedJob newest = commit(writer, "newest", 1);
        writer.markReported("workspace", "oldest");
        writer.markReported("workspace", "newest");
        replaceWithSparseFile(oldest.artifacts().get("payload.bin"), 2L * 1024 * 1024 * 1024);
        replaceWithSparseFile(newest.artifacts().get("payload.bin"), 3L * 1024 * 1024 * 1024 / 2);
        Files.setLastModifiedTime(oldest.manifest(), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(newest.manifest(), FileTime.fromMillis(2_000));
        Path stale = Files.createDirectories(root.resolve("stale-workspace").resolve("orphan.tmp"));
        Files.writeString(stale.resolve("partial.bin"), "partial");

        ArtifactManager manager = new ArtifactManager(root);

        assertFalse(Files.exists(stale));
        assertFalse(Files.exists(oldest.directory()));
        assertTrue(Files.isDirectory(newest.directory()));
        ArtifactManager.CommittedJob unreported = commit(manager, "unreported", 16);
        try (ArtifactManager.JobTransaction active = manager.beginJob("workspace", "active", 2L * 1024 * 1024 * 1024)) {
            Path activeTemporary = onlyTemporaryDirectory(root);
            assertFalse(Files.exists(newest.directory()));
            assertTrue(Files.isDirectory(unreported.directory()));
            assertThrows(ArtifactManager.QuotaExceededException.class,
                () -> manager.beginJob("workspace", "blocked-active", 2L * 1024 * 1024 * 1024));
            assertTrue(Files.isDirectory(activeTemporary));
        }
        assertThrows(ArtifactManager.JobTooLargeException.class,
            () -> manager.beginJob("workspace", "oversized", quota + 1));

        ArtifactManager.CommittedJob ordered;
        try (ArtifactManager.JobTransaction finalizing = manager.beginJob(
            "workspace", "ordered", 1024L * 1024 * 1024)) {
            OutputStream output = finalizing.open("payload.bin");
            output.write(new byte[16]);
            assertThrows(java.io.IOException.class, finalizing::commit);
            Path temporary = onlyTemporaryDirectory(root);
            assertFalse(Files.exists(temporary.resolve("manifest.json")));
            output.close();
            AtomicReference<ArtifactManager.CommittedJob> result = new AtomicReference<>();
            AtomicReference<Throwable> commitFailure = new AtomicReference<>();
            Thread commitThread = new Thread(() -> {
                try {
                    result.set(finalizing.commit());
                } catch (Throwable throwable) {
                    commitFailure.set(throwable);
                }
            }, "artifact-finalize-probe");
            commitThread.setDaemon(true);
            synchronized (manager) {
                commitThread.start();
                awaitBlocked(commitThread);
                assertThrows(ArtifactManager.QuotaExceededException.class,
                    () -> manager.beginJob("workspace", "blocked-finalizing", quota));
                assertTrue(Files.isDirectory(temporary));
            }
            commitThread.join(5_000);
            assertFalse(commitThread.isAlive());
            if (commitFailure.get() != null) throw new AssertionError(commitFailure.get());
            ordered = result.get();
            assertFalse(Files.exists(temporary));
        }
        assertTrue(Files.isRegularFile(ordered.manifest()));
        assertTrue(Files.isRegularFile(ordered.artifacts().get("payload.bin")));
        long completeBytes = completeJobBytes(root);
        assertEquals(completeBytes, manager.usedBytes());
        assertTrue(completeBytes <= quota);
    }

    @Test
    void activeAndUnreportedJobsAreProtectedFromQuotaTrim() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("protected"), 2_048);
        ArtifactManager.CommittedJob eligible = commit(manager, "eligible", 700);
        manager.markReported("workspace", "eligible");
        ArtifactManager.CommittedJob unreported = commit(manager, "unreported", 700);

        try (ArtifactManager.JobTransaction active = manager.beginJob("workspace", "active", 500)) {
            assertFalse(Files.exists(eligible.directory()));
            assertTrue(Files.isDirectory(unreported.directory()));
            assertTrue(hasTemporaryDirectory(manager.root()));
        }
    }

    @Test
    void protectedJobPressureUsesTypedQuotaFailure() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("pressure"), 1_200);
        commit(manager, "unreported", 700);

        assertThrows(ArtifactManager.QuotaExceededException.class,
            () -> manager.beginJob("workspace", "blocked", 500));
        assertFalse(hasTemporaryDirectory(manager.root()));
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

    private static void addSparsePadding(Path directory, long size) throws Exception {
        try (FileChannel channel = FileChannel.open(directory.resolve("padding.bin"), CREATE_NEW, WRITE, SPARSE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
    }

    private static void replaceWithSparseFile(Path file, long size) throws Exception {
        Files.delete(file);
        try (FileChannel channel = FileChannel.open(file, CREATE_NEW, WRITE, SPARSE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertEquals(size, Files.size(file));
    }

    private static long completeJobBytes(Path root) throws Exception {
        try (var paths = Files.walk(root, 2)) {
            return paths.filter(path -> Files.isDirectory(path) && Files.isRegularFile(path.resolve("manifest.json")))
                .mapToLong(path -> {
                    try {
                        return directorySize(path);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }).sum();
        }
    }

    private static Path onlyTemporaryDirectory(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".tmp"))
                .findFirst().orElseThrow();
        }
    }

    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.BLOCKED, thread.getState());
    }

    private static long directorySize(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }).sum();
        }
    }

    private static boolean hasTemporaryDirectory(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        }
    }
}