package dev.vibris.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ArtifactManager {
    static final long DEFAULT_QUOTA_BYTES = 3L * 1024 * 1024 * 1024;

    private final Path root;

    ArtifactManager(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
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
}