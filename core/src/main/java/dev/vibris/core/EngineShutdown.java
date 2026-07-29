package dev.vibris.core;

import dev.vibris.api.VibrisRuntimeAdapter;

import java.util.concurrent.ScheduledExecutorService;

final class EngineShutdown {
    private EngineShutdown() {
    }

    static void close(
        VibrisRuntimeAdapter runtime,
        ScheduledExecutorService disconnectTimer,
        FairJobScheduler scheduler,
        SourceActivator activator
    ) {
        RuntimeException failure = null;
        try {
            runtime.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        disconnectTimer.shutdownNow();
        try {
            scheduler.close();
        } catch (RuntimeException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        } finally {
            activator.close();
        }
        if (failure != null) throw failure;
    }
}