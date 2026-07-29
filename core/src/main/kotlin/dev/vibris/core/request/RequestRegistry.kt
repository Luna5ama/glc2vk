package dev.vibris.core.request

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.Optional

class RequestRegistry<T : Any>(
    private val liveCapacity: Int,
    private val terminalCapacity: Int,
    private val terminalTtl: Duration,
    private val clock: Clock,
) {
    private val live = LinkedHashMap<String, Entry<T>>()
    private val terminal = LinkedHashMap<String, Entry<T>>()

    init {
        require(liveCapacity >= 1 && terminalCapacity >= 1) {
            "request registry capacities must be positive"
        }
        require(!terminalTtl.isNegative && !terminalTtl.isZero) {
            "terminal request TTL must be positive"
        }
    }

    @Synchronized
    fun accept(requestId: String?): AcceptResult<T> = accept(requestId, "")

    @Synchronized
    fun accept(requestId: String?, ownerId: String?): AcceptResult<T> {
        val id = requireId(requestId)
        val owner = ownerId ?: throw NullPointerException("ownerId")
        purgeExpired()
        val current = live[id]
        if (current != null) {
            if (current.ownerId != owner) {
                return AcceptResult(AcceptKind.OWNER_MISMATCH, null)
            }
            return AcceptResult(AcceptKind.CURRENT, current.snapshot())
        }
        val completed = terminal[id]
        if (completed != null) {
            if (completed.ownerId != owner) {
                return AcceptResult(AcceptKind.OWNER_MISMATCH, null)
            }
            return AcceptResult(AcceptKind.CACHED_FINAL, completed.snapshot())
        }
        if (live.size == liveCapacity) {
            return AcceptResult(AcceptKind.FULL, null)
        }
        val accepted = Entry<T>(owner, RequestState.ACCEPTED)
        live[id] = accepted
        return AcceptResult(AcceptKind.NEW, accepted.snapshot())
    }

    @Synchronized
    fun markRunning(requestId: String?) {
        val entry = requireLive(requestId)
        check(entry.state == RequestState.ACCEPTED) {
            "request is not accepted: $requestId"
        }
        entry.state = RequestState.RUNNING
    }

    @Synchronized
    fun finish(requestId: String?, state: RequestState, result: T) {
        require(state.terminal()) { "final request state must be terminal" }
        val id = requireId(requestId)
        val entry = requireLive(id)
        live.remove(id)
        entry.state = state
        entry.result = result
        entry.completedAt = clock.instant()
        purgeExpired()
        while (terminal.size >= terminalCapacity) {
            terminal.remove(terminal.keys.iterator().next())
        }
        terminal[id] = entry
    }

    @Synchronized
    fun resume(requestId: String?): Optional<Snapshot<T>> = resume(requestId, "")

    @Synchronized
    fun resume(requestId: String?, ownerId: String?): Optional<Snapshot<T>> {
        val id = requireId(requestId)
        val owner = ownerId ?: throw NullPointerException("ownerId")
        purgeExpired()
        val entry = live[id] ?: terminal[id]
        if (entry != null && entry.ownerId != owner) {
            return Optional.empty()
        }
        return if (entry == null) Optional.empty() else Optional.of(entry.snapshot())
    }

    @Synchronized
    fun liveSize(): Int = live.size

    @Synchronized
    fun terminalSize(): Int {
        purgeExpired()
        return terminal.size
    }

    @Synchronized
    fun size(): Int {
        purgeExpired()
        return live.size + terminal.size
    }

    private fun requireLive(requestId: String?): Entry<T> {
        val id = requireId(requestId)
        return live[id] ?: throw IllegalStateException("unknown live request: $id")
    }

    private fun purgeExpired() {
        val cutoff = clock.instant().minus(terminalTtl)
        terminal.entries.removeIf { !it.value.completedAt!!.isAfter(cutoff) }
    }

    private fun requireId(requestId: String?): String {
        require(!requestId.isNullOrBlank()) { "request ID must not be blank" }
        return requestId
    }

    enum class AcceptKind {
        NEW,
        CURRENT,
        CACHED_FINAL,
        OWNER_MISMATCH,
        FULL,
    }

    @JvmRecord
    data class AcceptResult<T>(val kind: AcceptKind, val snapshot: Snapshot<T>?)

    @JvmRecord
    data class Snapshot<T>(val state: RequestState, val result: T?)

    private class Entry<T>(
        val ownerId: String,
        var state: RequestState,
        var result: T? = null,
        var completedAt: Instant? = null,
    ) {
        fun snapshot(): Snapshot<T> = Snapshot(state, result)
    }
}