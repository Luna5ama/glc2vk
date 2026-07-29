package dev.vibris.core;

import dev.vibris.api.ArtifactSink;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

class ArtifactJobTransaction implements ArtifactSink, AutoCloseable {
    private static final String MANIFEST = "manifest.json";

    private final ArtifactManager manager;
    private final Path temporary;
    private final Path directory;
    private final OwnedPathIdentity workspaceIdentity;
    private final OwnedPathIdentity temporaryIdentity;
    private final Map<String, ArtifactRecord> artifacts = new LinkedHashMap<>();
    private final Set<String> artifactNames = new HashSet<>();
    private final Set<ArtifactOutputStream> openStreams = new HashSet<>();
    private State state = State.ACTIVE;
    private long artifactBytes;
    private boolean writeFailed;

    ArtifactJobTransaction(ArtifactManager manager, Path temporary, Path directory,
                           OwnedPathIdentity workspaceIdentity) throws IOException {
        this.manager = manager;
        this.temporary = temporary;
        this.directory = directory;
        this.workspaceIdentity = workspaceIdentity;
        temporaryIdentity = OwnedPathIdentity.captureDirectory(temporary);
    }

    final Path directory() {
        return directory;
    }

    final OwnedPathIdentity workspaceIdentity() {
        return workspaceIdentity;
    }

    @Override
    public synchronized OutputStream open(String artifactName) throws IOException {
        requireActive();
        Path path = artifactPath(artifactName);
        String canonicalName = canonical(artifactName);
        if (!artifactNames.add(canonicalName)) {
            throw new IOException("Artifact name is already open or written.");
        }
        try {
            ArtifactOutputStream output = new ArtifactOutputStream(this, artifactName, path);
            openStreams.add(output);
            return output;
        } catch (IOException | RuntimeException exception) {
            artifactNames.remove(canonicalName);
            throw exception;
        }
    }

    public synchronized ArtifactManager.CommittedJob commit() throws IOException {
        return commit(null);
    }

