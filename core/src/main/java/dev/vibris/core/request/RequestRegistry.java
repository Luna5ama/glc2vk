package dev.vibris.core.request;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RequestRegistry<T> {
    private final int liveCapacity;
    private final int terminalCapacity;
    private final Duration terminalTtl;
    private final Clock clock;
    private final Map<String, Entry<T>> live = new LinkedHashMap<>();
    private final Map<String, Entry<T>> terminal = new LinkedHashMap<>();

    public RequestRegistry(int liveCapacity, int terminalCapacity, Duration terminalTtl, Clock clock) {
        if (liveCapacity < 1 || terminalCapacity < 1) {
            throw new IllegalArgumentException("request registry capacities must be positive");
        }
        if (terminalTtl.isNegative() || terminalTtl.isZero()) {
            throw new IllegalArgumentException("terminal request TTL must be positive");
        }
        this.liveCapacity = liveCapacity;
        this.terminalCapacity = terminalCapacity;
        this.terminalTtl = terminalTtl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized AcceptResult<T> accept(String requestId) {
        return accept(requestId, "");
    }

    public synchronized AcceptResult<T> accept(String requestId, String ownerId) {
        requireId(requestId);
        Objects.requireNonNull(ownerId, "ownerId");
        purgeExpired();
        Entry<T> current = live.get(requestId);
        if (current != null) {
            if (!current.ownerId.equals(ownerId)) return new AcceptResult<>(AcceptKind.OWNER_MISMATCH, null);
            return new AcceptResult<>(AcceptKind.CURRENT, current.snapshot());
        }
        Entry<T> completed = terminal.get(requestId);
        if (completed != null) {
            if (!completed.ownerId.equals(ownerId)) return new AcceptResult<>(AcceptKind.OWNER_MISMATCH, null);
            return new AcceptResult<>(AcceptKind.CACHED_FINAL, completed.snapshot());
        }
        if (live.size() == liveCapacity) {
            return new AcceptResult<>(AcceptKind.FULL, null);
        }
        Entry<T> accepted = new Entry<>(ownerId, RequestState.ACCEPTED, null, null);
        live.put(requestId, accepted);
        return new AcceptResult<>(AcceptKind.NEW, accepted.snapshot());
    }

    public synchronized void markRunning(String requestId) {
        Entry<T> entry = requireLive(requestId);
        if (entry.state != RequestState.ACCEPTED) {
            throw new IllegalStateException("request is not accepted: " + requestId);
        }
        entry.state = RequestState.RUNNING;
    }

    public synchronized void finish(String requestId, RequestState state, T result) {
        if (!Objects.requireNonNull(state, "state").terminal()) {
            throw new IllegalArgumentException("final request state must be terminal");
        }
        Entry<T> entry = requireLive(requestId);
        live.remove(requestId);
        entry.state = state;
        entry.result = Objects.requireNonNull(result, "result");
        entry.completedAt = clock.instant();
        purgeExpired();
        while (terminal.size() >= terminalCapacity) {
            terminal.remove(terminal.keySet().iterator().next());
        }
        terminal.put(requestId, entry);
    }

    public synchronized Optional<Snapshot<T>> resume(String requestId) {
        return resume(requestId, "");
    }

    public synchronized Optional<Snapshot<T>> resume(String requestId, String ownerId) {
        requireId(requestId);
        Objects.requireNonNull(ownerId, "ownerId");
        purgeExpired();
        Entry<T> entry = live.get(requestId);
        if (entry == null) entry = terminal.get(requestId);
        if (entry != null && !entry.ownerId.equals(ownerId)) return Optional.empty();
        return entry == null ? Optional.empty() : Optional.of(entry.snapshot());
    }

    public synchronized int liveSize() {
        return live.size();
    }

    public synchronized int terminalSize() {
        purgeExpired();
        return terminal.size();
    }

    public synchronized int size() {
        purgeExpired();
        return live.size() + terminal.size();
    }

    private Entry<T> requireLive(String requestId) {
        requireId(requestId);
        Entry<T> entry = live.get(requestId);
        if (entry == null) throw new IllegalStateException("unknown live request: " + requestId);
        return entry;
    }

    private void purgeExpired() {
        Instant cutoff = clock.instant().minus(terminalTtl);
        terminal.entrySet().removeIf(entry -> !entry.getValue().completedAt.isAfter(cutoff));
    }

    private static void requireId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("request ID must not be blank");
        }
    }

    public enum AcceptKind {
        NEW,
        CURRENT,
        CACHED_FINAL,
        OWNER_MISMATCH,
        FULL
    }

    public record AcceptResult<T>(AcceptKind kind, Snapshot<T> snapshot) {
    }

    public record Snapshot<T>(RequestState state, T result) {
    }

    private static final class Entry<T> {
        private final String ownerId;
        private RequestState state;
        private T result;
        private Instant completedAt;

        private Entry(String ownerId, RequestState state, T result, Instant completedAt) {
            this.ownerId = ownerId;
            this.state = state;
            this.result = result;
            this.completedAt = completedAt;
        }

        private Snapshot<T> snapshot() {
            return new Snapshot<>(state, result);
        }
    }
}