package dev.vibris.core

import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ContextApplyResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.SceneContext
import dev.vibris.api.TemporalResetResult
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.ArtifactMetadata
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobCompleted
import dev.vibris.protocol.v2.JobResult
import dev.vibris.protocol.v2.JobStage
import dev.vibris.protocol.v2.RestorationReceipt
import java.io.IOException
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer

internal class RuntimeJobExecutor @JvmOverloads constructor(
    runtime: VibrisRuntimeAdapter?,
    private val probe: CoreProbe,
    private val activator: SourceActivator,
    private val shaderLogs: ShaderLogSink,
    maxActions: Int = ServerConfiguration.DEFAULT_MAX_ACTIONS_PER_JOB,
) {
    private val runtime: VibrisRuntimeAdapter = requireNotNull(runtime) { "runtime" }
    private val captures = CaptureJobExecutor(shaderLogs as? ArtifactManager, maxActions)
    private val awaiter = RuntimeAwaiter(probe)
    private val actions = ActionJobExecutor(this.runtime, probe, captures, this)
    @Volatile
    private var activeContext: SceneContext? = null

    @Volatile
    private var activeShaderSettings: Map<String, String>? = null

    @Volatile
    private var pendingRecovery: PendingRecovery? = null

    @Throws(Failure::class)
    fun execute(job: CoreJob, progress: Consumer<JobStage>): TerminalResult {
        val startedAtUnixMs = System.currentTimeMillis()
        val startedNanos = System.nanoTime()
        val deadline = RuntimeJobContext.deadline(job)
        if (job.submission.hasRecoverRuntime()) {
            return executeRecovery(job, progress, startedAtUnixMs, startedNanos)
        }
        val isolation = BenchmarkCaseIsolation.begin(
            job,
            activator,
            activeShaderSettings,
            activeContext,
        )
        try {
            var completed = actions.execute(job, progress, deadline)
            val restoration = terminalize(job, isolation, true, progress)
            completed = completed.toBuilder().setRestoration(restoration).build()
            isolation.release(activator)
            return completed(job, completed, startedAtUnixMs, startedNanos)
        } catch (failure: Failure) {
            if (failure.holdOwnership) throw failure
            val restored = try {
                terminalize(job, isolation, false, progress)
            } catch (restoreFailure: Failure) {
                restoreFailure.addSuppressed(failure)
                throw restoreFailure
            }
            isolation.release(activator)
            throw failure.withRestoration(restored)
        } catch (failure: RuntimeException) {
            val wrapped = Failure(ErrorCode.ERROR_CODE_INTERNAL, failure.message ?: "Runtime job failed.")
            val restored = try {
                terminalize(job, isolation, false, progress)
            } catch (restoreFailure: Failure) {
                restoreFailure.addSuppressed(failure)
                throw restoreFailure
            }
            isolation.release(activator)
            throw wrapped.withRestoration(restored)
        }
    }

    private fun completed(
        job: CoreJob,
        result: JobResult,
        startedAtUnixMs: Long,
        startedNanos: Long,
    ): TerminalResult = TerminalResult.completed(
        JobCompleted.newBuilder()
            .setJobId(job.submission.jobId)
            .setRequestId(job.requestId)
            .setResult(awaiter.withTimings(job, result, startedAtUnixMs, startedNanos))
            .build(),
    )

    @Throws(Failure::class)
    fun applyContext(job: CoreJob, progress: Consumer<JobStage>, deadline: Long): ContextApplyResult {
        val cancellation = job.cancellation.token()
        progress.accept(JobStage.JOB_STAGE_LOADING_WORLD)
        probe.event(job.requestId, "ENSURING_WORLD")
        progress.accept(JobStage.JOB_STAGE_APPLYING_CONTEXT)
        val context: ContextApplyResult = await(
            runtime.ensureWorldAndContext(RuntimeJobContext.toApi(job.submission.context), cancellation),
            job,
            deadline,
        )
        if (!context.successful) {
            throw Failure(ErrorCode.ERROR_CODE_WORLD_LOAD_FAILED, context.message)
        }
        activeContext = context.context
        probe.contextApplied(job.requestId, job.workspaceId, RuntimeJobContext.toProtocol(context.context))
        return context
    }

    @Throws(Failure::class)
    fun reset(job: CoreJob, progress: Consumer<JobStage>, deadline: Long) {
        progress.accept(JobStage.JOB_STAGE_RESETTING_TEMPORAL_STATE)
        probe.event(job.requestId, "RESETTING_TEMPORAL_STATE")
        val reset: TemporalResetResult = await(runtime.resetTemporalState(job.cancellation.token()), job, deadline)
        if (!reset.successful) {
            throw Failure(ErrorCode.ERROR_CODE_INTERNAL, "Runtime temporal state reset failed.")
        }
    }

    @Throws(Failure::class)
    fun waitFrames(
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        frames: Int,
    ) {
        progress.accept(JobStage.JOB_STAGE_WARMING_UP)
        probe.event(job.requestId, "WARMING_UP")
        await(runtime.waitRenderedFrames(frames, job.cancellation.token()), job, deadline)
    }

    fun runtime(): VibrisRuntimeAdapter = runtime

    fun probe(): CoreProbe = probe

    @Throws(Failure::class)
    fun activateSource(
        job: CoreJob,
        source: SourceRegistry.Lease,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): ReloadResult = activateSource(
        job,
        source,
        null,
        progress,
        deadline,
    )

    @Throws(Failure::class)
    fun loadShader(
        job: CoreJob,
        source: SourceRegistry.Lease,
        config: dev.vibris.protocol.v2.ShaderConfig,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): LoadResult {
        val settings = if (config.preserveCurrent) null else config.valuesMap
        val reload = if (activator.isActive(source)) {
            reloadActiveSource(job, source, settings, progress, deadline)
        } else {
            activateSource(job, source, settings, progress, deadline)
        }
        val context = applyContext(job, progress, deadline)
        reset(job, progress, deadline)
        return LoadResult(reload, context, settings)
    }

    @Throws(Failure::class)
    private fun activateSource(
        job: CoreJob,
        source: SourceRegistry.Lease,
        config: Map<String, String>?,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): ReloadResult {
        progress.accept(JobStage.JOB_STAGE_ACTIVATING_SOURCE)
        probe.event(job.requestId, "ACTIVATING_SOURCE")
        val activation = try {
            activator.begin(source)
        } catch (failure: SourceActivator.Failure) {
            throw Failure(failure.code, failure.message)
        }
        var original: Failure? = null
        var successful: ReloadResult? = null
        var activeStatePreserved = false
        try {
            val reload = reload(job, config, progress, deadline)
            if (!reload.successful) {
                activeStatePreserved = reload.activeStatePreserved
                throw ShaderReloadFailure.create(shaderLogs, job, reload)
            }
            successful = reload
            try {
                activator.commit(activation)
            } catch (failure: SourceActivator.Failure) {
                throw Failure(failure.code, failure.message)
            }
        } catch (failure: Failure) {
            original = failure
        }
        if (original == null) {
            return successful!!
        }
        val restored = activator.rollback(activation)
        if (restored && activation.previous() != null && !activeStatePreserved && !reloadPreviousSource()) {
            activator.markNotReady()
        }
        if (!restored) {
            activator.fail(activation)
        }
        throw original
    }

    @Throws(Failure::class)
    private fun reloadActiveSource(
        job: CoreJob,
        source: SourceRegistry.Lease,
        config: Map<String, String>?,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): ReloadResult {
        val reload = reload(job, config, progress, deadline)
        if (!reload.successful) {
            if (!reload.activeStatePreserved && !reloadPreviousSource()) {
                activator.markNotReady()
            }
            throw ShaderReloadFailure.create(shaderLogs, job, reload)
        }
        return reload
    }

    @Throws(Failure::class)
    private fun reload(
        job: CoreJob,
        config: Map<String, String>?,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): ReloadResult {
        progress.accept(JobStage.JOB_STAGE_COMPILING)
        probe.event(job.requestId, "RELOADING_SHADERS")
        val result = await(runtime.reloadVibrisShaderpack(config, job.cancellation.token()), job, deadline)
        if (result.successful && config != null) activeShaderSettings = config.toMap()
        return result
    }

    @Throws(Failure::class)
    fun capture(
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        prepared: CaptureJobExecutor.Prepared,
        plan: CapturePlan,
    ): CaptureResult {
        progress.accept(JobStage.JOB_STAGE_CAPTURING)
        probe.event(job.requestId, "CAPTURING")
        val checkpoint = prepared.checkpoint()
        try {
            return awaitCapture(runtime.capture(plan, prepared.sink(), job.cancellation.token()), job, deadline)
        } catch (failure: Failure) {
            try {
                prepared.rollback(checkpoint)
            } catch (rollbackFailure: IOException) {
                rollbackFailure.addSuppressed(failure)
                throw CaptureJobExecutor.failure(rollbackFailure)
            }
            throw failure
        }
    }

    @Throws(Failure::class)
    fun capturePatchedShaders(
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        prepared: CaptureJobExecutor.Prepared,
        artifactName: String,
    ): CaptureResult {
        progress.accept(JobStage.JOB_STAGE_CAPTURING)
        probe.event(job.requestId, "CAPTURING_PATCHED_SHADERS")
        val checkpoint = prepared.checkpoint()
        try {
            return awaitCapture(
                runtime.capturePatchedShaders(artifactName, prepared.sink(), job.cancellation.token()),
                job,
                deadline,
            )
        } catch (failure: Failure) {
            try {
                prepared.rollback(checkpoint)
            } catch (rollbackFailure: IOException) {
                rollbackFailure.addSuppressed(failure)
                throw CaptureJobExecutor.failure(rollbackFailure)
            }
            throw failure
        }
    }

    @Throws(Failure::class)
    fun awaitCapture(stage: CompletionStage<CaptureResult>, job: CoreJob, deadline: Long): CaptureResult =
        awaiter.capture(stage, job, deadline)

    private fun reloadPreviousSource(): Boolean {
        try {
            val result = runtime.reloadVibrisShaderpack(null, CancellationToken.none())
                .toCompletableFuture()
                .join()
            return result.successful
        } catch (_: Exception) {
            return false
        }
    }

    @Synchronized
    fun hasPendingRecovery(): Boolean = pendingRecovery != null

    @Synchronized
    fun restorationReceipt(): RestorationReceipt {
        pendingRecovery?.let { return it.lastReceipt }
        val actual = runCatching(::currentSnapshot).getOrElse {
            BenchmarkCaseIsolation.Snapshot(null, activeShaderSettings, activeContext)
        }
        return BenchmarkCaseIsolation.noMutationReceipt(actual)
    }

    private fun terminalize(
        job: CoreJob,
        isolation: BenchmarkCaseIsolation,
        successful: Boolean,
        progress: Consumer<JobStage>,
    ): RestorationReceipt {
        if (!isolation.shouldRestore(successful)) {
            if (successful) {
                try {
                    activator.verifyActiveSource()
                    return isolation.currentReceipt(currentSnapshot())
                } catch (failure: SourceActivator.Failure) {
                    throw Failure(failure.code, failure.message)
                }
            }
            val actual = runCatching(::currentSnapshot).getOrElse {
                BenchmarkCaseIsolation.Snapshot(null, activeShaderSettings, activeContext)
            }
            return isolation.currentReceipt(actual)
        }
        progress.accept(JobStage.JOB_STAGE_RESTORING)
        probe.event(job.requestId, "RESTORING_RUNTIME_STATE")
        return try {
            val actual = restore(isolation.snapshot)
            isolation.successReceipt(actual, true)
        } catch (failure: Exception) {
            activator.markNotReady()
            val message = failure.message ?: "The last safe runtime snapshot could not be restored."
            val actual = runCatching(::currentSnapshot).getOrElse {
                BenchmarkCaseIsolation.Snapshot(null, activeShaderSettings, activeContext)
            }
            val receipt = isolation.failureReceipt(
                actual,
                ErrorCode.ERROR_CODE_RESTORE_FAILED,
                message,
                false,
            )
            pendingRecovery = PendingRecovery(isolation, job.sources.toList(), receipt)
            throw Failure(
                ErrorCode.ERROR_CODE_RESTORE_FAILED,
                "$message ${BenchmarkCaseIsolation.MANUAL_RECOVERY}",
                restoration = receipt,
                holdOwnership = true,
            )
        }
    }

    private fun executeRecovery(
        job: CoreJob,
        progress: Consumer<JobStage>,
        startedAtUnixMs: Long,
        startedNanos: Long,
    ): TerminalResult {
        progress.accept(JobStage.JOB_STAGE_RECOVERING)
        probe.event(job.requestId, "RECOVERING_RUNTIME_STATE")
        val recovery = pendingRecovery
        if (recovery == null) {
            try {
                activator.markReadyAfterVerification()
                return completed(
                    job,
                    JobResult.newBuilder()
                        .setRestoration(BenchmarkCaseIsolation.noMutationReceipt(currentSnapshot()))
                        .build(),
                    startedAtUnixMs,
                    startedNanos,
                )
            } catch (failure: Exception) {
                val message = failure.message ?: "The runtime link could not be revalidated."
                val actual = runCatching(::currentSnapshot).getOrElse {
                    BenchmarkCaseIsolation.Snapshot(null, activeShaderSettings, activeContext)
                }
                throw Failure(
                    ErrorCode.ERROR_CODE_RECOVERY_FAILED,
                    "$message ${BenchmarkCaseIsolation.MANUAL_RECOVERY}",
                    restoration = BenchmarkCaseIsolation.recoveryFailureReceipt(actual, message),
                )
            }
        }
        try {
            val actual = restore(recovery.isolation.snapshot)
            activator.markReadyAfterVerification()
            val receipt = recovery.isolation.successReceipt(actual, true)
            recovery.isolation.release(activator)
            activator.release(recovery.heldSources)
            pendingRecovery = null
            return completed(
                job,
                JobResult.newBuilder().setRestoration(receipt).build(),
                startedAtUnixMs,
                startedNanos,
            )
        } catch (failure: Exception) {
            activator.markNotReady()
            val message = failure.message ?: "Runtime recovery could not verify the last safe snapshot."
            val actual = runCatching(::currentSnapshot).getOrElse {
                BenchmarkCaseIsolation.Snapshot(null, activeShaderSettings, activeContext)
            }
            val receipt = recovery.isolation.failureReceipt(
                actual,
                ErrorCode.ERROR_CODE_RECOVERY_FAILED,
                message,
                false,
            )
            recovery.lastReceipt = receipt
            throw Failure(
                ErrorCode.ERROR_CODE_RECOVERY_FAILED,
                "$message ${BenchmarkCaseIsolation.MANUAL_RECOVERY}",
                restoration = receipt,
                holdOwnership = true,
            )
        }
    }

    @Throws(Exception::class)
    private fun restore(expected: BenchmarkCaseIsolation.Snapshot): BenchmarkCaseIsolation.Snapshot {
        activator.restore(expected.source)
        if (expected.source != null) {
            val reload = restoreAwait(runtime.reloadVibrisShaderpack(expected.shaderSettings, CancellationToken.none()))
            check(reload.successful) { "The safe shader source or settings could not be reloaded." }
        }
        activeShaderSettings = expected.shaderSettings?.toMap()
        expected.scene?.let { scene ->
            val context = restoreAwait(runtime.ensureWorldAndContext(scene, CancellationToken.none()))
            check(context.successful && context.context == scene) { "The safe scene context could not be restored exactly." }
            activeContext = context.context
        }
        val reset = restoreAwait(runtime.resetTemporalState(CancellationToken.none()))
        check(reset.successful) { "Temporal state could not be reset after restoration." }
        activator.verifyActiveSource()
        val actual = currentSnapshot()
        check(expected.source?.uuid == actual.source?.uuid) { "Restored source UUID does not match the safe snapshot." }
        check(expected.source?.snapshotSha256 == actual.source?.snapshotSha256) {
            "Restored source content does not match the safe snapshot."
        }
        check(expected.shaderSettings == actual.shaderSettings) { "Restored shader settings do not match the safe snapshot." }
        check(expected.scene == actual.scene) { "Restored scene does not match the safe snapshot." }
        return actual
    }

    private fun currentSnapshot(): BenchmarkCaseIsolation.Snapshot = BenchmarkCaseIsolation.Snapshot(
        activator.activeSnapshot(),
        activeShaderSettings?.toMap(),
        activeContext,
    )

    @Throws(Exception::class)
    private fun <T> restoreAwait(stage: CompletionStage<T>): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RESTORE_TIMEOUT_SECONDS)
        var interrupted = false
        try {
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    throw IllegalStateException(
                        "Runtime restoration timed out after $RESTORE_TIMEOUT_SECONDS seconds.",
                    )
                }
                try {
                    return stage.toCompletableFuture().get(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (failure: TimeoutException) {
                    throw IllegalStateException(
                        "Runtime restoration timed out after $RESTORE_TIMEOUT_SECONDS seconds.",
                        failure,
                    )
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    @Throws(Failure::class)
    fun <T> await(stage: CompletionStage<T>, job: CoreJob, deadline: Long): T = awaiter.await(stage, job, deadline)

    data class LoadResult(
        val reload: ReloadResult,
        val context: ContextApplyResult,
        val effectiveShaderSettings: Map<String, String>?,
    )

    class Failure internal constructor(
        @JvmField val code: ErrorCode,
        message: String?,
        @JvmField val artifacts: List<ArtifactMetadata>,
        @JvmField val diagnostics: List<ReloadResult.Diagnostic>,
        @JvmField val restoration: RestorationReceipt?,
        @JvmField val holdOwnership: Boolean,
    ) : Exception(message) {
        constructor(code: ErrorCode, message: String?) :
            this(code, message, java.util.List.of(), java.util.List.of(), null, false)

        constructor(code: ErrorCode, message: String?, artifact: ArtifactMetadata) :
            this(code, message, java.util.List.of(artifact), java.util.List.of(), null, false)

        internal constructor(
            code: ErrorCode,
            message: String?,
            restoration: RestorationReceipt?,
            holdOwnership: Boolean = false,
        ) : this(code, message, java.util.List.of(), java.util.List.of(), restoration, holdOwnership)

        fun withRestoration(value: RestorationReceipt): Failure = Failure(
            code,
            message,
            artifacts,
            diagnostics,
            value,
            holdOwnership,
        ).also { replacement -> suppressed.forEach(replacement::addSuppressed) }
    }

    private data class PendingRecovery(
        val isolation: BenchmarkCaseIsolation,
        val heldSources: List<SourceRegistry.Lease>,
        @Volatile var lastReceipt: RestorationReceipt,
    )

    private companion object {
        const val RESTORE_TIMEOUT_SECONDS = 10L
    }

}
