package dev.vibris.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class PendingSourceRoot {
    private final Path root;

    PendingSourceRoot(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    void prepare() throws Failure {
        try {
            OwnedPathIdentity.createDirectoriesSafely(root);
            requireOrdinaryAncestors();
        } catch (IOException exception) {
            throw new Failure("Pending source root is missing or unsafe.", exception);
        }
        clear();
    }

    void clear() throws Failure {
        try {
            requireOrdinaryAncestors();
            try (var children = Files.list(root)) {
                for (Path child : children.toList()) {
                    if (!deleteEntry(child)) {
                        throw new Failure("Pending source root contains an unsafe entry.");
                    }
                }
            }
        } catch (IOException exception) {
            throw new Failure("Failed to clear the pending source root.", exception);
        }
    }

    private static boolean deleteEntry(Path child) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || Files.isSymbolicLink(child)) {
            Files.delete(child);
            return true;
        }
        return OwnedSourceTree.delete(child);
    }

    private void requireOrdinaryAncestors() throws IOException {
        Path current = root;
        while (current != null) {
            OwnedPathIdentity.captureDirectory(current);
            current = current.getParent();
        }
    }

    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }

        Failure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}