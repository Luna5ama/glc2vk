package dev.vibris.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class FixedShaderLinkTest {
    @TempDir
    Path temp;

    @Test
    void prepareUnlinksStaleSymlinkWithoutFollowingTarget() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("sentinel.txt"), "outside");
        Files.createSymbolicLink(shaderpack.resolve("shaders"), outside);
        FixedShaderLink link = new FixedShaderLink(pending, shaderpack);

        // When
        link.prepare();

        // Then
        assertFalse(Files.exists(shaderpack.resolve("shaders"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(sentinel));
    }

    @Test
    void switchCreatesTrueDirectorySymlinkAndPreservesOldLinkOnOwnershipFailure() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending-switch"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-switch"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease sourceA = source(registry, pending, "A");
        SourceRegistry.Lease sourceB = source(registry, pending, "B");
        FixedShaderLink link = new FixedShaderLink(pending, shaderpack);
        link.prepare();
        link.switchTo(sourceA, () -> { });

        // When
        assertThrows(ShaderLink.Failure.class, () -> link.switchTo(sourceB, () -> {
            throw new ShaderLink.Failure("injected ownership failure", true);
        }));

        // Then
        Path active = shaderpack.resolve("shaders");
        assertTrue(Files.isSymbolicLink(active));
        assertEquals(sourceA.directory(), Files.readSymbolicLink(active));
        assertFalse(Files.exists(shaderpack.resolve("shaders.next." + sourceB.uuid()),
            java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void guardedReplacementHandlesUnavailableAtomicMove() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending-fallback"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-fallback"));
        SourceRegistry registry = new SourceRegistry(pending, new CoreProbe());
        SourceRegistry.Lease sourceA = source(registry, pending, "A");
        SourceRegistry.Lease sourceB = source(registry, pending, "B");
        FixedShaderLink link = new FixedShaderLink(pending, shaderpack, (source, target) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
        });
        link.prepare();
        link.switchTo(sourceA, () -> { });

        // When
        link.switchTo(sourceB, () -> { });

        // Then
        assertEquals(sourceB.directory(), link.currentTarget());
        assertTrue(Files.isDirectory(sourceB.directory()));
    }

    @Test
    void ordinaryDirectoryAtActivePathFailsClosed() throws Exception {
        // Given
        Path pending = Files.createDirectory(temp.resolve("pending-directory"));
        Path shaderpack = Files.createDirectory(temp.resolve("shaderpack-directory"));
        Path active = Files.createDirectory(shaderpack.resolve("shaders"));
        Path sentinel = Files.writeString(active.resolve("sentinel.txt"), "owned by user");
        FixedShaderLink link = new FixedShaderLink(pending, shaderpack);

        // When
        ShaderLink.Failure failure = assertThrows(ShaderLink.Failure.class, link::prepare);

        // Then
        assertTrue(failure.stable());
        assertTrue(Files.isRegularFile(sentinel));
    }

    private static SourceRegistry.Lease source(SourceRegistry registry, Path pending, String marker) throws Exception {
        String uuid = UUID.randomUUID().toString();
        byte[] content = marker.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path directory = Files.createDirectory(pending.resolve(uuid));
        Files.write(directory.resolve("main.glsl"), content);
        var reference = dev.vibris.protocol.v1.PreparedSourceRef.newBuilder()
            .setUuid(uuid)
            .setFileCount(1)
            .setTotalBytes(content.length)
            .build();
        List<SourceRegistry.Lease> leases = registry.reserve(registry.validate(List.of(reference)));
        registry.accept(leases);
        return leases.getFirst();
    }
}