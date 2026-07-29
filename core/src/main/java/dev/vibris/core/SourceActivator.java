package dev.vibris.core;

import dev.vibris.protocol.v1.ErrorCode;

import java.util.List;

final class SourceActivator implements AutoCloseable {
    private final SourceRegistry sources;
    private final ShaderLink link;
    private boolean ready = true;
    private boolean closed;

    SourceActivator(SourceRegistry sources, ShaderLink link) {
        this.sources = sources;
        this.link = link;
    }

    synchronized Activation begin(SourceRegistry.Lease next) throws Failure {
        requireReady();
        SourceRegistry.Activation activation;
        try {
            activation = sources.beginActivation(next);
            link.switchTo(next, () -> requireOwned(next));
        } catch (SourceRegistry.Failure failure) {
            throw fail(next, ErrorCode.SOURCE_ACTIVATION_FAILED, failure.getMessage(), true, failure);
        } catch (ShaderLink.Failure failure) {
            throw fail(next, ErrorCode.SYMLINK_SWITCH_FAILED, failure.getMessage(), failure.stable(), failure);
        }
        return new Activation(activation);
    }

    synchronized void commit(Activation activation) throws Failure {
        try {
            sources.requireOwned(activation.state.next());
            sources.commitActivation(activation.state);
        } catch (SourceRegistry.Failure failure) {
            throw new Failure(ErrorCode.SOURCE_ACTIVATION_FAILED, failure.getMessage(), failure);
        }
    }

    synchronized boolean rollback(Activation activation) {
        SourceRegistry.Lease previous = activation.state.previous();
        try {
            if (previous == null) {
                link.detach();
            } else {
                link.switchTo(previous, () -> requireOwned(previous));
            }
            return true;
        } catch (ShaderLink.Failure failure) {
            ready = false;
            return false;
        }
    }

    synchronized void fail(Activation activation) {
        sources.failActivation(activation.state);
    }

    synchronized void release(List<SourceRegistry.Lease> leases) {
        sources.release(leases, link.retainsActiveSource());
    }

    synchronized boolean ready() {
        return ready && !closed;
    }

    synchronized void markNotReady() {
        ready = false;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            link.detach();
        } catch (ShaderLink.Failure failure) {
            ready = false;
        }
        sources.close();
    }

    private void requireOwned(SourceRegistry.Lease source) throws ShaderLink.Failure {
        try {
            sources.requireOwned(source);
        } catch (SourceRegistry.Failure failure) {
            throw new ShaderLink.Failure(failure.getMessage(), true, failure);
        }
    }

    private Failure fail(
        SourceRegistry.Lease source,
        ErrorCode code,
        String message,
        boolean stable,
        Exception cause
    ) {
        sources.failActivation(source);
        if (!stable) ready = false;
        return new Failure(code, message, cause);
    }

    private void requireReady() throws Failure {
        if (!ready || closed) throw new Failure(ErrorCode.SERVER_NOT_READY, "Source activation is not ready.");
    }

    record Activation(SourceRegistry.Activation state) {
        SourceRegistry.Lease previous() {
            return state.previous();
        }
    }

    static final class Failure extends Exception {
        final ErrorCode code;

        Failure(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        Failure(ErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }
}