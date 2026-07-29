package dev.vibris.core.source

enum class SourceState {
    VALIDATED,
    QUEUED,
    ACTIVATING,
    ACTIVE,
    RELEASED_ACTIVE,
    RECLAIMABLE,
    DELETING,
    DELETED,
    FAILED,
}