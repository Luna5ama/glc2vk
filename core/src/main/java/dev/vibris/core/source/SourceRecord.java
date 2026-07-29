package dev.vibris.core.source;

import java.util.Arrays;
import java.util.Objects;

public final class SourceRecord {
    private final String uuid;
    private SourceState state = SourceState.VALIDATED;
    private int references;
    private boolean active;

    public SourceRecord(String uuid, int references) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        if (uuid.isBlank()) throw new IllegalArgumentException("source UUID must not be blank");
        if (references < 1) throw new IllegalArgumentException("source must have an initial reference");
        this.references = references;
    }

    public synchronized String uuid() {
        return uuid;
    }

    public synchronized SourceState state() {
        return state;
    }

    public synchronized int references() {
        return references;
    }

    public synchronized boolean active() {
        return active;
    }

    public synchronized void retain() {
        requireState(SourceState.VALIDATED, SourceState.QUEUED, SourceState.ACTIVATING, SourceState.ACTIVE);
        references++;
    }

    public synchronized void queue() {
        transition(SourceState.QUEUED);
    }

    public synchronized void beginActivation() {
        transition(SourceState.ACTIVATING);
    }

    public synchronized void activated() {
        transition(SourceState.ACTIVE);
        active = true;
    }

    public synchronized void failed() {
        transition(SourceState.FAILED);
    }

    public synchronized void release() {
        if (references == 0) throw new IllegalStateException("source has no reference to release");
        references--;
        if (references != 0) return;
        if (active) {
            transition(SourceState.RELEASED_ACTIVE);
        } else {
            transition(SourceState.RECLAIMABLE);
        }
    }

    public synchronized void deactivate() {
        requireState(SourceState.ACTIVE, SourceState.RELEASED_ACTIVE);
        if (!active) throw new IllegalStateException("source is not active");
        active = false;
        if (references == 0) transition(SourceState.RECLAIMABLE);
    }

    public synchronized boolean deletionEligible() {
        return references == 0 && !active && state == SourceState.RECLAIMABLE;
    }

    public synchronized void beginDeleting() {
        if (!deletionEligible()) throw new IllegalStateException("source is not reclaimable");
        transition(SourceState.DELETING);
    }

    public synchronized void deleted() {
        transition(SourceState.DELETED);
    }

    private void requireState(SourceState... allowed) {
        if (Arrays.stream(allowed).noneMatch(candidate -> candidate == state)) {
            throw new IllegalStateException("invalid source state: " + state);
        }
    }

    private void transition(SourceState next) {
        boolean allowed = switch (state) {
            case VALIDATED -> next == SourceState.QUEUED || next == SourceState.FAILED ||
                next == SourceState.RECLAIMABLE;
            case QUEUED -> next == SourceState.ACTIVATING || next == SourceState.FAILED ||
                next == SourceState.RECLAIMABLE;
            case ACTIVATING -> next == SourceState.ACTIVE || next == SourceState.FAILED ||
                next == SourceState.RECLAIMABLE;
            case ACTIVE -> next == SourceState.RELEASED_ACTIVE || next == SourceState.RECLAIMABLE;
            case RELEASED_ACTIVE, FAILED -> next == SourceState.RECLAIMABLE;
            case RECLAIMABLE -> next == SourceState.DELETING;
            case DELETING -> next == SourceState.DELETED;
            case DELETED -> false;
        };
        if (!allowed) throw new IllegalStateException("invalid source transition: " + state + " -> " + next);
        state = next;
    }
}