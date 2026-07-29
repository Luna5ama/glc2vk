package dev.vibris.api;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

@FunctionalInterface
public interface CancellationToken {
    boolean isCancellationRequested();

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) throw new CancellationException("Vibris operation was cancelled");
    }

    static CancellationToken none() {
        return () -> false;
    }

    static Source source() {
        return new Source();
    }

    final class Source {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CancellationToken token = cancelled::get;

        private Source() {
        }

        public CancellationToken token() {
            return token;
        }

        public boolean cancel() {
            return cancelled.compareAndSet(false, true);
        }
    }
}