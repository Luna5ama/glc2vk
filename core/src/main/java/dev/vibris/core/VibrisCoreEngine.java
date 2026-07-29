package dev.vibris.core;

import dev.vibris.api.VibrisRuntimeAdapter;
import dev.vibris.core.request.RequestRegistry;
import dev.vibris.core.request.RequestState;
import dev.vibris.protocol.v1.ClientMessage;
import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.ServerMessage;
import dev.vibris.protocol.v1.SubmitJob;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class VibrisCoreEngine implements AutoCloseable {
    static final int REQUEST_REGISTRY_CAPACITY = 192;
    private static final int LIVE_REQUEST_CAPACITY = 64;
    private static final int TERMINAL_REQUEST_CAPACITY = 128;
    private static final Duration TERMINAL_TTL = Duration.ofMinutes(10);
    private static final Duration DISCONNECT_GRACE = Duration.ofSeconds(2);

    private final RequestRegistry<TerminalResult> requests = new RequestRegistry<>(
        LIVE_REQUEST_CAPACITY, TERMINAL_REQUEST_CAPACITY, TERMINAL_TTL, Clock.systemUTC());
    private final Map<String, CoreJob> liveJobs = new HashMap<>();
    private final FairJobScheduler scheduler = new FairJobScheduler();
    private final CoreProbe probe = new CoreProbe();
    private final SourceRegistry sources;
    private final RuntimeJobExecutor executor;
    private final VibrisRuntimeAdapter runtime;
    private final ScheduledExecutorService disconnectTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Vibris Disconnect Grace");
        thread.setDaemon(true);
        return thread;
    });
    private boolean closed;
    public VibrisCoreEngine(Path pendingRoot, VibrisRuntimeAdapter runtime) {
        this.runtime = runtime;
        sources = new SourceRegistry(pendingRoot, probe);
        executor = new RuntimeJobExecutor(runtime, probe);
        updateMetrics();
    }
    public CoreProbe probe() {
        return probe;
    }
    void submit(ControlSession session, ClientMessage message) {
        SubmitJob submission = message.getSubmitJob();
        String requestId = submission.getRequestId();
        if (requestId.isBlank() || !requestId.equals(message.getRequestId()) ||
            !submission.getWorkspaceId().equals(session.workspaceId())) {
            session.send(ProtocolMessages.immediateFailure(
                message, session.workspaceId(), ErrorCode.INTERNAL_ERROR, "Job envelope is inconsistent."));
            return;
        }

        List<SourceRegistry.Candidate> candidates;
        SourceRegistry.Failure validationFailure;
        try {
            candidates = sources.validate(submission.getSourcesList());
            validationFailure = null;
        } catch (SourceRegistry.Failure failure) {
            candidates = List.of();
            validationFailure = failure;
        }
        CoreJob job = new CoreJob(submission, message.getMessageId(), session);
        synchronized (this) {
            if (closed) {
                session.send(ProtocolMessages.immediateFailure(
                    message, session.workspaceId(), ErrorCode.SERVER_NOT_READY, "Vibris is shutting down."));
                return;
            }
            RequestRegistry.AcceptResult<TerminalResult> accepted = requests.accept(
                requestId, session.workspaceId());
            if (accepted.kind() == RequestRegistry.AcceptKind.OWNER_MISMATCH) {
                session.send(ProtocolMessages.immediateFailure(
                    message, session.workspaceId(), ErrorCode.INTERNAL_ERROR, "Request belongs to another workspace."));
                return;
            }
            if (accepted.kind() == RequestRegistry.AcceptKind.CACHED_FINAL) {
                session.send(accepted.snapshot().result().message(
                    message.getMessageId(), requestId, session.workspaceId()));
                return;
            }
            if (accepted.kind() == RequestRegistry.AcceptKind.CURRENT) {
                sendCurrent(session, message, accepted.snapshot().state());
                return;
            }
            if (accepted.kind() == RequestRegistry.AcceptKind.FULL) {
                session.send(ProtocolMessages.immediateFailure(
                    message, session.workspaceId(), ErrorCode.QUEUE_FULL, "The request registry is full."));
                return;
            }
            if (validationFailure != null) {
                finishRejected(session, message, validationFailure.code, validationFailure.getMessage());
                return;
            }
            List<SourceRegistry.Lease> leases;
            try {
                leases = sources.reserve(candidates);
            } catch (SourceRegistry.Failure failure) {
                finishRejected(session, message, failure.code, failure.getMessage());
                return;
            }
            liveJobs.put(requestId, job);
            if (!scheduler.submit(requestId, job.workspaceId, () -> execute(job))) {
                liveJobs.remove(requestId);
                sources.reject(leases);
                finishRejected(session, message, ErrorCode.QUEUE_FULL, "The global execution queue is full.");
                return;
            }
            sources.accept(leases);
            session.send(ProtocolMessages.accepted(job, scheduler.size()));
            job.initialize(leases);
            updateMetrics();
        }
    }
    void cancel(ControlSession session, String requestId) {
        CoreJob job;
        synchronized (this) {
            job = liveJobs.get(requestId);
            if (job == null || !job.workspaceId.equals(session.workspaceId())) return;
            job.bind(session);
            job.cancellation.cancel();
            if (!scheduler.cancel(requestId)) return;
        }
        finish(job, ProtocolMessages.failure(requestId, ErrorCode.CANCELLED, "Job was cancelled."),
            RequestState.CANCELLED, false);
    }
    void resume(ControlSession session, ClientMessage message) {
        List<ServerMessage> responses;
        synchronized (this) {
            responses = ResumeResponses.create(session, message, requests, liveJobs);
        }
        responses.forEach(session::send);
    }
    void disconnected(ControlSession session) {
        session.disconnect();
        synchronized (this) {
            for (CoreJob job : liveJobs.values()) {
                if (!job.stillOwnedBy(session)) continue;
                job.scheduleDisconnectCancellation(disconnectTimer.schedule(
                    () -> cancelDisconnected(job, session), DISCONNECT_GRACE.toMillis(), TimeUnit.MILLISECONDS));
            }
        }
    }
    private void cancelDisconnected(CoreJob job, ControlSession session) {
        synchronized (this) {
            if (!liveJobs.containsKey(job.requestId) || !job.stillOwnedBy(session)) return;
        }
        cancel(session, job.requestId);
    }
    private void execute(CoreJob job) {
        boolean started = false;
        try {
            job.awaitReady();
            synchronized (this) {
                if (!liveJobs.containsKey(job.requestId)) return;
                if (queueTimedOut(job)) {
                    finish(job, ProtocolMessages.failure(job.requestId, ErrorCode.QUEUE_TIMEOUT,
                        "Job expired in the execution queue."), RequestState.FAILED, false);
                    return;
                }
                requests.markRunning(job.requestId);
            }
            started = true;
            probe.jobStarted(job.requestId);
            probe.event(job.requestId, "ACQUIRED_LEASE");
            sources.activate(job.sources);
            TerminalResult terminal = executor.execute(job, stage -> sendProgress(job, stage));
            finish(job, terminal, RequestState.COMPLETED, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            finish(job, ProtocolMessages.failure(job.requestId, ErrorCode.CANCELLED,
                "Job execution was interrupted."), RequestState.CANCELLED, false);
        } catch (RuntimeJobExecutor.Failure failure) {
            RequestState state = failure.code == ErrorCode.CANCELLED ? RequestState.CANCELLED : RequestState.FAILED;
            finish(job, ProtocolMessages.failure(job.requestId, failure.code, failure.getMessage()), state, false);
        } finally {
            if (started) probe.jobStopped();
            updateMetrics();
        }
    }
    private void finish(CoreJob job, TerminalResult terminal, RequestState state, boolean successful) {
        ControlSession session;
        synchronized (this) {
            if (liveJobs.remove(job.requestId) == null) return;
            sources.cleanup(job.sources);
            requests.finish(job.requestId, state, terminal);
            session = job.session;
            updateMetrics();
        }
        probe.event(job.requestId, successful ? "SUCCEEDED" : state == RequestState.CANCELLED ? "CANCELLED" : "FAILED");
        session.send(terminal.message(job.messageId, job.requestId, job.workspaceId));
    }
    private void finishRejected(ControlSession session, ClientMessage message, ErrorCode code, String detail) {
        TerminalResult terminal = ProtocolMessages.failure(message.getRequestId(), code, detail);
        synchronized (this) {
            requests.finish(message.getRequestId(), RequestState.FAILED, terminal);
            updateMetrics();
        }
        session.send(terminal.message(message.getMessageId(), message.getRequestId(), session.workspaceId()));
    }
    private void sendCurrent(ControlSession session, ClientMessage message, RequestState state) {
        CoreJob job = liveJobs.get(message.getRequestId());
        if (job == null) {
            session.send(ProtocolMessages.immediateFailure(
                message, session.workspaceId(), ErrorCode.INTERNAL_ERROR, "Request validation is still in progress."));
            return;
        }
        job.bind(session);
        session.send(ProtocolMessages.accepted(job, scheduler.size()));
        if (state == RequestState.RUNNING) {
            session.send(ProtocolMessages.progress(job, dev.vibris.protocol.v1.JobStage.JOB_STAGE_WARMING_UP));
        }
    }

    private void sendProgress(CoreJob job, dev.vibris.protocol.v1.JobStage stage) {
        job.session.send(ProtocolMessages.progress(job, stage));
    }

    private boolean queueTimedOut(CoreJob job) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - job.acceptedNanos);
        long queue = job.submission.getTimeouts().getQueueTimeoutMs();
        long total = job.submission.getTimeouts().getTotalTimeoutMs();
        return queue > 0 && elapsed >= queue || total > 0 && elapsed >= total;
    }

    private synchronized void updateMetrics() { probe.registries(requests.size(), sources.size(), scheduler.size()); }

    @Override
    public void close() {
        List<CoreJob> jobs;
        synchronized (this) {
            if (closed) return;
            closed = true;
            jobs = new ArrayList<>(liveJobs.values());
        }
        for (CoreJob job : jobs) cancel(job.session, job.requestId);
        disconnectTimer.shutdownNow();
        scheduler.close();
        runtime.close();
        updateMetrics();
    }
}