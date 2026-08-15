package dev.vibris.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FairJobSchedulerTest {
    @Test
    fun keepsAWorkflowTurnWithoutHidingTheAuthoritativeLease() {
        val scheduler = FairJobScheduler(8, continuationGraceMs = 25)
        val execution = Collections.synchronizedList(ArrayList<String>())
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(3)

        assertTrue(scheduler.submit(metadata("a-1", "workspace-a", "workflow-a"), Runnable {
            execution.add("a-1")
            firstStarted.countDown()
            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
            completed.countDown()
        }).accepted)
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        assertTrue(scheduler.submit(metadata("a-2", "workspace-a", "workflow-a"), Runnable {
            execution.add("a-2")
            completed.countDown()
        }).accepted)
        assertTrue(scheduler.submit(metadata("b-1", "workspace-b", "workflow-b"), Runnable {
            execution.add("b-1")
            completed.countDown()
        }).accepted)

        val snapshot = scheduler.snapshot()
        assertEquals("a-1", snapshot.active?.metadata?.jobId)
        assertEquals(listOf("a-2", "b-1"), snapshot.queued.map { it.metadata.jobId })
        assertEquals(listOf(1, 2), snapshot.queued.map { it.position })

        releaseFirst.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("a-1", "a-2", "b-1"), execution)
        scheduler.close()
    }

    @Test
    fun groupQuantumForcesRoundRobinBeforeAWorkflowCanStarvePeers() {
        val scheduler = FairJobScheduler(
            capacity = 12,
            maxConsecutiveGroupJobs = 3,
            continuationGraceMs = 0,
        )
        val execution = Collections.synchronizedList(ArrayList<String>())
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(7)
        val record = { id: String -> Runnable { execution.add(id); completed.countDown() } }

        assertTrue(scheduler.submit(metadata("a-1", "workspace-a", "workflow-a"), Runnable {
            execution.add("a-1")
            firstStarted.countDown()
            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
            completed.countDown()
        }).accepted)
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        for (index in 2..5) {
            assertTrue(scheduler.submit(
                metadata("a-$index", "workspace-a", "workflow-a"),
                record("a-$index"),
            ).accepted)
        }
        assertTrue(scheduler.submit(metadata("b-1", "workspace-b", "workflow-b"), record("b-1")).accepted)
        assertTrue(scheduler.submit(metadata("c-1", "workspace-c", "workflow-c"), record("c-1")).accepted)

        assertEquals(
            listOf("a-2", "a-3", "b-1", "c-1", "a-4", "a-5"),
            scheduler.snapshot().queued.map { it.metadata.jobId },
        )
        releaseFirst.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(
            listOf("a-1", "a-2", "a-3", "b-1", "c-1", "a-4", "a-5"),
            execution,
        )
        scheduler.close()
    }

    @Test
    fun lateWorkflowContinuationUsesTheBoundedGraceWindow() {
        val scheduler = FairJobScheduler(
            capacity = 8,
            maxConsecutiveGroupJobs = 2,
            continuationGraceMs = 2_000,
        )
        val execution = Collections.synchronizedList(ArrayList<String>())
        val firstBodyFinished = CountDownLatch(1)
        val completed = CountDownLatch(3)
        val bStarted = CountDownLatch(1)

        assertTrue(scheduler.submit(metadata("a-1", "workspace-a", "workflow-a"), Runnable {
            execution.add("a-1")
            firstBodyFinished.countDown()
            completed.countDown()
        }).accepted)
        assertTrue(scheduler.submit(metadata("b-1", "workspace-b", "workflow-b"), Runnable {
            execution.add("b-1")
            bStarted.countDown()
            completed.countDown()
        }).accepted)
        assertTrue(firstBodyFinished.await(2, TimeUnit.SECONDS))
        for (attempt in 0 until 500) {
            if (scheduler.snapshot().active == null) break
            Thread.sleep(1)
        }
        assertFalse(bStarted.await(10, TimeUnit.MILLISECONDS))
        assertTrue(scheduler.submit(metadata("a-2", "workspace-a", "workflow-a"), Runnable {
            execution.add("a-2")
            completed.countDown()
        }).accepted)

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("a-1", "a-2", "b-1"), execution)
        scheduler.close()
    }

    @Test
    fun continuouslyReplenishedWorkflowsRemainBoundedAndAllComplete() {
        val scheduler = FairJobScheduler(
            capacity = 16,
            maxConsecutiveGroupJobs = 4,
            continuationGraceMs = 0,
        )
        val execution = Collections.synchronizedList(ArrayList<String>())
        val jobsPerWorkspace = 12
        val completed = CountDownLatch(jobsPerWorkspace * 3)
        lateinit var submitNext: (String, Int) -> Unit
        submitNext = { workspace, index ->
            val id = "$workspace-$index"
            assertTrue(scheduler.submit(metadata(id, workspace, "workflow-$workspace"), Runnable {
                execution.add(workspace)
                if (index < jobsPerWorkspace) submitNext(workspace, index + 1)
                completed.countDown()
            }).accepted)
        }

        listOf("workspace-a", "workspace-b", "workspace-c").forEach { submitNext(it, 1) }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(jobsPerWorkspace, execution.count { it == "workspace-a" })
        assertEquals(jobsPerWorkspace, execution.count { it == "workspace-b" })
        assertEquals(jobsPerWorkspace, execution.count { it == "workspace-c" })
        var longestRun = 0
        var currentRun = 0
        var previous = ""
        execution.forEach { workspace ->
            currentRun = if (workspace == previous) currentRun + 1 else 1
            longestRun = maxOf(longestRun, currentRun)
            previous = workspace
        }
        assertTrue(longestRun <= 4, "a replenishing workflow exceeded its bounded scheduler quantum")
        scheduler.close()
    }

    private fun metadata(id: String, workspace: String, group: String = "") = FairJobScheduler.JobMetadata(
        id,
        workspace,
        id,
        "I:/fixture/$workspace",
        "action_sequence",
        group,
        System.currentTimeMillis(),
    )
}
