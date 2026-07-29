package dev.vibris.core

import dev.vibris.api.VibrisRuntimeAdapter
import java.util.concurrent.ScheduledExecutorService

internal object EngineShutdown {
    @JvmStatic
    fun close(
        runtime: VibrisRuntimeAdapter,
        disconnectTimer: ScheduledExecutorService,
        scheduler: FairJobScheduler,
        activator: SourceActivator,
    ) {
        var failure: RuntimeException? = null
        try {
            runtime.close()
        } catch (exception: RuntimeException) {
            failure = exception
        }
        disconnectTimer.shutdownNow()
        try {
            scheduler.close()
        } catch (exception: RuntimeException) {
            if (failure == null) {
                failure = exception
            } else {
                failure.addSuppressed(exception)
            }
        } finally {
            activator.close()
        }
        if (failure != null) {
            throw failure
        }
    }
}