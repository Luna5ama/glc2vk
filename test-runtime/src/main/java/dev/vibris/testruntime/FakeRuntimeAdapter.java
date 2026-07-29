package dev.vibris.testruntime;

import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.RuntimeStatus;
import dev.vibris.api.SceneContext;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.api.VibrisRuntimeAdapter;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

public final class FakeRuntimeAdapter implements VibrisRuntimeAdapter {
    private static final long NANOS_PER_FRAME = 100_000L;
    private static final long MAX_WAIT_SLICE_NANOS = 1_000_000L;

    private final Object frameGate = new Object();
    private final AtomicLong renderedFrames = new AtomicLong();
    private final AtomicInteger activeOperations = new AtomicInteger();
    private final AtomicInteger maxConcurrentOperations = new AtomicInteger();
    private volatile String currentSaveId = "test-save";
    private volatile String currentDimensionId = "minecraft:overworld";
    private volatile boolean paused;
    private volatile boolean closed;

    @Override
    public CompletionStage<RuntimeStatus> getStatus() {
        return immediate(() -> new RuntimeStatus(true, currentSaveId, currentDimensionId, ""));
    }

    @Override
    public CompletionStage<ContextApplyResult> ensureWorldAndContext(
        SceneContext context,
        CancellationToken cancellation
    ) {
        return immediate(cancellation, () -> {
            SceneContext snapshot = Objects.requireNonNull(context, "context");
            currentSaveId = snapshot.saveId();
            currentDimensionId = snapshot.dimensionId();
            return ContextApplyResult.success(snapshot);
        });
    }

    @Override
    public CompletionStage<ReloadResult> reloadVibrisShaderpack(CancellationToken cancellation) {
        return immediate(cancellation, () -> ReloadResult.success(List.of()));
    }

    @Override
    public CompletionStage<TemporalResetResult> resetTemporalState(CancellationToken cancellation) {
        return immediate(cancellation, () -> new TemporalResetResult(true));
    }

    @Override
    public CompletionStage<Long> waitRenderedFrames(int frameCount, CancellationToken cancellation) {
        if (frameCount < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("frameCount must not be negative"));
        }
        Objects.requireNonNull(cancellation, "cancellation");
        CompletableFuture<Long> result = new CompletableFuture<>();
        Thread.ofVirtual().name("Vibris fake frames").start(() -> {
            int active = activeOperations.incrementAndGet();
            maxConcurrentOperations.accumulateAndGet(active, Math::max);
            try {
                long remainingNanos = Math.multiplyExact(frameCount, NANOS_PER_FRAME);
                while (remainingNanos > 0) {
                    awaitUnpaused(cancellation);
                    long startedNanos = System.nanoTime();
                    LockSupport.parkNanos(Math.min(remainingNanos, MAX_WAIT_SLICE_NANOS));
                    checkActive(cancellation);
                    remainingNanos -= Math.min(System.nanoTime() - startedNanos, remainingNanos);
                }
                checkActive(cancellation);
                long completedFrame = renderedFrames.addAndGet(frameCount);
                activeOperations.decrementAndGet();
                result.complete(completedFrame);
            } catch (Throwable throwable) {
                activeOperations.decrementAndGet();
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    int maxConcurrentOperations() {
        return maxConcurrentOperations.get();
    }

    @Override
    public ResourceCatalog getResourceCatalog() {
        ensureOpen();
        return ResourceCatalog.empty();
    }

    @Override
    public CompletionStage<CaptureResult> capture(
        CapturePlan plan,
        ArtifactSink sink,
        CancellationToken cancellation
    ) {
        return immediate(cancellation, () -> {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(sink, "sink");
            return new CaptureResult(renderedFrames.get(), Map.of());
        });
    }

    public void pauseExecution() {
        synchronized (frameGate) {
            if (!closed) paused = true;
        }
    }

    public void resumeExecution() {
        synchronized (frameGate) {
            paused = false;
            frameGate.notifyAll();
        }
    }

    @Override
    public void close() {
        synchronized (frameGate) {
            closed = true;
            paused = false;
            frameGate.notifyAll();
        }
    }

    private void awaitUnpaused(CancellationToken cancellation) throws InterruptedException {
        synchronized (frameGate) {
            while (paused && !closed && !cancellation.isCancellationRequested()) frameGate.wait(1);
            checkActive(cancellation);
        }
    }

    private void checkActive(CancellationToken cancellation) {
        cancellation.throwIfCancellationRequested();
        if (closed) throw new CancellationException("Vibris fake runtime was closed");
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Vibris fake runtime is closed");
    }

    private <T> CompletionStage<T> immediate(Supplier<T> operation) {
        try {
            ensureOpen();
            return CompletableFuture.completedFuture(operation.get());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private <T> CompletionStage<T> immediate(CancellationToken cancellation, Supplier<T> operation) {
        return immediate(() -> {
            Objects.requireNonNull(cancellation, "cancellation").throwIfCancellationRequested();
            return operation.get();
        });
    }
}