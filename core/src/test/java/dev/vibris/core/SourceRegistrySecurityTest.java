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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceRegistrySecurityTest {
    @TempDir
    Path temp;

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
}