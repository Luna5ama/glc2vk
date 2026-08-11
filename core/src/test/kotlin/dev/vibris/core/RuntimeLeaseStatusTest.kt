package dev.vibris.core

import dev.vibris.api.ReloadResult
import dev.vibris.api.RuntimeStatus
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ActionSequence
import dev.vibris.protocol.v2.ActivateSource
import dev.vibris.protocol.v2.ClientMessage
import dev.vibris.protocol.v2.JobSpec
import dev.vibris.protocol.v2.JobStage
import dev.vibris.protocol.v2.PreparedSourceRef
import dev.vibris.protocol.v2.SceneContext
import dev.vibris.protocol.v2.ServerMessage
import dev.vibris.protocol.v2.SourceOrigin
import dev.vibris.protocol.v2.StatusDetail
import dev.vibris.protocol.v2.StatusWaitCondition
import dev.vibris.protocol.v2.SubmitJob
import dev.vibris.protocol.v2.WorkspaceOrigin
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuntimeLeaseStatusTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun cancellationRetainsLeaseUntilSafePointAndWakesStatusWaiter() {
        val runtime = RuntimeTestAdapter()
        val pending = temp.resolve("pending").toAbsolutePath()
        Files.createDirectories(pending)
        val engine = VibrisCoreEngine(pending, runtime)
        val descriptor = ServerDescriptor(pending, ArtifactManager(temp.resolve("artifacts")), runtime)
        descriptor.status(engine)
        val reload = CompletableFuture<ReloadResult>()
        runtime.reloadStages.add(reload)
        val compiling = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val session = session(compiling, terminal)

        engine.submit(session, job("cancel-safe", source(pending)))
        assertTrue(compiling.await(2, TimeUnit.SECONDS))
        assertTrue(engine.awaitStatus(
            StatusWaitCondition.STATUS_WAIT_CONDITION_CAN_START_JOB,
            "",
            0,
        ).timedOut)

        engine.cancel(session, "cancel-safe")
        val cancelling = descriptor.status(engine, StatusDetail.STATUS_DETAIL_FULL)
        assertTrue(cancelling.hasActiveLease())
        assertTrue(cancelling.activeLease.cancellationRequested)
        assertFalse(cancelling.canStartJob)

        val waiter = CompletableFuture.supplyAsync {
            engine.awaitStatus(StatusWaitCondition.STATUS_WAIT_CONDITION_CAN_START_JOB, "", 2_000)
        }
        val terminalWaiter = CompletableFuture.supplyAsync {
            engine.awaitStatus(StatusWaitCondition.STATUS_WAIT_CONDITION_JOB_TERMINAL, "cancel-safe", 2_000)
        }
        reload.complete(ReloadResult.success(emptyList()))
        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        val result = waiter.get(2, TimeUnit.SECONDS)
        assertTrue(result.satisfied)
        assertFalse(result.timedOut)
        val terminalResult = terminalWaiter.get(2, TimeUnit.SECONDS)
        assertTrue(terminalResult.satisfied)
        assertFalse(terminalResult.timedOut)
        assertFalse(descriptor.status(engine).hasActiveLease())
        engine.close()
    }

    @Test
    fun transitionHistoryIsBoundedToNewestThirtyTwoRecords() {
        val engine = VibrisCoreEngine(temp.resolve("transition-pending"), RuntimeTestAdapter())
        repeat(40) { index ->
            engine.observeRuntimeStatus(
                RuntimeStatus(index % 2 == 0, "save", "minecraft:overworld", ""),
                if (index % 2 == 0) "" else "fixture unavailable",
            )
        }

        val transitions = engine.statusSnapshot().transitions
        assertTrue(transitions.size == 32)
        assertTrue(transitions.first().sequence > 1)
        assertTrue(transitions.last().sequence > transitions.first().sequence)
        engine.close()
    }

    private fun session(compiling: CountDownLatch, terminal: CountDownLatch): ControlSession {
        val session = ControlSession(object : StreamObserver<ServerMessage> {
            override fun onNext(message: ServerMessage) {
                if (message.hasJobProgress() && message.jobProgress.stage == JobStage.JOB_STAGE_COMPILING) {
                    compiling.countDown()
                }
                if (message.hasJobCompleted() || message.hasJobFailed()) terminal.countDown()
            }

            override fun onError(throwable: Throwable) = throw AssertionError(throwable)

            override fun onCompleted() = Unit
        })
        session.identify(WORKSPACE_ID, "process")
        return session
    }

    private fun source(pending: Path): PreparedSourceRef {
        val uuid = UUID.randomUUID().toString()
        val source = Files.createDirectory(pending.resolve(uuid))
        val file = Files.writeString(source.resolve("main.glsl"), "fixture")
        return PreparedSourceRef.newBuilder()
            .setSourceUuid(uuid)
            .setRequestedRevision("workspace")
            .setResolvedRevision("a".repeat(40))
            .setOrigin(SourceOrigin.newBuilder().setWorkspace(
                WorkspaceOrigin.newBuilder().setDisplayName("fixture").setWorktreeRoot(pending.toString()),
            ))
            .setFileCount(1)
            .setTotalBytes(Files.size(file))
            .build()
    }

    private fun job(id: String, source: PreparedSourceRef): ClientMessage {
        val spec = JobSpec.newBuilder()
            .setJobId(id)
            .setContext(SceneContext.newBuilder().setSaveId("save")
                .setDimensionId("minecraft:overworld").setFov(70.0))
            .addSources(source)
            .setActionSequence(ActionSequence.newBuilder().addActions(Action.newBuilder().setActivateSource(
                ActivateSource.newBuilder().setSourceUuid(source.sourceUuid),
            )))
            .build()
        return ClientMessage.newBuilder()
            .setProtocolVersion(ProtocolMessages.V2)
            .setMessageId("message-$id")
            .setRequestId(id)
            .setWorkspaceId(WORKSPACE_ID)
            .setSubmitJob(SubmitJob.newBuilder().setJob(spec))
            .build()
    }

    private companion object {
        const val WORKSPACE_ID = "11111111-1111-4111-8111-111111111111"
    }
}
