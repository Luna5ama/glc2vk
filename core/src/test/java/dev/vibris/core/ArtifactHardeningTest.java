package dev.vibris.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactHardeningTest {
    @TempDir
    Path temp;

    @Test
    void rootIdentityReplacementStopsNewJobs() throws Exception {
        Path root = temp.resolve("artifacts");
        ArtifactManager manager = new ArtifactManager(root, 4_096);
        Path displaced = temp.resolve("displaced-root");
        Files.move(root, displaced);
        Files.createDirectory(root);

        assertThrows(IOException.class, () -> manager.beginJob("workspace", "request", 0));
        assertTrue(Files.isDirectory(root));
        assertTrue(Files.isDirectory(displaced));
    }

    @Test
    void workspaceIdentityReplacementStopsFinalizeAndCleanup() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("artifacts"), 4_096);
        ArtifactManager.JobTransaction job = manager.beginJob("workspace", "request", 7);
        try (OutputStream output = job.open("payload.bin")) {
            output.write(new byte[7]);
        }
        Path temporary = onlyTemporaryDirectory(manager.root());
        Path workspace = temporary.getParent();
        Path displaced = workspace.resolveSibling("displaced-workspace");
        Files.move(workspace, displaced);
        Files.createDirectory(workspace);

        assertThrows(IOException.class, job::commit);
        assertThrows(IOException.class, job::close);
        assertTrue(Files.isDirectory(workspace));
        assertTrue(Files.isDirectory(displaced.resolve(temporary.getFileName())));
    }

    @Test
    void recoveryDeletesMalformedJobWithoutLosingValidJob() throws Exception {
        Path root = temp.resolve("recovery");
        ArtifactManager writer = new ArtifactManager(root, 4_096);
        ArtifactManager.CommittedJob valid = commit(writer, "valid", 7);
        Path malformed = Files.createDirectory(valid.directory().resolveSibling("malformed"));
        Files.writeString(malformed.resolve("manifest.json"), "{}");
        Files.createDirectory(malformed.resolve("nested"));

        ArtifactManager recovered = new ArtifactManager(root, 4_096);

        assertFalse(Files.exists(malformed));
        assertTrue(Files.isRegularFile(valid.artifacts().get("payload.bin")));
        assertEquals(valid.byteSize(), recovered.usedBytes());
    }

    @Test
    void recoveryStopsAtConfiguredJobBound() throws Exception {
        Path root = Files.createDirectories(temp.resolve("bounded"));
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        for (int index = 0; index < 2; index++) {
            Path job = Files.createDirectory(workspace.resolve("job-" + index));
            Files.writeString(job.resolve("manifest.json"), "{}");
        }

        assertThrows(IOException.class,
            () -> ArtifactFiles.recover(root, OwnedPathIdentity.captureDirectory(root), 1));
    }

    @Test
    void unreportedProtectionExpiresWithTerminalResultTtl() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-28T00:00:00Z"));
        ArtifactManager manager = new ArtifactManager(temp.resolve("ttl"), 1_000, clock);
        ArtifactManager.CommittedJob protectedJob = commit(manager, "protected", 600);

        assertThrows(ArtifactManager.QuotaExceededException.class,
            () -> manager.beginJob("workspace", "blocked", 400));

        clock.advance(ArtifactManager.UNREPORTED_TTL);
        try (ArtifactManager.JobTransaction ignored = manager.beginJob("workspace", "eligible", 400)) {
            assertFalse(Files.exists(protectedJob.directory()));
        }
    }

    @Test
    void constructorRejectsRootThatCannotCreateArtifacts() throws Exception {
        Path root = Files.createDirectory(temp.resolve("unwritable"));
        AclFileAttributeView view = Files.getFileAttributeView(root, AclFileAttributeView.class);
        List<AclEntry> original = view.getAcl();
        AclEntry denyCreate = AclEntry.newBuilder()
            .setType(AclEntryType.DENY)
            .setPrincipal(view.getOwner())
            .setPermissions(EnumSet.of(AclEntryPermission.ADD_FILE, AclEntryPermission.ADD_SUBDIRECTORY))
            .setFlags(EnumSet.noneOf(AclEntryFlag.class))
            .build();
        try {
            view.setAcl(java.util.stream.Stream.concat(
                java.util.stream.Stream.of(denyCreate), original.stream()).toList());
            assertThrows(IllegalStateException.class, () -> new ArtifactManager(root, 4_096));
        } finally {
            view.setAcl(original);
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

    private static Path onlyTemporaryDirectory(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".tmp"))
                .findFirst().orElseThrow();
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