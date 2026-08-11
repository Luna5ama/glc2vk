package dev.vibris.core

import java.util.ArrayDeque
import java.util.LinkedHashMap

internal class FairJobScheduler(
    private val capacity: Int = CAPACITY,
    private val stateChanged: () -> Unit = {},
) : AutoCloseable {
    private val queues = LinkedHashMap<String, ArrayDeque<Entry>>()
    private val workspaceRing = ArrayDeque<String>()
    private val worker = Thread(::workLoop, "Vibris Core Scheduler").apply {
        isDaemon = true
        start()
    }
    private var active: ActiveJob? = null
    private var queued = 0
    private var peakSize = 0
    private var closed = false

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun submit(metadata: JobMetadata, task: Runnable?): Submission {
        val requiredTask = requireNotNull(task) { "task" }
        val result = synchronized(this) {
            requireMetadata(metadata)
            if (closed || queued >= capacity) {
                return@synchronized Submission(false, 0)
            }
            queues.computeIfAbsent(metadata.workspaceId) { ArrayDeque() }
                .addLast(Entry(metadata, requiredTask))
            queued++
            peakSize = maxOf(peakSize, queued)
            if (metadata.workspaceId != active?.metadata?.workspaceId &&
                !workspaceRing.contains(metadata.workspaceId)
            ) {
                workspaceRing.addLast(metadata.workspaceId)
            }
            monitorNotifyAll()
            val position = orderedQueue().indexOfFirst { it.requestId == metadata.requestId } + 1
            Submission(true, position)
        }
        if (result.accepted) stateChanged()
        return result
    }

    fun cancel(requestId: String?): Boolean {
        val request = requireId(requestId, "request ID")
        val cancelled = synchronized(this) {
            val workspaces = queues.entries.iterator()
            while (workspaces.hasNext()) {
                val candidate = workspaces.next()
                val jobs = candidate.value.iterator()
                while (jobs.hasNext()) {
                    if (jobs.next().metadata.requestId != request) continue
                    jobs.remove()
                    queued--
                    if (candidate.value.isEmpty() && candidate.key != active?.metadata?.workspaceId) {
                        workspaces.remove()
                        workspaceRing.remove(candidate.key)
                    }
                    return@synchronized true
                }
            }
            false
        }
        if (cancelled) stateChanged()
        return cancelled
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val ordered = orderedQueue()
        return Snapshot(
            active,
            ordered.mapIndexed { index, metadata -> QueuedJob(metadata, index + 1) },
            closed,
        )
    }

    @Synchronized
    fun size(): Int = queued

    @Synchronized
    fun canAccept(): Boolean = !closed && queued < capacity

    @Synchronized
    fun peakSize(): Int = peakSize

    @Synchronized
    fun isClosed(): Boolean = closed

    override fun close() {
        val changed = synchronized(this) {
            if (closed) {
                false
            } else {
                closed = true
                monitorNotifyAll()
                true
            }
        }
        if (changed) stateChanged()
        if (Thread.currentThread() == worker) return

        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun workLoop() {
        while (true) {
            val job = try {
                take()
            } catch (_: InterruptedException) {
                continue
            } ?: return

            stateChanged()
            try {
                job.task.run()
            } catch (failure: RuntimeException) {
                worker.uncaughtExceptionHandler.uncaughtException(worker, failure)
            } catch (failure: Error) {
                worker.uncaughtExceptionHandler.uncaughtException(worker, failure)
            } finally {
                finish(job.metadata.workspaceId)
                stateChanged()
            }
        }
    }

    @Synchronized
    @Throws(InterruptedException::class)
    private fun take(): Entry? {
        while (workspaceRing.isEmpty()) {
            if (closed && queued == 0) return null
            monitorWait()
        }
        val workspaceId = workspaceRing.removeFirst()
        val workspaceQueue = queues.getValue(workspaceId)
        val job = workspaceQueue.removeFirst()
        queued--
        active = ActiveJob(job.metadata, System.currentTimeMillis())
        return job
    }

    @Synchronized
    private fun finish(workspaceId: String) {
        active = null
        val workspaceQueue = queues[workspaceId]
        if (workspaceQueue == null || workspaceQueue.isEmpty()) {
            queues.remove(workspaceId)
        } else if (!workspaceRing.contains(workspaceId)) {
            workspaceRing.addLast(workspaceId)
        }
        monitorNotifyAll()
    }

    @Synchronized
    private fun orderedQueue(): List<JobMetadata> {
        if (queued == 0) return emptyList()
        val copies = LinkedHashMap<String, ArrayDeque<JobMetadata>>()
        queues.forEach { (workspace, jobs) ->
            if (jobs.isNotEmpty()) copies[workspace] = ArrayDeque(jobs.map(Entry::metadata))
        }
        val ring = ArrayDeque(workspaceRing)
        val activeWorkspace = active?.metadata?.workspaceId
        if (activeWorkspace != null && !ring.contains(activeWorkspace) &&
            copies[activeWorkspace]?.isNotEmpty() == true
        ) {
            ring.addLast(activeWorkspace)
        }
        val ordered = ArrayList<JobMetadata>(queued)
        while (ring.isNotEmpty()) {
            val workspace = ring.removeFirst()
            val jobs = copies[workspace] ?: continue
            if (jobs.isEmpty()) continue
            ordered.add(jobs.removeFirst())
            if (jobs.isNotEmpty()) ring.addLast(workspace)
        }
        return ordered
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun monitorWait() {
        (this as java.lang.Object).wait()
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun monitorNotifyAll() {
        (this as java.lang.Object).notifyAll()
    }

    @JvmRecord
    data class JobMetadata(
        val requestId: String,
        val workspaceId: String,
        val jobId: String,
        val worktreeRoot: String,
        val operation: String,
        val queuedAtUnixMs: Long,
    )

    @JvmRecord
    data class ActiveJob(val metadata: JobMetadata, val startedAtUnixMs: Long)

    @JvmRecord
    data class QueuedJob(val metadata: JobMetadata, val position: Int)

    @JvmRecord
    data class Snapshot(val active: ActiveJob?, val queued: List<QueuedJob>, val closed: Boolean)

    @JvmRecord
    data class Submission(val accepted: Boolean, val position: Int)

    @JvmRecord
    private data class Entry(val metadata: JobMetadata, val task: Runnable)

    companion object {
        const val CAPACITY = 32

        private fun requireMetadata(metadata: JobMetadata) {
            requireId(metadata.requestId, "request ID")
            requireId(metadata.workspaceId, "workspace ID")
            requireId(metadata.jobId, "job ID")
            requireId(metadata.operation, "operation")
            require(metadata.queuedAtUnixMs >= 0) { "queued timestamp must not be negative" }
        }

        private fun requireId(value: String?, name: String): String {
            if (value.isNullOrBlank()) throw IllegalArgumentException("$name must not be blank")
            return value
        }
    }
}
