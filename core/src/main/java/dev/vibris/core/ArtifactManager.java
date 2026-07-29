package dev.vibris.core;

import dev.vibris.api.ReloadResult;
import dev.vibris.protocol.v1.ArtifactFormat;
import dev.vibris.protocol.v1.ArtifactKind;
import dev.vibris.protocol.v1.ArtifactMetadata;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

public final class ArtifactManager implements ShaderLogSink {
    public static final long DEFAULT_QUOTA_BYTES = 3L * 1024 * 1024 * 1024;
    static final Duration UNREPORTED_TTL = Duration.ofMinutes(10);
    private final Path root;
    private final long quotaBytes;
    private final Clock clock;
    private final OwnedPathIdentity rootIdentity;
    private final Map<Path, CompletedJob> completed = new HashMap<>();
    private final Map<ArtifactJobTransaction, Long> reservations = new HashMap<>();
    private final Map<Path, Long> unreported = new HashMap<>();
    private long usedBytes;
    private long reservedBytes;
    private long completionSequence;

    public ArtifactManager(Path root) { this(root, DEFAULT_QUOTA_BYTES, Clock.systemUTC()); }
    public ArtifactManager(Path root, long quotaBytes) { this(root, quotaBytes, Clock.systemUTC()); }
    ArtifactManager(Path root, long quotaBytes, Clock clock) {
        if (quotaBytes < 0) throw new IllegalArgumentException("quotaBytes must not be negative");
        this.root = root.toAbsolutePath().normalize();
        this.quotaBytes = quotaBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
        try {
            OwnedPathIdentity.createDirectoriesSafely(this.root);
            rootIdentity = OwnedPathIdentity.captureDirectory(this.root);
            for (ArtifactFiles.RecoveredJob job : ArtifactFiles.recover(this.root, rootIdentity)) {
                completed.put(job.directory(), new CompletedJob(
                    job.directory(), job.bytes(), job.completedAt(), completionSequence++, job.workspaceIdentity()));
                usedBytes = Math.addExact(usedBytes, job.bytes());
            }
            evictUntilWithinQuota(0);
        } catch (IOException | ArithmeticException exception) {
            throw new IllegalStateException("Could not initialize the artifact root.", exception);
        }
    }

    public Path root() { return root; }
    public long quotaBytes() { return quotaBytes; }
    public synchronized long usedBytes() { return usedBytes; }

    public synchronized JobTransaction beginJob(String workspaceId, String requestId, long estimatedBytes)
        throws IOException {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(requestId, "requestId");
        if (estimatedBytes < 0) throw new IllegalArgumentException("estimatedBytes must not be negative");
        if (estimatedBytes > quotaBytes) throw new JobTooLargeException(estimatedBytes, quotaBytes);

        verifyRootIdentity();
        Path workspace = root.resolve(segment("workspace", workspaceId));
        Path directory = workspace.resolve(segment("request", requestId));
        Path temporary = directory.resolveSibling(directory.getFileName() + ".tmp");
        OwnedPathIdentity.createDirectoriesSafely(workspace);
        verifyRootIdentity();
        OwnedPathIdentity workspaceIdentity = OwnedPathIdentity.captureDirectory(workspace);
        CompletedJob previous = completed.get(directory);
        expireUnreported();
        if (previous != null && !unreported.containsKey(directory)) {
            ArtifactFiles.deleteTree(directory, previous.workspaceIdentity);
            completed.remove(directory);
            usedBytes = Math.subtractExact(usedBytes, previous.bytes);
        }
        if (Files.exists(directory, NOFOLLOW_LINKS) || Files.exists(temporary, NOFOLLOW_LINKS)) {
            throw new IOException("Artifact job already exists.");
        }
        evictUntilWithinQuota(estimatedBytes);
        verifyStorageIdentity(workspace, workspaceIdentity);
        Files.createDirectory(temporary);
        JobTransaction transaction;
        try {
            verifyStorageIdentity(workspace, workspaceIdentity);
            transaction = new JobTransaction(temporary, directory, workspaceIdentity);
        } catch (IOException | RuntimeException exception) {
            ArtifactFiles.deleteTree(temporary, workspaceIdentity);
            throw exception;
        }
        reservations.put(transaction, estimatedBytes);
        reservedBytes = Math.addExact(reservedBytes, estimatedBytes);
        return transaction;
    }

    public synchronized void markReported(String workspaceId, String requestId) {
        Path workspace = root.resolve(segment("workspace", workspaceId));
        Path directory = workspace.resolve(segment("request", requestId));
        unreported.remove(directory);
    }

    @Override
    public ArtifactMetadata writeShaderLog(String workspaceId, String requestId,
        List<ReloadResult.Diagnostic> diagnostics) throws IOException {
        byte[] bytes = logText(diagnostics).getBytes(StandardCharsets.UTF_8);
        CommittedJob committed;
        try (JobTransaction transaction = beginJob(workspaceId, requestId, bytes.length)) {
            try (OutputStream output = transaction.open("shader.log")) {
                output.write(bytes);
            }
            committed = transaction.commit();
        }
        Path log = committed.artifacts().get("shader.log");
        return ArtifactMetadata.newBuilder()
            .setArtifactId(segment("artifact", workspaceId + '\0' + requestId + "\0shader.log"))
            .setFileName("shader.log")
            .setKind(ArtifactKind.ARTIFACT_KIND_SHADER_COMPILE_LOG)
            .setFormat(ArtifactFormat.ARTIFACT_FORMAT_TEXT)
            .setMediaType("text/plain; charset=utf-8")
            .setByteSize(bytes.length)
            .setPath(log.toString())
            .build();
    }

