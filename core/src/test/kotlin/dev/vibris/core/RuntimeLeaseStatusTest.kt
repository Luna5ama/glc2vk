package dev.vibris.core

import dev.vibris.api.ReloadResult
import dev.vibris.api.RuntimeStatus
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ActionSequence
import dev.vibris.protocol.v2.ActivateSource
import dev.vibris.protocol.v2.ClientMessage
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobSpec
import dev.vibris.protocol.v2.JobStage
import dev.vibris.protocol.v2.LoadShader
import dev.vibris.protocol.v2.PreparedSourceRef
import dev.vibris.protocol.v2.ReceiptStatus
import dev.vibris.protocol.v2.RecoverRuntimeRequest
import dev.vibris.protocol.v2.RestorePolicy
import dev.vibris.protocol.v2.SceneContext
import dev.vibris.protocol.v2.ServerMessage
import dev.vibris.protocol.v2.ServerState
import dev.vibris.protocol.v2.ShaderConfig
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
import java.util.concurrent.CopyOnWriteArrayList
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

    @Test
    fun restoreFailureKeepsOriginalLeaseAndOnlyRecoveryCanMakeCoreReady() {
        val runtime = RuntimeTestAdapter()
        val pending = temp.resolve("recovery-pending").toAbsolutePath()
        Files.createDirectories(pending)
        val engine = VibrisCoreEngine(
            pending,
            runtime,
            RetainingLink,
            ShaderLogSink.none(),
        )
        val descriptor = ServerDescriptor(pending, ArtifactManager(temp.resolve("recovery-artifacts")), runtime)
        descriptor.status(engine)

        val baseline = source(pending)
        val baselineSession = recordingSession()
        engine.submit(baselineSession.session, loadJob("baseline", baseline, false))
        assertTrue(baselineSession.terminal.await(2, TimeUnit.SECONDS))

        val candidate = source(pending)
        runtime.reloads.add(ReloadResult.success(emptyList()))
        runtime.reloads.add(ReloadResult.failure(emptyList()))
        val candidateSession = recordingSession()
        engine.submit(candidateSession.session, loadJob("candidate", candidate, true))
        assertTrue(candidateSession.terminal.await(2, TimeUnit.SECONDS))
        val failed = candidateSession.messages.last { it.hasJobFailed() }.jobFailed
        assertTrue(failed.error.code == ErrorCode.ERROR_CODE_RESTORE_FAILED)
        assertTrue(failed.restoration.status == ReceiptStatus.RECEIPT_STATUS_FAILED)

        val recovering = descriptor.status(engine, StatusDetail.STATUS_DETAIL_FULL)
        assertTrue(recovering.state == ServerState.SERVER_STATE_RECOVERING)
        assertTrue(recovering.hasActiveLease())
        assertTrue(recovering.activeLease.jobId == "candidate")
        assertFalse(recovering.canAcceptJob)

        val rejectedSession = recordingSession()
        engine.submit(rejectedSession.session, loadJob("must-be-rejected", source(pending), false))
        val rejected = rejectedSession.messages.last { it.hasJobFailed() }.jobFailed
        assertTrue(rejected.error.code == ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE)

        runtime.reloads.add(ReloadResult.success(emptyList()))
        val recoverySession = recordingSession()
        engine.submit(recoverySession.session, recoveryJob("recover"))
        assertTrue(recoverySession.terminal.await(2, TimeUnit.SECONDS))
        val completed = recoverySession.messages.last { it.hasJobCompleted() }.jobCompleted
        assertTrue(completed.result.restoration.status == ReceiptStatus.RECEIPT_STATUS_OK)
        val ready = engine.awaitStatus(StatusWaitCondition.STATUS_WAIT_CONDITION_CAN_START_JOB, "", 2_000)
        assertTrue(ready.satisfied)
        assertFalse(descriptor.status(engine).hasActiveLease())
        assertTrue(engine.ready())
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

    private fun loadJob(id: String, source: PreparedSourceRef, restore: Boolean): ClientMessage {
        val spec = JobSpec.newBuilder()
            .setJobId(id)
            .setContext(SceneContext.newBuilder().setSaveId("save")
                .setDimensionId("minecraft:overworld").setFov(70.0))
            .addSources(source)
            .setRestoreState(RestorePolicy.newBuilder().setOnSuccess(restore).setOnError(restore))
            .setActionSequence(ActionSequence.newBuilder().addActions(Action.newBuilder().setLoadShader(
                LoadShader.newBuilder()
                    .setSourceUuid(source.sourceUuid)
                    .setSourceId(id)
                    .setConfigId(id)
                    .setConfig(ShaderConfig.newBuilder().putValues("QUALITY", id)),
            )))
            .build()
        return submit(id, spec)
    }

    private fun recoveryJob(id: String): ClientMessage = submit(
        id,
        JobSpec.newBuilder()
            .setJobId(id)
            .setRecoverRuntime(RecoverRuntimeRequest.getDefaultInstance())
            .build(),
    )

    private fun submit(id: String, spec: JobSpec): ClientMessage = ClientMessage.newBuilder()
        .setProtocolVersion(ProtocolMessages.V2)
        .setMessageId("message-$id")
        .setRequestId(id)
        .setWorkspaceId(WORKSPACE_ID)
        .setSubmitJob(SubmitJob.newBuilder().setJob(spec))
        .build()

    private fun recordingSession(): SessionFixture {
        val messages = CopyOnWriteArrayList<ServerMessage>()
        val terminal = CountDownLatch(1)
        val session = ControlSession(object : StreamObserver<ServerMessage> {
            override fun onNext(message: ServerMessage) {
                messages.add(message)
                if (message.hasJobCompleted() || message.hasJobFailed()) terminal.countDown()
            }

            override fun onError(throwable: Throwable) = throw AssertionError(throwable)

            override fun onCompleted() = Unit
        })
        session.identify(WORKSPACE_ID, "process")
        return SessionFixture(session, messages, terminal)
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

        object RetainingLink : ShaderLink {
            override fun switchTo(source: SourceRegistry.Lease, ownership: ShaderLink.OwnershipCheck) =
                ownership.verify()

            override fun detach() = Unit

            override fun retainsActiveSource(): Boolean = true
        }
    }

    private data class SessionFixture(
        val session: ControlSession,
        val messages: List<ServerMessage>,
        val terminal: CountDownLatch,
    )
}
