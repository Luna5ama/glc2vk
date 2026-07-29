package dev.vibris.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Objects;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

final class ArtifactOutputStream extends OutputStream {
    private final ArtifactJobTransaction owner;
    private final String name;
    private final Path path;
    private final FileChannel channel;
    private final OutputStream output;
    private ArtifactFiles.RegularFileIdentity identity;
    private long bytes;
    private boolean closed;

    ArtifactOutputStream(ArtifactJobTransaction owner, String name, Path path) throws IOException {
        this.owner = owner;
        this.name = name;
        this.path = path;
        channel = FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS);
        try {
            identity = ArtifactFiles.captureRegularFile(path);
            output = Channels.newOutputStream(channel);
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    @Override
    public void write(int value) throws IOException {
        write(new byte[]{(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (closed) throw new IOException("Artifact stream is closed.");
        owner.write(this, bytes, offset, length);
    }

    @Override
    public void flush() throws IOException {
        output.flush();
    }

    @Override
    public void close() throws IOException {
        synchronized (owner) {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try {
                output.flush();
                channel.force(true);
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                output.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
            try {
                ArtifactFiles.verifyIdentity(path, identity);
                identity = ArtifactFiles.captureRegularFile(path);
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
            owner.closed(this, failure != null);
            if (failure != null) throw failure;
        }
    }

    void writeDirect(byte[] bytes, int offset, int length) throws IOException {
        output.write(bytes, offset, length);
    }

    void addBytes(int length) {
        bytes = Math.addExact(bytes, length);
    }

    String name() {
        return name;
    }

    Path path() {
        return path;
    }

    long bytes() {
        return bytes;
    }

    ArtifactFiles.RegularFileIdentity identity() {
        return identity;
    }
}