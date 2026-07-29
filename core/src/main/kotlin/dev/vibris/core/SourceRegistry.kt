package dev.vibris.core

import dev.vibris.core.source.SourceRecord
import dev.vibris.core.source.SourceState
import dev.vibris.protocol.v1.ErrorCode
import dev.vibris.protocol.v1.PreparedSourceRef
import java.nio.file.Path
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.UUID

internal class SourceRegistry @JvmOverloads constructor(
    pendingRoot: Path,
    private val probe: CoreProbe,
    maxSourceBytes: Long = ServerConfiguration.DEFAULT_MAX_SOURCE_BYTES,
    maxSourceFiles: Int = ServerConfiguration.DEFAULT_MAX_SOURCE_FILES,
) {
    private val trees = OwnedSourceTree(pendingRoot, maxSourceBytes, maxSourceFiles)
    private val sources = HashMap<String, Lease>()
    private var activeSource: Lease? = null

    @Throws(Failure::class)
    fun validate(references: List<PreparedSourceRef>): List<Candidate> = validate(references, 1)

    @Throws(Failure::class)
    fun validate(references: List<PreparedSourceRef>, expectedCount: Int): List<Candidate> {
        if (references.isEmpty()) {
            throw Failure(ErrorCode.SOURCE_DIRECTORY_MISSING, "A source is required.")
        }
        if (references.size != expectedCount) {
            throw Failure(ErrorCode.SOURCE_ACTIVATION_FAILED, "Prepared source count does not match the job.")
        }
        val unique = HashSet<String>()
        val candidates = ArrayList<Candidate>(references.size)
        for (reference in references) {
            val uuid = requireUuid(reference.uuid)
            if (!unique.add(uuid)) {
                throw Failure(ErrorCode.INVALID_SOURCE_UUID, "Source UUID is repeated.")
            }
            val inspection = trees.inspect(uuid)
            if (inspection.fileCount != reference.fileCount || inspection.totalBytes != reference.totalBytes) {
                throw Failure(
                    ErrorCode.SOURCE_DIRECTORY_MISSING,
                    "Source metadata does not match its directory.",
                )
            }
            candidates.add(Candidate(uuid, inspection.directory, reference.fileCount, reference.totalBytes))
        }
        return candidates
    }

    @Synchronized
    @Throws(Failure::class)
    fun reserve(candidates: List<Candidate>): List<Lease> {
        if (sources.size + candidates.size > CAPACITY) {
            throw Failure(ErrorCode.QUEUE_FULL, "The source registry is full.")
        }
        val ownerships = ArrayList<OwnedSourceTree.Ownership>(candidates.size)
        for (candidate in candidates) {
            if (sources.containsKey(candidate.uuid)) {
                throw Failure(ErrorCode.INVALID_SOURCE_UUID, "Source UUID is already owned.")
            }
            ownerships.add(trees.reserve(candidate.directory, candidate.fileCount, candidate.totalBytes))
        }
        val reserved = ArrayList<Lease>(candidates.size)
        for (index in candidates.indices) {
            val candidate = candidates[index]
            val lease = Lease(
                candidate.uuid,
                candidate.directory,
                ownerships[index],
                SourceRecord(candidate.uuid, 1),
            )
            sources[candidate.uuid] = lease
            reserved.add(lease)
        }
        return reserved
    }

    @Synchronized
    fun accept(reserved: List<Lease>) {
        for (lease in reserved) {
            transition(lease, "", SourceState.VALIDATED, Runnable { lease.record.state() })
            transition(lease, SourceState.VALIDATED, SourceState.QUEUED, Runnable { lease.record.queue() })
        }
    }

    @Synchronized
    fun reject(reserved: List<Lease>) {
        for (lease in reserved) {
            sources.remove(lease.uuid, lease)
        }
    }

    @Synchronized
    @Throws(Failure::class)
    fun beginActivation(lease: Lease): Activation {
        requireOwned(lease)
        transition(
            lease,
            SourceState.QUEUED,
            SourceState.ACTIVATING,
            Runnable { lease.record.beginActivation() },
        )
        return Activation(lease, activeSource)
    }

    @Synchronized
    fun commitActivation(activation: Activation) {
        if (activeSource !== activation.previous) {
            throw IllegalStateException("active source changed")
        }
        val next = activation.next
        transition(next, SourceState.ACTIVATING, SourceState.ACTIVE, Runnable { next.record.activated() })
        activeSource = next
        val previous = activation.previous
        if (previous != null) {
            val before = previous.record.state()
            previous.record.deactivate()
            record(previous, before, previous.record.state())
            deleteIfEligible(previous)
        }
    }

    @Synchronized
    fun failActivation(activation: Activation) {
        failActivation(activation.next)
    }

    @Synchronized
    fun failActivation(lease: Lease) {
        val before = lease.record.state()
        lease.record.failed()
        record(lease, before, lease.record.state())
    }

    @Synchronized
    @Throws(Failure::class)
    fun requireOwned(lease: Lease) {
        if (!sources.containsKey(lease.uuid) || !trees.stillOwned(lease.directory, lease.ownership)) {
            throw Failure(ErrorCode.SOURCE_ACTIVATION_FAILED, "Prepared source identity changed.")
        }
    }

    @Synchronized
    fun activeUuid(): String = activeSource?.uuid ?: ""

    fun cleanup(leases: List<Lease>) {
        release(leases, false)
    }

    fun release(leases: List<Lease>, retainActive: Boolean) {
        for (lease in leases) {
            release(lease, retainActive)
        }
    }

    @Synchronized
    fun size(): Int = sources.size

    @Synchronized
    private fun release(lease: Lease, retainActive: Boolean) {
        if (!sources.containsKey(lease.uuid)) {
            return
        }
        if (!trees.stillOwned(lease.directory, lease.ownership)) {
            abandonUnsafe(lease)
            return
        }
        var before = lease.record.state()
        lease.record.release()
        record(lease, before, lease.record.state())
        if (lease.record.active() && !retainActive) {
            before = lease.record.state()
            lease.record.deactivate()
            record(lease, before, lease.record.state())
            if (activeSource === lease) {
                activeSource = null
            }
        }
        deleteIfEligible(lease)
    }

    private fun abandonUnsafe(lease: Lease) {
        var before = lease.record.state()
        if (before == SourceState.VALIDATED || before == SourceState.QUEUED || before == SourceState.ACTIVATING) {
            lease.record.failed()
            record(lease, before, lease.record.state())
        }
        before = lease.record.state()
        lease.record.release()
        record(lease, before, lease.record.state())
        if (lease.record.active()) {
            before = lease.record.state()
            lease.record.deactivate()
            record(lease, before, lease.record.state())
        }
        if (activeSource === lease) {
            activeSource = null
        }
        sources.remove(lease.uuid, lease)
    }

    @Synchronized
    fun close() {
        val active = activeSource
        if (active != null && active.record.active()) {
            val before = active.record.state()
            active.record.deactivate()
            record(active, before, active.record.state())
        }
        activeSource = null
        ArrayList(sources.values).forEach(::deleteIfEligible)
    }

    private fun deleteIfEligible(lease: Lease) {
        if (!lease.record.deletionEligible()) {
            return
        }
        var before = lease.record.state()
        lease.record.beginDeleting()
        record(lease, before, lease.record.state())
        if (!OwnedSourceTree.delete(lease.directory)) {
            sources.remove(lease.uuid, lease)
            return
        }
        before = lease.record.state()
        lease.record.deleted()
        record(lease, before, lease.record.state())
        sources.remove(lease.uuid, lease)
    }

    private fun transition(lease: Lease, from: SourceState, to: SourceState, action: Runnable) {
        action.run()
        record(lease, from, to)
    }

    private fun transition(lease: Lease, from: String, to: SourceState, action: Runnable) {
        action.run()
        probe.sourceTransition(lease.uuid, from, to.name)
    }

    private fun record(lease: Lease, from: SourceState, to: SourceState) {
        probe.sourceTransition(lease.uuid, from.name, to.name)
    }

    data class Candidate(
        val uuid: String,
        val directory: Path,
        val fileCount: Long,
        val totalBytes: Long,
    ) {
        fun uuid(): String = uuid

        fun directory(): Path = directory

        fun fileCount(): Long = fileCount

        fun totalBytes(): Long = totalBytes
    }

    data class Lease(
        val uuid: String,
        val directory: Path,
        val ownership: OwnedSourceTree.Ownership,
        val record: SourceRecord,
    ) {
        fun uuid(): String = uuid

        fun directory(): Path = directory

        fun ownership(): OwnedSourceTree.Ownership = ownership

        fun record(): SourceRecord = record
    }

    data class Activation(
        val next: Lease,
        val previous: Lease?,
    ) {
        fun next(): Lease = next

        fun previous(): Lease? = previous
    }

    class Failure(
        @JvmField val code: ErrorCode,
        message: String,
    ) : Exception(message)

    companion object {
        const val CAPACITY = 128

        @Throws(Failure::class)
        private fun requireUuid(value: String): String {
            try {
                val uuid = UUID.fromString(value)
                if (!uuid.toString().equals(value, ignoreCase = true)) {
                    throw IllegalArgumentException()
                }
                return uuid.toString()
            } catch (_: IllegalArgumentException) {
                throw Failure(ErrorCode.INVALID_SOURCE_UUID, "Source UUID is invalid.")
            }
        }
    }
}