    public synchronized ArtifactManager.CommittedJob commit(Set<String> expectedArtifacts) throws IOException {
        requireActive();
        if (!openStreams.isEmpty()) throw new IOException("Artifact streams must be closed before commit.");
        if (writeFailed) throw new IOException("Artifact output did not flush successfully.");
        if (expectedArtifacts != null) requireExpectedArtifacts(expectedArtifacts);
        verifyTemporaryAndArtifacts();
        state = State.FINALIZING;
        Map<String, Long> artifactSizes = artifactSizes();
        byte[] manifestBytes = ArtifactManifest.encode(artifactSizes);
        try {
            manager.reserve(this, Math.addExact(artifactBytes, manifestBytes.length));
            Path manifest = temporary.resolve(MANIFEST);
            ArtifactFiles.RegularFileIdentity manifestIdentity;
            try (FileChannel channel = FileChannel.open(manifest, CREATE_NEW, WRITE, NOFOLLOW_LINKS);
                 OutputStream output = Channels.newOutputStream(channel)) {
                manifestIdentity = ArtifactFiles.captureRegularFile(manifest);
                output.write(manifestBytes);
                output.flush();
                channel.force(true);
            }
            ArtifactFiles.verifyIdentity(manifest, manifestIdentity);
            manifestIdentity = ArtifactFiles.captureRegularFile(manifest);
            ArtifactFiles.verifiedSize(manifest, manifestIdentity, manifestBytes.length);
            long completedAt = Files.getLastModifiedTime(manifest).toMillis();
            verifyTemporaryAndArtifacts();
            ArtifactFiles.verifiedSize(manifest, manifestIdentity, manifestBytes.length);
            manager.verifyStorageIdentity(temporary.getParent(), workspaceIdentity);
            try {
                Files.move(temporary, directory, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Artifact filesystem does not support atomic job finalize.", exception);
            }
            long totalBytes = Math.addExact(artifactBytes, manifestBytes.length);
            state = State.COMMITTED;
            manager.complete(this, totalBytes, completedAt);
            Map<String, Path> finalArtifacts = new LinkedHashMap<>();
            artifacts.keySet().forEach(name -> finalArtifacts.put(name, directory.resolve(name)));
            Map<String, Long> fileByteSizes = new LinkedHashMap<>(artifactSizes);
            fileByteSizes.put(MANIFEST, (long) manifestBytes.length);
            return new ArtifactManager.CommittedJob(
                directory, directory.resolve(MANIFEST), finalArtifacts, fileByteSizes, totalBytes);
        } catch (IOException | RuntimeException exception) {
            try {
                abortInternal();
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (state == State.COMMITTED || state == State.ABORTED) return;
        IOException failure = null;
        for (ArtifactOutputStream stream : List.copyOf(openStreams)) {
            try {
                stream.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        try {
            abortInternal();
        } catch (IOException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        if (failure != null) throw failure;
    }

    private Path artifactPath(String artifactName) throws IOException {
        if (artifactName == null || artifactName.isBlank() || artifactName.equals(".") ||
            artifactName.equals("..") || canonical(artifactName).equals(MANIFEST)) {
            throw new IOException("Artifact name must be one safe file name.");
        }
        try {
            if (!Path.of(artifactName).getFileName().toString().equals(artifactName)) {
                throw new IOException("Artifact name must be one safe file name.");
            }
        } catch (InvalidPathException exception) {
            throw new IOException("Artifact name is invalid.", exception);
        }
        Path path = temporary.resolve(artifactName).normalize();
        if (!path.getParent().equals(temporary)) throw new IOException("Artifact path escapes its job directory.");
        return path;
    }

    synchronized void write(ArtifactOutputStream stream, byte[] bytes, int offset, int length)
        throws IOException {
        requireActive();
        manager.reserve(this, Math.addExact(artifactBytes, length));
        try {
            stream.writeDirect(bytes, offset, length);
        } catch (IOException exception) {
            writeFailed = true;
            throw exception;
        }
        artifactBytes = Math.addExact(artifactBytes, length);
        stream.addBytes(length);
    }

    synchronized void closed(ArtifactOutputStream stream, boolean failed) {
        openStreams.remove(stream);
        artifacts.put(stream.name(), new ArtifactRecord(stream.path(), stream.bytes(), stream.identity()));
        if (failed) writeFailed = true;
    }

    private void requireActive() throws IOException {
        if (state != State.ACTIVE) throw new IOException("Artifact job is not active.");
    }

    private void abortInternal() throws IOException {
        state = State.ABORTED;
        manager.abort(this);
        manager.verifyStorageIdentity(temporary.getParent(), workspaceIdentity);
        if (!temporaryIdentity.matchesDirectory(temporary)) {
            throw new IOException("Artifact job directory changed before cleanup.");
        }
        ArtifactFiles.deleteTree(temporary, workspaceIdentity);
    }

    private void verifyTemporaryAndArtifacts() throws IOException {
        manager.verifyStorageIdentity(temporary.getParent(), workspaceIdentity);
        if (!temporaryIdentity.matchesDirectory(temporary)) {
            throw new IOException("Artifact job directory changed before finalize.");
        }
        for (ArtifactRecord artifact : artifacts.values()) {
            ArtifactFiles.verifiedSize(artifact.path, artifact.identity, artifact.bytes);
        }
    }

    private void requireExpectedArtifacts(Set<String> expectedArtifacts) throws IOException {
        Set<String> expected = new HashSet<>();
        for (String name : Objects.requireNonNull(expectedArtifacts, "expectedArtifacts")) {
            artifactPath(name);
            if (!expected.add(canonical(name))) throw new IOException("Expected artifact names are repeated.");
        }
        if (!expected.equals(artifactNames)) throw new IOException("Runtime did not write the expected artifacts.");
    }

    private Map<String, Long> artifactSizes() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        artifacts.forEach((name, artifact) -> sizes.put(name, artifact.bytes));
        return sizes;
    }

    private static String canonical(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private record ArtifactRecord(Path path, long bytes, ArtifactFiles.RegularFileIdentity identity) {
    }

    private enum State { ACTIVE, FINALIZING, COMMITTED, ABORTED }
}