package dev.vibris.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FairJobSchedulerTest {
    @Test
    fun exposesOneAuthoritativeLeaseAndFairCrossWorkspaceQueue() {
        val scheduler = FairJobScheduler(8)
        val execution = Collections.synchronizedList(ArrayList<String>())
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(3)

        assertTrue(scheduler.submit(metadata("a-1", "workspace-a"), Runnable {
            execution.add("a-1")
            firstStarted.countDown()
            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
            completed.countDown()
        }).accepted)
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        assertTrue(scheduler.submit(metadata("a-2", "workspace-a"), Runnable {
            execution.add("a-2")
            completed.countDown()
        }).accepted)
        assertTrue(scheduler.submit(metadata("b-1", "workspace-b"), Runnable {
            execution.add("b-1")
            completed.countDown()
        }).accepted)

        val snapshot = scheduler.snapshot()
        assertEquals("a-1", snapshot.active?.metadata?.jobId)
        assertEquals(listOf("b-1", "a-2"), snapshot.queued.map { it.metadata.jobId })
        assertEquals(listOf(1, 2), snapshot.queued.map { it.position })

        releaseFirst.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("a-1", "b-1", "a-2"), execution)
        scheduler.close()
    }

    private fun metadata(id: String, workspace: String) = FairJobScheduler.JobMetadata(
        id,
        workspace,
        id,
        "I:/fixture/$workspace",
        "action_sequence",
        System.currentTimeMillis(),
    )
}
