package dev.vibris.core.source;

public enum SourceState {
    VALIDATED,
    QUEUED,
    ACTIVATING,
    ACTIVE,
    RELEASED_ACTIVE,
    RECLAIMABLE,
    DELETING,
    DELETED,
    FAILED
}