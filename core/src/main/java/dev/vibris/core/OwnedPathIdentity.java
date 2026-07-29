package dev.vibris.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

record OwnedPathIdentity(Object fileKey, FileTime creationTime) {
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
}