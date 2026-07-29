package dev.vibris.core;

import dev.vibris.api.ReloadResult;
import dev.vibris.protocol.v1.ArtifactFormat;
import dev.vibris.protocol.v1.ArtifactKind;
import dev.vibris.protocol.v1.ArtifactMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class ArtifactManager implements ShaderLogSink {
    static final long DEFAULT_QUOTA_BYTES = 3L * 1024 * 1024 * 1024;

    private final Path root;

    ArtifactManager(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            OwnedPathIdentity.createDirectoriesSafely(this.root);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the artifact root.", exception);
        }
    }

    Path root() {
        return root;
    }

    long quotaBytes() {
        return DEFAULT_QUOTA_BYTES;
    }

    @Override
    public ArtifactMetadata writeShaderLog(
        String workspaceId,
        String requestId,
        List<ReloadResult.Diagnostic> diagnostics
    ) throws IOException {
        OwnedPathIdentity.createDirectoriesSafely(root);
        Path workspace = root.resolve(segment("workspace", workspaceId));
        Path job = workspace.resolve(segment("request", requestId));
        OwnedPathIdentity.createDirectoriesSafely(workspace);
        OwnedPathIdentity.createDirectoriesSafely(job);
        OwnedPathIdentity jobIdentity = OwnedPathIdentity.captureDirectory(job);
        Path temporary = Files.createTempFile(job, ".shader-", ".tmp");
        Path log = job.resolve("shader.log");
        try {
            Files.writeString(temporary, logText(diagnostics), StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (!jobIdentity.matchesDirectory(job)) throw new IOException("Artifact job directory changed.");
            if (Files.exists(log, NOFOLLOW_LINKS) && !Files.isRegularFile(log, NOFOLLOW_LINKS)) {
                throw new IOException("Shader log path is unsafe.");
            }
            move(temporary, log);
        } finally {
            Files.deleteIfExists(temporary);
        }
        long byteSize = Files.size(log);
        return ArtifactMetadata.newBuilder()
            .setArtifactId(segment("artifact", workspaceId + '\0' + requestId + "\0shader.log"))
            .setFileName("shader.log")
            .setKind(ArtifactKind.ARTIFACT_KIND_SHADER_COMPILE_LOG)
            .setFormat(ArtifactFormat.ARTIFACT_FORMAT_TEXT)
            .setMediaType("text/plain; charset=utf-8")
            .setByteSize(byteSize)
            .setPath(log.toString())
            .build();
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String logText(List<ReloadResult.Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) return "Shader reload failed.";
        StringBuilder output = new StringBuilder();
        for (ReloadResult.Diagnostic diagnostic : diagnostics) {
            output.append('[').append(diagnostic.severity()).append("] ")
                .append(diagnostic.source());
            if (diagnostic.line() > 0) output.append(':').append(diagnostic.line());
            output.append(": ").append(diagnostic.message()).append(System.lineSeparator());
        }
        return output.toString();
    }

    private static String segment(String kind, String value) {
        return UUID.nameUUIDFromBytes((kind + '\0' + value).getBytes(StandardCharsets.UTF_8)).toString();
    }
}