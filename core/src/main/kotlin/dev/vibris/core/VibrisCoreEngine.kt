package dev.vibris.core

import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.core.request.RequestRegistry
import dev.vibris.core.request.RequestState
import dev.vibris.protocol.v1.ClientMessage
import dev.vibris.protocol.v1.ErrorCode
import dev.vibris.protocol.v1.JobStage
import dev.vibris.protocol.v1.ServerMessage
import dev.vibris.protocol.v1.SubmitJob
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class VibrisCoreEngine internal constructor(
    pendingRoot: Path,
    private val runtime: VibrisRuntimeAdapter,
    shaderLink: ShaderLink,
    shaderLogs: ShaderLogSink,
    maxSourceBytes: Long = ServerConfiguration.DEFAULT_MAX_SOURCE_BYTES,
    maxSourceFiles: Int = ServerConfiguration.DEFAULT_MAX_SOURCE_FILES,
    maxGlobalQueue: Int = ServerConfiguration.DEFAULT_MAX_GLOBAL_QUEUE,
    maxActionsPerJob: Int = ServerConfiguration.DEFAULT_MAX_ACTIONS_PER_JOB,
) : AutoCloseable {
    private val requests = RequestRegistry<TerminalResult>(
        LIVE_REQUEST_CAPACITY,
        TERMINAL_REQUEST_CAPACITY,
        TERMINAL_TTL,
        Clock.systemUTC(),
    )
    private val liveJobs = HashMap<String, CoreJob>()
    private val scheduler = FairJobScheduler(maxGlobalQueue)
    private val probe = CoreProbe()
    private val sources = SourceRegistry(pendingRoot, probe, maxSourceBytes, maxSourceFiles)
    private val activator = SourceActivator(sources, shaderLink)
    private val executor = RuntimeJobExecutor(runtime, probe, activator, shaderLogs, maxActionsPerJob)
    private val delivery = TerminalDelivery(shaderLogs)
    private val disconnectTimer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Vibris Disconnect Grace").apply { isDaemon = true }
    }
    private var closed = false

    constructor(pendingRoot: Path, runtime: VibrisRuntimeAdapter) :
        this(pendingRoot, runtime, ShaderLink.transientLink(), ShaderLogSink.none())

    init {
        updateMetrics()
    }

    fun probe(): CoreProbe = probe

    @Synchronized
    fun ready(): Boolean = !closed && activator.ready()

    @Synchronized
    fun activeSourceUuid(): String = sources.activeUuid()

    @Synchronized
    fun queueLength(): Int = scheduler.size()

    internal fun submit(session: ControlSession, message: ClientMessage) {
        val submission = message.submitJob
        val requestId = submission.requestId
        if (requestId.isBlank() || requestId != message.requestId ||
            submission.workspaceId != session.workspaceId()
        ) {
            failImmediate(session, message, ErrorCode.INTERNAL_ERROR, "Job envelope is inconsistent.")
            return
        }
        var candidates: List<SourceRegistry.Candidate> = emptyList()
        var validationFailure: SourceRegistry.Failure? = null
        try {
            val sourceCount = if (submission.hasRecipe() && submission.recipe.hasAbCompare()) 2 else 1
            candidates = sources.validate(submission.sourcesList, sourceCount)
        } catch (failure: SourceRegistry.Failure) {
            validationFailure = failure
        }
        val job = CoreJob(submission, message.messageId, session)
        synchronized(this) {
            if (closed || !activator.ready()) {
                failImmediate(session, message, ErrorCode.SERVER_NOT_READY, "Vibris is not ready.")
                return
            }
            val accepted = requests.accept(requestId, session.workspaceId())
            if (accepted.kind == RequestRegistry.AcceptKind.OWNER_MISMATCH) {
                failImmediate(session, message, ErrorCode.INTERNAL_ERROR, "Request belongs to another workspace.")
                return
            }
            if (accepted.kind == RequestRegistry.AcceptKind.CACHED_FINAL) {
                delivery.send(
                    session,
                    accepted.snapshot!!.result!!.message(message.messageId, requestId, session.workspaceId()),
                    session.workspaceId(),
                    requestId,
                )
                return
            }
            if (accepted.kind == RequestRegistry.AcceptKind.CURRENT) {
                sendCurrent(session, message, accepted.snapshot!!.state)
                return
            }
            if (accepted.kind == RequestRegistry.AcceptKind.FULL) {
                failImmediate(session, message, ErrorCode.QUEUE_FULL, "The request registry is full.")
                return
            }
            if (validationFailure != null) {
                finishRejected(session, message, validationFailure.code, validationFailure.message!!)
                return
            }
            val leases: List<SourceRegistry.Lease>
            try {
                leases = sources.reserve(candidates)
            } catch (failure: SourceRegistry.Failure) {
                finishRejected(session, message, failure.code, failure.message!!)
                return
            }
            liveJobs[requestId] = job
            if (!scheduler.submit(requestId, job.workspaceId, Runnable { execute(job) })) {
                liveJobs.remove(requestId)
                sources.reject(leases)
                finishRejected(session, message, ErrorCode.QUEUE_FULL, "The global execution queue is full.")
                return
            }
            sources.accept(leases)
            session.send(ProtocolMessages.accepted(job, scheduler.size()))
            job.initialize(leases)
            updateMetrics()
        }
    }

    internal fun cancel(session: ControlSession, requestId: String) {
        val job = synchronized(this) {
            val current = liveJobs[requestId]
            if (current == null || current.workspaceId != session.workspaceId()) {
                return
            }
            current.bind(session)
            current.cancellation.cancel()
            if (!scheduler.cancel(requestId)) {
                return
            }
            current
        }
        finish(
            job,
            ProtocolMessages.failure(requestId, ErrorCode.CANCELLED, "Job was cancelled."),
            RequestState.CANCELLED,
            false,
        )
    }

    internal fun resume(session: ControlSession, message: ClientMessage) {
        val responses: List<ServerMessage>
        synchronized(this) {
            responses = ResumeResponses.create(session, message, requests, liveJobs)
        }
        for (response in responses) {
            delivery.send(session, response, session.workspaceId(), response.requestId)
        }
    }

    internal fun disconnected(session: ControlSession) {
        session.disconnect()
        synchronized(this) {
            for (job in liveJobs.values) {
                if (!job.stillOwnedBy(session)) {
                    continue
                }
                job.scheduleDisconnectCancellation(
                    disconnectTimer.schedule(
                        { cancelDisconnected(job, session) },
                        DISCONNECT_GRACE.toMillis(),
                        TimeUnit.MILLISECONDS,
                    ),
                )
            }
        }
    }

    private fun cancelDisconnected(job: CoreJob, session: ControlSession) {
        synchronized(this) {
            if (!liveJobs.containsKey(job.requestId) || !job.stillOwnedBy(session)) {
                return
            }
        }
        cancel(session, job.requestId)
    }

    private fun execute(job: CoreJob) {
        var started = false
        try {
            job.awaitReady()
            synchronized(this) {
                if (!liveJobs.containsKey(job.requestId)) {
                    return
                }
                if (queueTimedOut(job)) {
                    finish(
                        job,
                        ProtocolMessages.failure(
                            job.requestId,
                            ErrorCode.QUEUE_TIMEOUT,
                            "Job expired in the execution queue.",
                        ),
                        RequestState.FAILED,
                        false,
                    )
                    return
                }
                requests.markRunning(job.requestId)
            }
            started = true
            probe.jobStarted(job.requestId)
            probe.event(job.requestId, "ACQUIRED_LEASE")
            val terminal = executor.execute(job) { stage -> sendProgress(job, stage) }
            finish(job, terminal, RequestState.COMPLETED, true)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            finish(
                job,
                ProtocolMessages.failure(
                    job.requestId,
                    ErrorCode.CANCELLED,
                    "Job execution was interrupted.",
                ),
                RequestState.CANCELLED,
                false,
            )
        } catch (failure: RuntimeJobExecutor.Failure) {
            val state = if (failure.code == ErrorCode.CANCELLED) {
                RequestState.CANCELLED
            } else {
                RequestState.FAILED
            }
            finish(
                job,
                ProtocolMessages.failure(job.requestId, failure.code, failure.message!!, failure.artifacts),
                state,
                false,
            )
        } finally {
            if (started) {
                probe.jobStopped()
            }
            updateMetrics()
        }
    }

    private fun finish(job: CoreJob, terminal: TerminalResult, state: RequestState, successful: Boolean) {
        val session = synchronized(this) {
            if (liveJobs.remove(job.requestId) == null) {
                return
            }
            activator.release(job.sources)
            requests.finish(job.requestId, state, terminal)
            updateMetrics()
            job.session!!
        }
        probe.event(
            job.requestId,
            if (successful) {
                "SUCCEEDED"
            } else if (state == RequestState.CANCELLED) {
                "CANCELLED"
            } else {
                "FAILED"
            },
        )
        delivery.send(
            session,
            terminal.message(job.messageId, job.requestId, job.workspaceId),
            job.workspaceId,
            job.requestId,
        )
    }

    private fun finishRejected(
        session: ControlSession,
        message: ClientMessage,
        code: ErrorCode,
        detail: String,
    ) {
        val terminal = ProtocolMessages.failure(message.requestId, code, detail)
        synchronized(this) {
            requests.finish(message.requestId, RequestState.FAILED, terminal)
            updateMetrics()
        }
        session.send(terminal.message(message.messageId, message.requestId, session.workspaceId()))
    }

    private fun sendCurrent(session: ControlSession, message: ClientMessage, state: RequestState) {
        val job = liveJobs[message.requestId]
        if (job == null) {
            failImmediate(
                session,
                message,
                ErrorCode.INTERNAL_ERROR,
                "Request validation is still in progress.",
            )
            return
        }
        job.bind(session)
        session.send(ProtocolMessages.accepted(job, scheduler.size()))
        if (state == RequestState.RUNNING) {
            session.send(ProtocolMessages.progress(job, JobStage.JOB_STAGE_WARMING_UP))
        }
    }

    private fun sendProgress(job: CoreJob, stage: JobStage) {
        job.session!!.send(ProtocolMessages.progress(job, stage))
    }

    private fun queueTimedOut(job: CoreJob): Boolean {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - job.acceptedNanos)
        val queue = job.submission.timeouts.queueTimeoutMs
        val total = job.submission.timeouts.totalTimeoutMs
        return queue > 0 && elapsed >= queue || total > 0 && elapsed >= total
    }

    @Synchronized
    private fun updateMetrics() {
        probe.registries(requests.size(), sources.size(), scheduler.size())
    }

    override fun close() {
        val jobs = synchronized(this) {
            if (closed) {
                return
            }
            closed = true
            ArrayList(liveJobs.values)
        }
        for (job in jobs) {
            cancel(job.session!!, job.requestId)
        }
        EngineShutdown.close(runtime, disconnectTimer, scheduler, activator)
        updateMetrics()
    }

    companion object {
        const val REQUEST_REGISTRY_CAPACITY = 192
        private const val LIVE_REQUEST_CAPACITY = 64
        private const val TERMINAL_REQUEST_CAPACITY = 128
        private val TERMINAL_TTL: Duration = Duration.ofMinutes(10)
        private val DISCONNECT_GRACE: Duration = Duration.ofSeconds(2)

        private fun failImmediate(
            session: ControlSession,
            message: ClientMessage,
            code: ErrorCode,
            detail: String,
        ) {
            session.send(ProtocolMessages.immediateFailure(message, session.workspaceId(), code, detail))
        }
    }
}