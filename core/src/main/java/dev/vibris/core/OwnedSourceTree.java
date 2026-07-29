package dev.vibris.core;

import dev.vibris.protocol.v1.ErrorCode;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class OwnedSourceTree {
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final int MAX_FILES = 100_000;

    private final Path pendingRoot;
    private OwnedPathIdentity pendingRootIdentity;

    OwnedSourceTree(Path pendingRoot) {
        this.pendingRoot = pendingRoot.toAbsolutePath().normalize();
    }

    Inspection inspect(String uuid) throws SourceRegistry.Failure {
        requireSafePendingRoot();
        Path directory = pendingRoot.resolve(uuid).normalize();
        if (!pendingRoot.equals(directory.getParent())) {
            throw new SourceRegistry.Failure(ErrorCode.INVALID_SOURCE_UUID, "Source UUID escapes the pending root.");
        }
        FileStats stats = scan(directory);
        return new Inspection(directory, stats.files, stats.bytes);
    }

    synchronized Ownership reserve(Path directory, long fileCount, long totalBytes)
        throws SourceRegistry.Failure {
        requireSafePendingRoot();
        FileStats stats = scan(directory);
        if (stats.files != fileCount || stats.bytes != totalBytes) {
            throw new SourceRegistry.Failure(
                ErrorCode.SOURCE_DIRECTORY_MISSING, "Source changed before ownership transfer.");
        }
        try {
            return new Ownership(pendingRootIdentity, OwnedPathIdentity.captureDirectory(directory));
        } catch (IOException exception) {
            throw new SourceRegistry.Failure(
                ErrorCode.SOURCE_CONTAINS_REPARSE_POINT, "Source changed before ownership transfer.");
        }
    }

    boolean stillOwned(Path directory, Ownership ownership) {
        return ownership.rootIdentity.matchesDirectory(pendingRoot) &&
            ownership.directoryIdentity.matchesDirectory(directory);
    }

    private FileStats scan(Path directory) throws SourceRegistry.Failure {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw new SourceRegistry.Failure(
                ErrorCode.SOURCE_DIRECTORY_MISSING, "Prepared source directory is missing.");
        }
        FileStats stats = new FileStats();
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                    requireOrdinary(path, attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    requireOrdinary(path, attributes);
                    if (!attributes.isRegularFile()) throw new IOException("non-ordinary source entry");
                    stats.add(attributes.size());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new SourceRegistry.Failure(
                ErrorCode.SOURCE_CONTAINS_REPARSE_POINT, "Prepared source is not an ordinary tree.");
        }
        if (stats.files == 0) {
            throw new SourceRegistry.Failure(ErrorCode.SOURCE_DIRECTORY_MISSING, "Prepared source is empty.");
        }
        return stats;
    }

    private synchronized void requireSafePendingRoot() throws SourceRegistry.Failure {
        Path current = pendingRoot;
        try {
            OwnedPathIdentity identity = OwnedPathIdentity.captureDirectory(pendingRoot);
            if (pendingRootIdentity == null) pendingRootIdentity = identity;
            if (!pendingRootIdentity.matchesDirectory(pendingRoot)) {
                throw new SourceRegistry.Failure(
                    ErrorCode.SOURCE_CONTAINS_REPARSE_POINT, "Pending source root identity changed.");
            }
            while (current != null) {
                OwnedPathIdentity.captureDirectory(current);
                current = current.getParent();
            }
        } catch (IOException exception) {
            ErrorCode code = Files.exists(pendingRoot, NOFOLLOW_LINKS)
                ? ErrorCode.SOURCE_CONTAINS_REPARSE_POINT
                : ErrorCode.SOURCE_DIRECTORY_MISSING;
            throw new SourceRegistry.Failure(code, "Pending source root is missing or unsafe.");
        }
    }

    static boolean delete(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                    requireOrdinary(directory, attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    requireOrdinary(file, attributes);
                    if (!attributes.isRegularFile()) throw new IOException("non-ordinary owned source entry");
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void requireOrdinary(Path path, BasicFileAttributes attributes) throws IOException {
        if (Files.isSymbolicLink(path) || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("link-like source entry");
        }
    }

    record Inspection(Path directory, long fileCount, long totalBytes) {
    }

    record Ownership(OwnedPathIdentity rootIdentity, OwnedPathIdentity directoryIdentity) {
    }

    private static final class FileStats {
        private long bytes;
        private int files;

        private void add(long size) throws IOException {
            if (++files > MAX_FILES || size > MAX_BYTES - bytes) throw new IOException("source limit exceeded");
            bytes += size;
        }
    }
}