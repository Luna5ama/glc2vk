package dev.vibris.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class FixedShaderLink implements ShaderLink {
    private static final String ACTIVE_NAME = "shaders";
    private static final String NEXT_PREFIX = "shaders.next.";

    private final Path pendingRoot;
    private final Path shaderpackRoot;
    private final Path activeLink;
    private final AtomicMover atomicMover;

    FixedShaderLink(Path pendingRoot, Path shaderpackRoot) {
        this(pendingRoot, shaderpackRoot, (source, target) -> Files.move(
            source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    FixedShaderLink(Path pendingRoot, Path shaderpackRoot, AtomicMover atomicMover) {
        this.pendingRoot = pendingRoot.toAbsolutePath().normalize();
        this.shaderpackRoot = shaderpackRoot.toAbsolutePath().normalize();
        activeLink = this.shaderpackRoot.resolve(ACTIVE_NAME);
        this.atomicMover = Objects.requireNonNull(atomicMover, "atomicMover");
    }

    synchronized void prepare() throws Failure {
        requireOrdinaryRoots();
        detach();
        try (var children = Files.list(shaderpackRoot)) {
            for (Path child : children.filter(path -> path.getFileName().toString().startsWith(NEXT_PREFIX)).toList()) {
                deleteLink(child);
            }
        } catch (IOException exception) {
            throw new Failure("Failed to clean temporary shader links.", true, exception);
        }
    }

    @Override
    public synchronized void switchTo(SourceRegistry.Lease source, OwnershipCheck ownership) throws Failure {
        requireOrdinaryRoots();
        Path target = source.directory().toAbsolutePath().normalize();
        if (!pendingRoot.equals(target.getParent())) {
            throw new Failure("Shader source is outside the pending root.", true);
        }
        requireLinkOrMissing(activeLink);
        Path temporary = shaderpackRoot.resolve(NEXT_PREFIX + source.uuid());
        if (Files.exists(temporary, NOFOLLOW_LINKS)) {
            throw new Failure("Temporary shader link already exists.", true);
        }

        LinkIdentity identity = null;
        try {
            Files.createSymbolicLink(temporary, target);
            identity = LinkIdentity.capture(temporary);
            ownership.verify();
            if (!identity.matches(temporary) || !target.equals(Files.readSymbolicLink(temporary))) {
                throw new Failure("Temporary shader link identity changed.", true);
            }
            replace(temporary, target);
        } catch (Failure failure) {
            deleteIfOwned(temporary, identity);
            throw failure;
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            deleteIfOwned(temporary, identity);
            throw new Failure("Failed to create the directory shader link.", true, exception);
        }
    }

    @Override
    public synchronized void detach() throws Failure {
        requireLinkOrMissing(activeLink);
        if (!Files.exists(activeLink, NOFOLLOW_LINKS)) return;
        try {
            Files.delete(activeLink);
        } catch (IOException exception) {
            throw new Failure("Failed to detach the active shader link.", true, exception);
        }
    }

    @Override
    public boolean retainsActiveSource() {
        return true;
    }

    synchronized Path currentTarget() throws Failure {
        requireLinkOrMissing(activeLink);
        if (!Files.exists(activeLink, NOFOLLOW_LINKS)) return null;
        try {
            return Files.readSymbolicLink(activeLink);
        } catch (IOException exception) {
            throw new Failure("Failed to read the active shader link.", false, exception);
        }
    }

    private void replace(Path temporary, Path target) throws Failure {
        Path previous = currentTarget();
        try {
            atomicMover.move(temporary, activeLink);
        } catch (IOException atomicFailure) {
            replaceGuarded(temporary, previous, atomicFailure);
        }
        try {
            if (!Files.isSymbolicLink(activeLink) || !target.equals(Files.readSymbolicLink(activeLink))) {
                throw new IOException("active shader link target mismatch");
            }
        } catch (IOException verificationFailure) {
            boolean restored = restore(previous);
            throw new Failure("Active shader link verification failed.", restored, verificationFailure);
        }
    }

    private void replaceGuarded(Path temporary, Path previous, IOException atomicFailure) throws Failure {
        requireLinkOrMissing(activeLink);
        try {
            if (Files.exists(activeLink, NOFOLLOW_LINKS)) Files.delete(activeLink);
            Files.move(temporary, activeLink);
        } catch (IOException fallbackFailure) {
            fallbackFailure.addSuppressed(atomicFailure);
            boolean restored = restore(previous);
            throw new Failure("Failed to replace the active shader link.", restored, fallbackFailure);
        }
    }

    private boolean restore(Path previous) {
        try {
            if (Files.exists(activeLink, NOFOLLOW_LINKS)) deleteLink(activeLink);
            if (previous != null) Files.createSymbolicLink(activeLink, previous);
            return previous == null
                ? !Files.exists(activeLink, NOFOLLOW_LINKS)
                : Files.isSymbolicLink(activeLink) && previous.equals(Files.readSymbolicLink(activeLink));
        } catch (IOException | Failure exception) {
            return false;
        }
    }

    private void requireOrdinaryRoots() throws Failure {
        try {
            requireOrdinaryAncestors(pendingRoot);
            requireOrdinaryAncestors(shaderpackRoot);
        } catch (IOException exception) {
            throw new Failure("Shader link roots are missing or unsafe.", true, exception);
        }
    }

    private static void requireOrdinaryAncestors(Path path) throws IOException {
        Path current = path;
        while (current != null) {
            OwnedPathIdentity.captureDirectory(current);
            current = current.getParent();
        }
    }

    private static void requireLinkOrMissing(Path path) throws Failure {
        if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            throw new Failure("Shader link path is not a symbolic link.", true);
        }
    }

    private static void deleteLink(Path path) throws IOException, Failure {
        requireLinkOrMissing(path);
        if (Files.exists(path, NOFOLLOW_LINKS)) Files.delete(path);
    }

    private static void deleteIfOwned(Path path, LinkIdentity identity) {
        if (identity == null || !identity.matches(path)) return;
        try {
            Files.delete(path);
        } catch (IOException ignored) {
        }
    }

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path target) throws IOException;
    }

    private record LinkIdentity(Object fileKey, FileTime creationTime, Path target) {
        static LinkIdentity capture(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!attributes.isSymbolicLink() || !Files.isSymbolicLink(path)) {
                throw new IOException("path is not a symbolic link");
            }
            return new LinkIdentity(attributes.fileKey(), attributes.creationTime(), Files.readSymbolicLink(path));
        }

        boolean matches(Path path) {
            try {
                LinkIdentity current = capture(path);
                boolean sameFile = fileKey != null || current.fileKey != null
                    ? fileKey != null && fileKey.equals(current.fileKey)
                    : creationTime.equals(current.creationTime);
                return sameFile && target.equals(current.target);
            } catch (IOException exception) {
                return false;
            }
        }
    }
}