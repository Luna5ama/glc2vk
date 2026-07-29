package dev.vibris.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

record OwnedPathIdentity(Object fileKey, FileTime creationTime) {
    static void createDirectoriesSafely(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path existing = normalized;
        while (existing != null) {
            try {
                captureDirectory(existing);
                break;
            } catch (NoSuchFileException exception) {
                existing = existing.getParent();
            }
        }
        if (existing == null) throw new IOException("owned path has no ordinary ancestor");
        requireOrdinaryAncestors(existing);
        Files.createDirectories(normalized);
        requireOrdinaryAncestors(normalized);
    }

    static OwnedPathIdentity captureDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther() ||
            Files.isSymbolicLink(path)) {
            throw new IOException("owned path is not an ordinary directory");
        }
        return new OwnedPathIdentity(attributes.fileKey(), attributes.creationTime());
    }

    boolean matchesDirectory(Path path) {
        try {
            OwnedPathIdentity current = captureDirectory(path);
            if (fileKey != null || current.fileKey != null) return fileKey != null && fileKey.equals(current.fileKey);
            return creationTime.equals(current.creationTime);
        } catch (IOException exception) {
            return false;
        }
    }

    private static void requireOrdinaryAncestors(Path path) throws IOException {
        Path current = path;
        while (current != null) {
            captureDirectory(current);
            current = current.getParent();
        }
    }
}