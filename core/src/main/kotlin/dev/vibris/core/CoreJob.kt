package dev.vibris.core

import dev.vibris.api.CancellationToken
import dev.vibris.protocol.v2.JobSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture

internal class CoreJob(
    @JvmField val submission: JobSpec,
    @JvmField val requestId: String,
    @JvmField val workspaceId: String,
    @JvmField val messageId: String,
    @Volatile @JvmField var session: ControlSession?,
) {
    @JvmField
    val acceptedNanos: Long = System.nanoTime()

    @JvmField
    val acceptedAtUnixMs: Long = System.currentTimeMillis()

    @JvmField
    val cancellation: CancellationToken.Source = CancellationToken.source()

    private val ready = CountDownLatch(1)

    @Volatile
    @JvmField
    var sources: List<SourceRegistry.Lease> = emptyList()

    @Volatile
    @JvmField
    var disconnectCancellation: ScheduledFuture<*>? = null

    fun initialize(acceptedSources: List<SourceRegistry.Lease>) {
        sources = java.util.List.copyOf(acceptedSources)
        ready.countDown()
    }

    @Throws(InterruptedException::class)
    fun awaitReady() {
        ready.await()
    }

    @Synchronized
    fun bind(replacement: ControlSession?) {
        session = replacement
        disconnectCancellation?.cancel(false)
        disconnectCancellation = null
    }

    @Synchronized
    fun scheduleDisconnectCancellation(future: ScheduledFuture<*>?) {
        disconnectCancellation?.cancel(false)
        disconnectCancellation = future
    }

    @Synchronized
    fun stillOwnedBy(candidate: ControlSession?): Boolean = session === candidate
}
