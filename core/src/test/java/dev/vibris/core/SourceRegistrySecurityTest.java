package dev.vibris.core;

import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.PreparedSourceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceRegistrySecurityTest {
    @TempDir
    Path temp;

    @Test
    void sourceFreeJobsAcceptAnEmptySourceSet() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-source-free"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());

        assertTrue(registry.validate(List.of(), 0).isEmpty());
    }

    @Test
    void multipleSourcesAreRejectedAtValidationBoundary() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-multiple"));
        PreparedSourceRef first = source(pending);
        PreparedSourceRef second = source(pending);
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());

        SourceRegistry.Failure failure = assertThrows(
            SourceRegistry.Failure.class, () -> registry.validate(List.of(first, second)));

        assertEquals(ErrorCode.SOURCE_ACTIVATION_FAILED, failure.code);
    }

    @Test
    void reparsePendingRootIsRejectedBeforeTraversal() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        String uuid = UUID.randomUUID().toString();
        Path source = Files.createDirectory(outside.resolve(uuid));
        byte[] content = "outside".getBytes(StandardCharsets.UTF_8);
        Files.write(source.resolve("main.glsl"), content);
        Path pending = Files.createSymbolicLink(temp.resolve("pending"), outside);
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        PreparedSourceRef reference = PreparedSourceRef.newBuilder()
            .setUuid(uuid)
            .setFileCount(1)
            .setTotalBytes(content.length)
            .build();

        SourceRegistry.Failure failure = assertThrows(
            SourceRegistry.Failure.class, () -> registry.validate(List.of(reference)));
        assertEquals(ErrorCode.SOURCE_CONTAINS_REPARSE_POINT, failure.code);
    }

    @Test
    void reservationRechecksExclusiveUuidOwnership() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-ordinary"));
        String uuid = UUID.randomUUID().toString();
        Path source = Files.createDirectory(pending.resolve(uuid));
        byte[] content = "ordinary".getBytes(StandardCharsets.UTF_8);
        Files.write(source.resolve("main.glsl"), content);
        PreparedSourceRef reference = PreparedSourceRef.newBuilder()
            .setUuid(uuid)
            .setFileCount(1)
            .setTotalBytes(content.length)
            .build();
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        List<SourceRegistry.Candidate> first = registry.validate(List.of(reference));
        List<SourceRegistry.Candidate> second = registry.validate(List.of(reference));
        List<SourceRegistry.Lease> reservation = registry.reserve(first);

        SourceRegistry.Failure failure = assertThrows(
            SourceRegistry.Failure.class, () -> registry.reserve(second));
        assertEquals(ErrorCode.INVALID_SOURCE_UUID, failure.code);
        registry.reject(reservation);
    }

    @Test
    void reservationHashesTheExactTransferredContent() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-content-mutation"));
        PreparedSourceRef reference = source(pending);
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        List<SourceRegistry.Candidate> candidates = registry.validate(List.of(reference));
        Files.writeString(pending.resolve(reference.getUuid()).resolve("main.glsl"), "x".repeat(36));

        SourceRegistry.Lease lease = registry.reserve(candidates).getFirst();

        assertEquals(64, lease.snapshotSha256().length());
    }

    @Test
    void cleanupDoesNotFollowPendingRootReplacedAfterReservation() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-reserved"));
        String uuid = UUID.randomUUID().toString();
        Path source = Files.createDirectory(pending.resolve(uuid));
        byte[] content = "reserved".getBytes(StandardCharsets.UTF_8);
        Files.write(source.resolve("main.glsl"), content);
        PreparedSourceRef reference = PreparedSourceRef.newBuilder()
            .setUuid(uuid)
            .setFileCount(1)
            .setTotalBytes(content.length)
            .build();
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        List<SourceRegistry.Lease> reservation = registry.reserve(registry.validate(List.of(reference)));
        registry.accept(reservation);

        Files.move(pending, temp.resolve("original-pending"));
        Path outside = Files.createDirectory(temp.resolve("outside-reserved"));
        Path outsideSource = Files.createDirectory(outside.resolve(uuid));
        Path sentinel = Files.writeString(outsideSource.resolve("sentinel.txt"), "outside");
        Files.createSymbolicLink(pending, outside);

        registry.cleanup(reservation);

        assertTrue(Files.exists(sentinel), "cleanup followed a replaced pending-root ancestor");
        assertEquals(0, registry.size(), "unsafe cleanup must not wedge source capacity");
    }

    @Test
    void deletingActiveSourceDoesNotPoisonNextActivation() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-active-delete"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease first = registry.reserve(registry.validate(List.of(source(pending)))).getFirst();
        registry.accept(List.of(first));
        registry.commitActivation(registry.beginActivation(first));

        Files.delete(first.directory().resolve("main.glsl"));
        Files.delete(first.directory());
        registry.release(List.of(first), true);

        assertEquals("", registry.activeUuid());
        assertEquals(0, registry.size());

        SourceRegistry.Lease second = registry.reserve(registry.validate(List.of(source(pending)))).getFirst();
        registry.accept(List.of(second));
        registry.commitActivation(registry.beginActivation(second));

        assertEquals(second.uuid(), registry.activeUuid());
        registry.release(List.of(second), false);
        assertFalse(Files.exists(second.directory()));
    }

    @Test
    void activeContentMutationInvalidatesSnapshotReceipt() throws Exception {
        Path pending = Files.createDirectory(temp.resolve("pending-active-mutation"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease lease = registry.reserve(registry.validate(List.of(source(pending)))).getFirst();
        registry.accept(List.of(lease));
        registry.commitActivation(registry.beginActivation(lease));
        Files.writeString(lease.directory().resolve("main.glsl"), "x".repeat(36));

        SourceRegistry.Failure failure = assertThrows(SourceRegistry.Failure.class, registry::requireActiveOwned);

        assertEquals(ErrorCode.SOURCE_ACTIVATION_FAILED, failure.code);
    }

    private static PreparedSourceRef source(Path pending) throws Exception {
        String uuid = UUID.randomUUID().toString();
        Path source = Files.createDirectory(pending.resolve(uuid));
        Path file = Files.writeString(source.resolve("main.glsl"), uuid);
        return PreparedSourceRef.newBuilder()
            .setUuid(uuid)
            .setFileCount(1)
            .setTotalBytes(Files.size(file))
            .build();
    }
}