    synchronized void reserve(ArtifactJobTransaction transaction, long bytes) throws IOException {
        Long current = reservations.get(transaction);
        if (current == null) throw new IOException("Artifact job is no longer active.");
        if (bytes > quotaBytes) throw new JobTooLargeException(bytes, quotaBytes);
        if (bytes <= current) return;
        long additional = Math.subtractExact(bytes, current);
        evictUntilWithinQuota(additional);
        reservations.put(transaction, bytes);
        reservedBytes = Math.addExact(reservedBytes, additional);
    }

    synchronized void complete(ArtifactJobTransaction transaction, long bytes, long completedAt) {
        Long reservation = reservations.remove(transaction);
        if (reservation != null) reservedBytes = Math.subtractExact(reservedBytes, reservation);
        completed.put(transaction.directory(), new CompletedJob(
            transaction.directory(), bytes, completedAt, completionSequence++, transaction.workspaceIdentity()));
        unreported.put(transaction.directory(), protectionDeadline());
        usedBytes = Math.addExact(usedBytes, bytes);
    }

    synchronized void abort(ArtifactJobTransaction transaction) {
        Long reservation = reservations.remove(transaction);
        if (reservation != null) reservedBytes = Math.subtractExact(reservedBytes, reservation);
    }

    private void evictUntilWithinQuota(long additionalBytes) throws IOException {
        expireUnreported();
        while (exceedsQuota(additionalBytes)) {
            CompletedJob oldest = completed.values().stream()
                .filter(job -> !unreported.containsKey(job.directory))
                .min(Comparator.comparingLong(CompletedJob::completedAt)
                    .thenComparingLong(CompletedJob::sequence)
                    .thenComparing(job -> job.directory.toString()))
                .orElseThrow(() -> new QuotaExceededException(quotaBytes));
            verifyRootIdentity();
            ArtifactFiles.deleteTree(oldest.directory, oldest.workspaceIdentity);
            completed.remove(oldest.directory);
            usedBytes = Math.subtractExact(usedBytes, oldest.bytes);
        }
    }

    void verifyStorageIdentity(Path workspace, OwnedPathIdentity workspaceIdentity) throws IOException {
        verifyRootIdentity();
        if (!workspaceIdentity.matchesDirectory(workspace)) {
            throw new IOException("Artifact workspace directory changed identity.");
        }
    }

    private void verifyRootIdentity() throws IOException {
        if (!rootIdentity.matchesDirectory(root)) {
            throw new IOException("Artifact root directory changed identity.");
        }
    }

    private void expireUnreported() {
        long now = clock.millis();
        unreported.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private long protectionDeadline() {
        try {
            return Math.addExact(clock.millis(), UNREPORTED_TTL.toMillis());
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private boolean exceedsQuota(long additionalBytes) {
        try {
            return Math.addExact(Math.addExact(usedBytes, reservedBytes), additionalBytes) > quotaBytes;
        } catch (ArithmeticException exception) {
            return true;
        }
    }

    private static String logText(List<ReloadResult.Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) return "Shader reload failed.";
        StringBuilder output = new StringBuilder();
        for (ReloadResult.Diagnostic diagnostic : diagnostics) {
            output.append('[').append(diagnostic.severity()).append("] ").append(diagnostic.source());
            if (diagnostic.line() > 0) output.append(':').append(diagnostic.line());
            output.append(": ").append(diagnostic.message()).append(System.lineSeparator());
        }
        return output.toString();
    }

    private static String segment(String kind, String value) {
        return UUID.nameUUIDFromBytes((kind + '\0' + value).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record CommittedJob(Path directory, Path manifest, Map<String, Path> artifacts,
                               Map<String, Long> fileByteSizes, long byteSize) {
        public CommittedJob {
            directory = directory.toAbsolutePath().normalize();
            manifest = manifest.toAbsolutePath().normalize();
            artifacts = Map.copyOf(artifacts);
            fileByteSizes = Map.copyOf(fileByteSizes);
        }
    }

    private record CompletedJob(Path directory, long bytes, long completedAt, long sequence,
                                OwnedPathIdentity workspaceIdentity) { }

    public static final class JobTooLargeException extends IOException {
        public JobTooLargeException(long bytes, long quotaBytes) {
            super("Artifact job requires " + bytes + " bytes, exceeding quota " + quotaBytes + '.'); }
    }

    public static final class QuotaExceededException extends IOException {
        public QuotaExceededException(long quotaBytes) {
            super("Artifact quota " + quotaBytes + " bytes is occupied by protected jobs."); }
    }

    public final class JobTransaction extends ArtifactJobTransaction {
        private JobTransaction(Path temporary, Path directory, OwnedPathIdentity workspaceIdentity) throws IOException {
            super(ArtifactManager.this, temporary, directory, workspaceIdentity); }
    }
}