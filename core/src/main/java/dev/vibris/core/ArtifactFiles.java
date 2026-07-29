package dev.vibris.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class ArtifactFiles {
    private static final String MANIFEST = "manifest.json";
    static final int MAX_RECOVERED_JOBS = 16_384;

    private ArtifactFiles() {
    }

    static List<RecoveredJob> recover(Path root, OwnedPathIdentity rootIdentity) throws IOException {
        return recover(root, rootIdentity, MAX_RECOVERED_JOBS);
    }

    static List<RecoveredJob> recover(Path root, OwnedPathIdentity rootIdentity, int maxJobs) throws IOException {
        if (maxJobs < 1) throw new IllegalArgumentException("maxJobs must be positive");
        requireDirectoryIdentity(root, rootIdentity);
        List<RecoveredJob> recovered = new ArrayList<>();
        int visitedJobs = 0;
        try (var workspaces = Files.newDirectoryStream(root)) {
            for (Path workspace : workspaces) {
                requireDirectoryIdentity(root, rootIdentity);
                if (workspace.getFileName().toString().endsWith(".tmp")) {
                    deleteTree(workspace, rootIdentity);
                    continue;
                }
                if (!Files.isDirectory(workspace, NOFOLLOW_LINKS)) continue;
                OwnedPathIdentity workspaceIdentity = OwnedPathIdentity.captureDirectory(workspace);
                try (var jobs = Files.newDirectoryStream(workspace)) {
                    for (Path job : jobs) {
                        requireDirectoryIdentity(root, rootIdentity);
                        requireDirectoryIdentity(workspace, workspaceIdentity);
                        if (job.getFileName().toString().endsWith(".tmp")) {
                            deleteTree(job, workspaceIdentity);
                            continue;
                        }
                        if (!Files.isDirectory(job, NOFOLLOW_LINKS)) continue;
                        if (++visitedJobs > maxJobs) {
                            throw new IOException("Artifact recovery job limit exceeded.");
                        }
                        try {
                            Path manifest = job.resolve(MANIFEST);
                            if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS)) {
                                throw new IOException("Artifact job manifest is missing or not a regular file.");
                            }
                            recovered.add(new RecoveredJob(job, directorySize(job),
                                Files.getLastModifiedTime(manifest, NOFOLLOW_LINKS).toMillis(), workspaceIdentity));
                        } catch (IOException malformed) {
                            try {
                                requireDirectoryIdentity(root, rootIdentity);
                                deleteTree(job, workspaceIdentity);
                            } catch (IOException cleanupFailure) {
                                cleanupFailure.addSuppressed(malformed);
                                throw cleanupFailure;
                            }
                        }
                    }
                }
            }
        }
        requireDirectoryIdentity(root, rootIdentity);
        return recovered;
    }

    static void deleteTree(Path directory, OwnedPathIdentity parentIdentity) throws IOException {
        Path parent = directory.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Artifact deletion target has no parent.");
        requireDirectoryIdentity(parent, parentIdentity);
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                requireDirectoryIdentity(parent, parentIdentity);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path current, IOException failure) throws IOException {
                if (failure != null) throw failure;
                requireDirectoryIdentity(parent, parentIdentity);
                Files.delete(current);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static RegularFileIdentity captureRegularFile(Path file) throws IOException {
        BasicFileAttributes attributes = regularFileAttributes(file);
        return new RegularFileIdentity(
            attributes.fileKey(), attributes.creationTime(), attributes.lastModifiedTime());
    }

    static long verifiedSize(Path file, RegularFileIdentity identity, long expectedBytes) throws IOException {
        BasicFileAttributes attributes = regularFileAttributes(file);
        if (!matchesIdentity(identity, attributes) ||
            !identity.lastModifiedTime.equals(attributes.lastModifiedTime())) {
            throw new IOException("Artifact file changed before finalize.");
        }
        if (attributes.size() != expectedBytes) throw new IOException("Artifact file size changed before finalize.");
        return attributes.size();
    }

    static void verifyIdentity(Path file, RegularFileIdentity identity) throws IOException {
        if (!matchesIdentity(identity, regularFileAttributes(file))) {
            throw new IOException("Artifact file changed while it was open.");
        }
    }

    private static long directorySize(Path directory) throws IOException {
        long[] bytes = {0};
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attributes)
                throws IOException {
                if (!current.equals(directory)) throw new IOException("Artifact job contains a nested directory.");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IOException("Artifact job contains a non-regular file.");
                }
                try {
                    bytes[0] = Math.addExact(bytes[0], attributes.size());
                } catch (ArithmeticException exception) {
                    throw new IOException("Artifact job size overflowed.", exception);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return bytes[0];
    }

    private static BasicFileAttributes regularFileAttributes(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther() ||
            Files.isSymbolicLink(file)) {
            throw new IOException("Artifact path is not an ordinary regular file.");
        }
        return attributes;
    }

    private static boolean matchesIdentity(RegularFileIdentity identity, BasicFileAttributes attributes) {
        boolean matchingKey = identity.fileKey != null || attributes.fileKey() != null
            ? identity.fileKey != null && identity.fileKey.equals(attributes.fileKey())
            : identity.creationTime.equals(attributes.creationTime());
        return matchingKey && identity.creationTime.equals(attributes.creationTime());
    }

    private static void requireDirectoryIdentity(Path directory, OwnedPathIdentity identity) throws IOException {
        if (!identity.matchesDirectory(directory)) {
            throw new IOException("Artifact storage directory changed identity.");
        }
    }

    record RegularFileIdentity(Object fileKey, FileTime creationTime, FileTime lastModifiedTime) {
    }

    record RecoveredJob(Path directory, long bytes, long completedAt, OwnedPathIdentity workspaceIdentity) {
    }
}