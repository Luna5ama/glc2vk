package dev.vibris.core;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class FairJobScheduler implements AutoCloseable {
    static final int CAPACITY = 32;

    private final Map<String, ArrayDeque<Entry>> queues = new LinkedHashMap<>();
    private final ArrayDeque<String> workspaceRing = new ArrayDeque<>();
    private final Thread worker;
    private String activeWorkspace;
    private int queued;
    private int peakSize;
    private boolean closed;

    FairJobScheduler() {
        worker = new Thread(this::workLoop, "Vibris Core Scheduler");
        worker.setDaemon(true);
        worker.start();
    }

    synchronized boolean submit(String requestId, String workspaceId, Runnable task) {
        requireId(requestId, "request ID");
        requireId(workspaceId, "workspace ID");
        Objects.requireNonNull(task, "task");
        if (closed || queued == CAPACITY) return false;

        queues.computeIfAbsent(workspaceId, ignored -> new ArrayDeque<>())
            .addLast(new Entry(requestId, workspaceId, task));
        queued++;
        peakSize = Math.max(peakSize, queued);
        if (!workspaceId.equals(activeWorkspace) && !workspaceRing.contains(workspaceId)) {
            workspaceRing.addLast(workspaceId);
        }
        notifyAll();
        return true;
    }

    synchronized boolean cancel(String requestId) {
        requireId(requestId, "request ID");
        for (var workspace = queues.entrySet().iterator(); workspace.hasNext();) {
            Map.Entry<String, ArrayDeque<Entry>> candidate = workspace.next();
            for (var jobs = candidate.getValue().iterator(); jobs.hasNext();) {
                if (!jobs.next().requestId().equals(requestId)) continue;
                jobs.remove();
                queued--;
                if (candidate.getValue().isEmpty() && !candidate.getKey().equals(activeWorkspace)) {
                    workspace.remove();
                    workspaceRing.remove(candidate.getKey());
                }
                return true;
            }
        }
        return false;
    }

    synchronized int size() {
        return queued;
    }

    synchronized int peakSize() {
        return peakSize;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!closed) {
                closed = true;
                notifyAll();
            }
        }
        if (Thread.currentThread() == worker) return;

        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void workLoop() {
        while (true) {
            Entry job;
            try {
                job = take();
            } catch (InterruptedException ignored) {
                continue;
            }
            if (job == null) return;

            try {
                job.task().run();
            } catch (RuntimeException | Error failure) {
                worker.getUncaughtExceptionHandler().uncaughtException(worker, failure);
            } finally {
                finish(job.workspaceId());
            }
        }
    }

    private synchronized Entry take() throws InterruptedException {
        while (workspaceRing.isEmpty()) {
            if (closed && queued == 0) return null;
            wait();
        }
        String workspaceId = workspaceRing.removeFirst();
        ArrayDeque<Entry> workspaceQueue = queues.get(workspaceId);
        Entry job = workspaceQueue.removeFirst();
        queued--;
        activeWorkspace = workspaceId;
        return job;
    }

    private synchronized void finish(String workspaceId) {
        activeWorkspace = null;
        ArrayDeque<Entry> workspaceQueue = queues.get(workspaceId);
        if (workspaceQueue.isEmpty()) {
            queues.remove(workspaceId);
        } else {
            workspaceRing.addLast(workspaceId);
        }
        notifyAll();
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private record Entry(String requestId, String workspaceId, Runnable task) {
    }
}