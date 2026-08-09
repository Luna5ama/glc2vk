package dev.vibris.core.artifact;

import dev.vibris.api.ReloadResult;
import dev.vibris.core.ArtifactManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactManagerTest {
    private static final String WORKSPACE_ID = "11111111-1111-4111-8111-111111111111";
    @TempDir
    Path temp;

    @Test
    void singleJobTempFlushRename() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("artifacts"), 4_096);

        ArtifactManager.CommittedJob committed;
        try (ArtifactManager.JobTransaction job = manager.beginJob(WORKSPACE_ID, "request", 7)) {
            try (OutputStream output = job.open("payload.bin")) {
                output.write("payload".getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(hasTemporaryDirectory(manager.root()));

            committed = job.commit();
        }

        assertFalse(hasTemporaryDirectory(manager.root()));
        assertTrue(Files.isDirectory(committed.directory()));
        assertEquals(WORKSPACE_ID, committed.directory().getParent().getFileName().toString());
        assertEquals("payload", Files.readString(committed.artifacts().get("payload.bin")));
        assertTrue(Files.isRegularFile(committed.manifest()));
        assertTrue(Files.readString(committed.manifest()).contains("\"payload.bin\""));
        assertEquals(7, committed.fileByteSizes().get("payload.bin"));
        assertEquals(Files.size(committed.manifest()), committed.fileByteSizes().get("manifest.json"));
        assertEquals(committed.byteSize(), manager.usedBytes());
    }

    @Test
    void artifactNamesCollideCaseInsensitivelyAndUseCreateNew() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("names"), 4_096);

        try (ArtifactManager.JobTransaction job = manager.beginJob(WORKSPACE_ID, "request", 0)) {
            try (OutputStream ignored = job.open("Payload.bin")) {
                assertThrows(java.io.IOException.class, () -> job.open("payload.BIN"));
                assertThrows(java.io.IOException.class, () -> job.open("MANIFEST.JSON"));
            }
        }

        try (ArtifactManager.JobTransaction job = manager.beginJob(WORKSPACE_ID, "foreign", 0)) {
            Path temporary = onlyTemporaryDirectory(manager.root());
            Files.writeString(temporary.resolve("payload.bin"), "foreign");
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> job.open("payload.bin"));
        }
    }

    @Test
    void commitRejectsMissingExpectedArtifactBeforeManifestAndRename() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("expected"), 4_096);

        try (ArtifactManager.JobTransaction job = manager.beginJob(WORKSPACE_ID, "request", 7)) {
            try (OutputStream output = job.open("shader.log")) {
                output.write("success".getBytes(StandardCharsets.UTF_8));
            }
            Path temporary = onlyTemporaryDirectory(manager.root());

            assertThrows(java.io.IOException.class,
                () -> job.commit(Set.of("shader.log", "payload.bin")));
            assertFalse(Files.exists(temporary.resolve("manifest.json")));
            assertFalse(Files.exists(temporary.resolveSibling(
                temporary.getFileName().toString().replace(".tmp", ""))));
        }
    }

    @Test
    void commitRejectsReplacedOrResizedArtifactFiles() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("tampered"), 4_096);

        try (ArtifactManager.JobTransaction job = manager.beginJob(WORKSPACE_ID, "replaced", 7)) {
            try (OutputStream output = job.open("payload.bin")) {
                output.write("payload".getBytes(StandardCharsets.UTF_8));
            }
            Path artifact = onlyTemporaryDirectory(manager.root()).resolve("payload.bin");
            Files.move(artifact, artifact.resolveSibling("original.bin"));
            Files.writeString(artifact, "payload");
            assertThrows(java.io.IOException.class, job::commit);
        }

        try (ArtifactManager.JobTransaction job = manager.beginJob(WORKSPACE_ID, "resized", 8)) {
            try (OutputStream output = job.open("payload.bin")) {
                output.write("payload".getBytes(StandardCharsets.UTF_8));
            }
            Path artifact = onlyTemporaryDirectory(manager.root()).resolve("payload.bin");
            Files.writeString(artifact, "!", java.nio.file.StandardOpenOption.APPEND);
            assertThrows(java.io.IOException.class, job::commit);
        }
    }

    @Test
    void manifestCreationDoesNotReplaceAnExistingEntry() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("manifest"), 4_096);

        try (ArtifactManager.JobTransaction job = manager.beginJob(WORKSPACE_ID, "request", 7)) {
            try (OutputStream output = job.open("payload.bin")) {
                output.write("payload".getBytes(StandardCharsets.UTF_8));
            }
            Path temporary = onlyTemporaryDirectory(manager.root());
            Files.writeString(temporary.resolve("manifest.json"), "foreign");

            assertThrows(java.nio.file.FileAlreadyExistsException.class, job::commit);
            assertFalse(Files.exists(temporary.resolveSibling(
                temporary.getFileName().toString().replace(".tmp", ""))));
        }
    }

    @Test
    void oversizedEstimateAndStreamOverrunUseTypedFailures() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolve("artifacts"), 256);

        assertThrows(ArtifactManager.JobTooLargeException.class,
            () -> manager.beginJob(WORKSPACE_ID, "estimated", 257));

        try (ArtifactManager.JobTransaction job = manager.beginJob(WORKSPACE_ID, "written", 0);
             OutputStream output = job.open("payload.bin")) {
            assertThrows(ArtifactManager.JobTooLargeException.class, () -> output.write(new byte[257]));
        }
    }

    @Test
    void shaderLogCompatibilityUsesCommittedArtifact() throws Exception {
        ArtifactManager manager = new ArtifactManager(temp.resolveSibling("artifacts"));

        var artifact = manager.writeShaderLog(WORKSPACE_ID, "request", List.of(
            new ReloadResult.Diagnostic(ReloadResult.Severity.ERROR, "composite.fsh", 17, "compile failed")));

        assertEquals("shader.log", artifact.getFileName());
        assertTrue(Files.readString(Path.of(artifact.getPath())).contains("compile failed"));
        assertTrue(Files.isRegularFile(Path.of(artifact.getPath()).resolveSibling("manifest.json")));
    }

    private static boolean hasTemporaryDirectory(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        }
    }

    private static Path onlyTemporaryDirectory(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".tmp"))
                .findFirst().orElseThrow();
        }
    }
}
