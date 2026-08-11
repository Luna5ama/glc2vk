package dev.vibris.core;

import dev.vibris.protocol.v2.ArtifactFormat;
import dev.vibris.protocol.v2.ArtifactKind;
import dev.vibris.protocol.v2.ArtifactRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactV2LifecycleTest {
    private static final String WORKSPACE_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_WORKSPACE_ID = "22222222-2222-2222-2222-222222222222";
    private static final ArtifactManifest.FileSpec PAYLOAD = new ArtifactManifest.FileSpec(
        ArtifactKind.ARTIFACT_KIND_BUFFER,
        ArtifactFormat.ARTIFACT_FORMAT_BIN,
        ArtifactRole.ARTIFACT_ROLE_PRIMARY,
        "application/octet-stream"
    );

    @TempDir
    Path temp;

    @Test
    void commitsCanonicalV2ManifestWithGroupingHashesRolesExpiryAndTotals() throws Exception {
        MutableClock clock = new MutableClock(1_000);
        ArtifactManager manager = new ArtifactManager(temp.resolve("artifacts"), 32_768, Duration.ofHours(168), clock);

        ArtifactManager.CommittedJob committed = commit(manager, WORKSPACE_ID, "job-a", "request-a", "payload");
        var listed = manager.manifests(WORKSPACE_ID, "job-a", "request-a");

        assertEquals(1, listed.size());
        var manifest = listed.getFirst();
        assertEquals(2, ArtifactManifest.INSTANCE.schemaVersion(committed.manifest()));
        assertEquals("job-a", manifest.getDocument().getJobId());
        assertEquals("request-a", manifest.getDocument().getRequestId());
        assertEquals(1_000 + Duration.ofHours(168).toMillis(), manifest.getDocument().getExpiresAtUnixMs());
        assertEquals(Files.size(committed.manifest()) + Files.size(committed.artifacts().get("payload.bin")),
            manifest.getDocument().getTotalBytes());
        assertEquals(ArtifactRole.ARTIFACT_ROLE_PRIMARY, manifest.getFiles().getFirst().getRole());
        assertEquals(ArtifactManifest.INSTANCE.sha256(committed.manifest()), committed.manifestSha256());
        assertEquals(ArtifactManifest.INSTANCE.sha256(committed.artifacts().get("payload.bin")),
            committed.metadata().get("payload.bin").getSha256());
        assertTrue(committed.directory().getParent().getParent().endsWith(WORKSPACE_ID));
        assertTrue(manager.capacity(0).getUsedBytes() > 0);
        assertEquals(0, manager.capacity(0).getReservedBytes());
    }

    @Test
    void expiresAtStartupAndBeforeReservationWithoutReadingV1Data() throws Exception {
        Path root = temp.resolve("artifacts");
        MutableClock clock = new MutableClock(10_000);
        ArtifactManager writer = new ArtifactManager(root, 32_768, Duration.ofMillis(10), clock);
        ArtifactManager.CommittedJob committed = commit(writer, WORKSPACE_ID, "job", "request", "expired");
        Path v1 = root.resolve("legacy/work");
        Files.createDirectories(v1);
        Files.writeString(v1.resolve("manifest.json"), "{\"artifacts\":[]}");

        clock.advance(Duration.ofMillis(10));
        ArtifactManager recovered = new ArtifactManager(root, 32_768, Duration.ofMillis(10), clock);

        assertFalse(Files.exists(committed.directory()));
        assertTrue(Files.isRegularFile(v1.resolve("manifest.json")));
        assertTrue(recovered.manifests(WORKSPACE_ID, null, null).isEmpty());
    }

    @Test
    void reservationsWarnAtEightyPercentAndRejectBeforeWriting() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("capacity"), 10_000);
        assertFalse(manager.capacity(7_999).getWarning());
        assertTrue(manager.capacity(8_000).getWarning());
        assertFalse(manager.capacity(10_001).getFits());
        assertThrows(ArtifactManager.JobTooLargeException.class,
            () -> manager.beginJob(WORKSPACE_ID, "job", "too-large", "test", 10_001));

        try (ArtifactManager.JobTransaction reservation =
                 manager.beginJob(WORKSPACE_ID, "job", "reserved", "test", 8_000)) {
            assertEquals(8_000, manager.capacity(0).getReservedBytes());
            assertThrows(ArtifactManager.QuotaExceededException.class,
                () -> manager.beginJob(WORKSPACE_ID, "job", "blocked", "test", 2_001));
        }
        assertEquals(0, manager.capacity(0).getReservedBytes());
    }

    @Test
    void listDetailAndDeleteEnforceWorkspaceAndExpectedManifestHash() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("owned"), 32_768);
        ArtifactManager.CommittedJob committed = commit(manager, WORKSPACE_ID, "job", "request", "owned");

        assertThrows(ArtifactManager.OwnershipException.class,
            () -> manager.manifest(OTHER_WORKSPACE_ID, committed.manifestId()));
        assertThrows(ArtifactManager.OwnershipException.class,
            () -> manager.delete(OTHER_WORKSPACE_ID, committed.manifestId(), committed.manifestSha256()));
        assertThrows(ArtifactManager.DeletionRaceException.class,
            () -> manager.delete(WORKSPACE_ID, committed.manifestId(), "0".repeat(64)));
        assertTrue(Files.isDirectory(committed.directory()));

        manager.delete(WORKSPACE_ID, committed.manifestId(), committed.manifestSha256());
        assertFalse(Files.exists(committed.directory()));
        assertTrue(manager.manifests(WORKSPACE_ID, null, null).isEmpty());
    }

    @Test
    void rejectsTamperingAndUnsupportedV2Shape() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("tamper"), 32_768);
        ArtifactManager.CommittedJob committed = commit(manager, WORKSPACE_ID, "job", "request", "safe");
        Files.writeString(committed.manifest(), " ", java.nio.file.StandardOpenOption.APPEND);

        assertThrows(ArtifactManager.DeletionRaceException.class,
            () -> manager.delete(WORKSPACE_ID, committed.manifestId(), committed.manifestSha256()));

        Path malformed = temp.resolve("unsupported.json");
        Files.writeString(malformed, "{\"schema_version\":1}");
        assertThrows(ArtifactManifest.UnsupportedVersionException.class,
            () -> ArtifactManifest.INSTANCE.decode(malformed));
    }

    private static ArtifactManager.CommittedJob commit(
        ArtifactManager manager,
        String workspaceId,
        String jobId,
        String requestId,
        String value
    ) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try (ArtifactManager.JobTransaction transaction =
                 manager.beginJob(workspaceId, jobId, requestId, "test", bytes.length)) {
            try (OutputStream output = transaction.open("payload.bin")) {
                output.write(bytes);
            }
            return transaction.commit(Map.of("payload.bin", PAYLOAD));
        }
    }

    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(Duration duration) {
            millis = Math.addExact(millis, duration.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
