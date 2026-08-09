package dev.vibris.core.source

class SourceRecord(
    private val uuidValue: String,
    private var referencesValue: Int,
) {
    private var stateValue = SourceState.VALIDATED
    private var activeValue = false

    init {
        require(uuidValue.isNotBlank()) { "source UUID must not be blank" }
        require(referencesValue >= 1) { "source must have an initial reference" }
    }

    @Synchronized
    fun uuid(): String = uuidValue

    @Synchronized
    fun state(): SourceState = stateValue

    @Synchronized
    fun references(): Int = referencesValue

    @Synchronized
    fun active(): Boolean = activeValue

    @Synchronized
    fun retain() {
        requireState(
            SourceState.VALIDATED,
            SourceState.QUEUED,
            SourceState.ACTIVATING,
            SourceState.ACTIVE,
            SourceState.RELEASED_ACTIVE,
        )
        referencesValue++
        if (stateValue == SourceState.RELEASED_ACTIVE) stateValue = SourceState.ACTIVE
    }

    @Synchronized
    fun queue() {
        transition(SourceState.QUEUED)
    }

    @Synchronized
    fun beginActivation() {
        transition(SourceState.ACTIVATING)
    }

    @Synchronized
    fun activated() {
        transition(SourceState.ACTIVE)
        activeValue = true
    }

    @Synchronized
    fun retryActivation() {
        transition(SourceState.QUEUED)
    }

    @Synchronized
    fun failed() {
        transition(SourceState.FAILED)
    }

    @Synchronized
    fun release() {
        check(referencesValue != 0) { "source has no reference to release" }
        referencesValue--
        if (referencesValue != 0) {
            return
        }
        if (activeValue) {
            transition(SourceState.RELEASED_ACTIVE)
        } else {
            transition(SourceState.RECLAIMABLE)
        }
    }

    @Synchronized
    fun deactivate() {
        requireState(SourceState.ACTIVE, SourceState.RELEASED_ACTIVE)
        check(activeValue) { "source is not active" }
        activeValue = false
        if (referencesValue == 0) {
            transition(SourceState.RECLAIMABLE)
        } else {
            transition(SourceState.QUEUED)
        }
    }

    @Synchronized
    fun deletionEligible(): Boolean =
        referencesValue == 0 && !activeValue && stateValue == SourceState.RECLAIMABLE

    @Synchronized
    fun beginDeleting() {
        check(deletionEligible()) { "source is not reclaimable" }
        transition(SourceState.DELETING)
    }

    @Synchronized
    fun deleted() {
        transition(SourceState.DELETED)
    }

    private fun requireState(vararg allowed: SourceState) {
        if (allowed.none { it == stateValue }) {
            throw IllegalStateException("invalid source state: $stateValue")
        }
    }

    private fun transition(next: SourceState) {
        val allowed = when (stateValue) {
            SourceState.VALIDATED ->
                next == SourceState.QUEUED ||
                    next == SourceState.FAILED ||
                    next == SourceState.RECLAIMABLE
            SourceState.QUEUED ->
                next == SourceState.ACTIVATING ||
                    next == SourceState.FAILED ||
                    next == SourceState.RECLAIMABLE
            SourceState.ACTIVATING ->
                next == SourceState.ACTIVE ||
                    next == SourceState.QUEUED ||
                    next == SourceState.FAILED ||
                    next == SourceState.RECLAIMABLE
            SourceState.ACTIVE ->
                next == SourceState.RELEASED_ACTIVE ||
                    next == SourceState.QUEUED ||
                    next == SourceState.RECLAIMABLE
            SourceState.RELEASED_ACTIVE, SourceState.FAILED -> next == SourceState.RECLAIMABLE
            SourceState.RECLAIMABLE -> next == SourceState.DELETING
            SourceState.DELETING -> next == SourceState.DELETED
            SourceState.DELETED -> false
        }
        check(allowed) { "invalid source transition: $stateValue -> $next" }
        stateValue = next
    }
}