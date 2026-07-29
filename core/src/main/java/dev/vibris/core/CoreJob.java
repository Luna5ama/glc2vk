package dev.vibris.core;

import dev.vibris.api.CancellationToken;
import dev.vibris.protocol.v1.SubmitJob;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;

final class CoreJob {
    final SubmitJob submission;
    final String requestId;
    final String workspaceId;
    final String messageId;
    final long acceptedNanos = System.nanoTime();
    final CancellationToken.Source cancellation = CancellationToken.source();
    private final CountDownLatch ready = new CountDownLatch(1);
    volatile List<SourceRegistry.Lease> sources = List.of();
    volatile ControlSession session;
    volatile ScheduledFuture<?> disconnectCancellation;

    CoreJob(SubmitJob submission, String messageId, ControlSession session) {
        this.submission = submission;
        requestId = submission.getRequestId();
        workspaceId = submission.getWorkspaceId();
        this.messageId = messageId;
        this.session = session;
    }

    void initialize(List<SourceRegistry.Lease> acceptedSources) {
        sources = List.copyOf(acceptedSources);
        ready.countDown();
    }

    void awaitReady() throws InterruptedException {
        ready.await();
    }

    synchronized void bind(ControlSession replacement) {
        session = replacement;
        if (disconnectCancellation != null) disconnectCancellation.cancel(false);
        disconnectCancellation = null;
    }

    synchronized void scheduleDisconnectCancellation(ScheduledFuture<?> future) {
        if (disconnectCancellation != null) disconnectCancellation.cancel(false);
        disconnectCancellation = future;
    }

    synchronized boolean stillOwnedBy(ControlSession candidate) {
        return session == candidate;
    }
}