package dev.vibris.core

import java.util.ArrayDeque
import java.util.LinkedHashMap

internal class FairJobScheduler(private val capacity: Int = CAPACITY) : AutoCloseable {
    private val queues = LinkedHashMap<String, ArrayDeque<Entry>>()
    private val workspaceRing = ArrayDeque<String>()
    private val worker = Thread(::workLoop, "Vibris Core Scheduler").apply {
        isDaemon = true
        start()
    }
    private var activeWorkspace: String? = null
    private var queued = 0
    private var peakSize = 0
    private var closed = false

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun submit(requestId: String?, workspaceId: String?, task: Runnable?): Boolean {
        val request = requireId(requestId, "request ID")
        val workspace = requireId(workspaceId, "workspace ID")
        val requiredTask = requireNotNull(task) { "task" }
        if (closed || queued >= capacity) {
            return false
        }

        queues.computeIfAbsent(workspace) { ArrayDeque() }
            .addLast(Entry(request, workspace, requiredTask))
        queued++
        peakSize = maxOf(peakSize, queued)
        if (workspace != activeWorkspace && !workspaceRing.contains(workspace)) {
            workspaceRing.addLast(workspace)
        }
        monitorNotifyAll()
        return true
    }

    @Synchronized
    fun cancel(requestId: String?): Boolean {
        val request = requireId(requestId, "request ID")
        val workspaces = queues.entries.iterator()
        while (workspaces.hasNext()) {
            val candidate = workspaces.next()
            val jobs = candidate.value.iterator()
            while (jobs.hasNext()) {
                if (jobs.next().requestId != request) {
                    continue
                }
                jobs.remove()
                queued--
                if (candidate.value.isEmpty() && candidate.key != activeWorkspace) {
                    workspaces.remove()
                    workspaceRing.remove(candidate.key)
                }
                return true
            }
        }
        return false
    }

    @Synchronized
    fun size(): Int = queued

    @Synchronized
    fun peakSize(): Int = peakSize

    @Synchronized
    fun isClosed(): Boolean = closed

    override fun close() {
        synchronized(this) {
            if (!closed) {
                closed = true
                monitorNotifyAll()
            }
        }
        if (Thread.currentThread() == worker) {
            return
        }

        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }

    private fun workLoop() {
        while (true) {
            val job = try {
                take()
            } catch (_: InterruptedException) {
                continue
            } ?: return

            try {
                job.task.run()
            } catch (failure: RuntimeException) {
                worker.uncaughtExceptionHandler.uncaughtException(worker, failure)
            } catch (failure: Error) {
                worker.uncaughtExceptionHandler.uncaughtException(worker, failure)
            } finally {
                finish(job.workspaceId)
            }
        }
    }

    @Synchronized
    @Throws(InterruptedException::class)
    private fun take(): Entry? {
        while (workspaceRing.isEmpty()) {
            if (closed && queued == 0) {
                return null
            }
            monitorWait()
        }
        val workspaceId = workspaceRing.removeFirst()
        val workspaceQueue = queues.getValue(workspaceId)
        val job = workspaceQueue.removeFirst()
        queued--
        activeWorkspace = workspaceId
        return job
    }

    @Synchronized
    private fun finish(workspaceId: String) {
        activeWorkspace = null
        val workspaceQueue = queues.getValue(workspaceId)
        if (workspaceQueue.isEmpty()) {
            queues.remove(workspaceId)
        } else {
            workspaceRing.addLast(workspaceId)
        }
        monitorNotifyAll()
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
    private data class Entry(val requestId: String, val workspaceId: String, val task: Runnable)

    companion object {
        const val CAPACITY = 32

        private fun requireId(value: String?, name: String): String {
            if (value.isNullOrBlank()) {
                throw IllegalArgumentException("$name must not be blank")
            }
            return value
        }
    }
}