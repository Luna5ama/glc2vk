package dev.vibris.core

import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ContextApplyResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.SceneContext
import dev.vibris.api.TemporalResetResult
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v1.ArtifactMetadata
import dev.vibris.protocol.v1.ErrorCode
import dev.vibris.protocol.v1.JobCompleted
import dev.vibris.protocol.v1.JobStage
import java.io.IOException
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
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
    private var activeContext: SceneContext? = null
    private var activeShaderSettings: Map<String, String>? = null

    @Throws(Failure::class)
    fun execute(job: CoreJob, progress: Consumer<JobStage>): TerminalResult {
        val startedAtUnixMs = System.currentTimeMillis()
        val startedNanos = System.nanoTime()
        val deadline = RuntimeJobContext.deadline(job)
        val isolation = BenchmarkCaseIsolation.begin(job, activator, activeContext, activeShaderSettings)
        val result = try {
            actions.execute(job, progress, deadline, isolation).also {
                restoreBenchmarkCase(job, isolation)
                isolation?.requireComplete()
            }
        } catch (failure: Failure) {
            try {
                restoreBenchmarkCase(job, isolation)
            } catch (restoreFailure: Failure) {
                restoreFailure.addSuppressed(failure)
                throw restoreFailure
            }
            throw failure
        } catch (exception: Exception) {
            try {
                restoreBenchmarkCase(job, isolation)
            } catch (restoreFailure: Failure) {
                restoreFailure.addSuppressed(exception)
                throw restoreFailure
            }
            throw exception
        } finally {
            isolation?.let { activator.releaseRetained(it.baselineSource) }
        }
        var completed = result
        if (isolation != null) {
            completed = completed.toBuilder()
                .addAllBenchmarkBarriers(isolation.receipts())
                .build()
        }
        completed = awaiter.withTimings(job, completed, startedAtUnixMs, startedNanos)
        try {
            activator.verifyActiveSource()
        } catch (failure: SourceActivator.Failure) {
            throw Failure(failure.code, failure.message)
        }
        return TerminalResult.completed(
            JobCompleted.newBuilder()
                .setRequestId(job.requestId)
                .setResult(completed)
                .build(),
        )
    }

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
            throw Failure(ErrorCode.WORLD_LOAD_FAILED, context.message)
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
            throw Failure(ErrorCode.INTERNAL_ERROR, "Runtime temporal state reset failed.")
        }
    }

    @Throws(Failure::class)
    fun waitFrames(
        job: CoreJob,
        progress: Consumer<JobStage>,
        deadline: Long,
        frames: Int,
        isolation: BenchmarkCaseIsolation? = null,
    ) {
        isolation?.warmupStarted()
        progress.accept(JobStage.JOB_STAGE_WARMING_UP)
        probe.event(job.requestId, "WARMING_UP")
        await(runtime.waitRenderedFrames(frames, job.cancellation.token()), job, deadline)
        isolation?.warmupCompleted()
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
        if (job.submission.hasShaderConfig()) job.submission.shaderConfig.valuesMap else null,
        progress,
        deadline,
        null,
    )

    @Throws(Failure::class)
    fun loadShader(
        job: CoreJob,
        source: SourceRegistry.Lease,
        configId: String,
        progress: Consumer<JobStage>,
        deadline: Long,
        isolation: BenchmarkCaseIsolation? = null,
    ): LoadResult {
        val matches = job.submission.shaderConfigsList.filter { it.id == configId }
        if (matches.size != 1) {
            throw Failure(ErrorCode.NOT_CONFIGURED, "Load action references an unknown shader config.")
        }
        val named = matches.single()
        val config = if (named.preserve) isolation?.baselineShaderSettings else named.config.valuesMap
        val reload = if (activator.isActive(source)) {
            isolation?.sourcePublished(source.uuid)
            reloadActiveSource(job, source, config, progress, deadline, isolation)
        } else {
            activateSource(job, source, config, progress, deadline, isolation)
        }
        val context = applyContext(job, progress, deadline)
        reset(job, progress, deadline)
        return LoadResult(reload, context, config)
    }

    @Throws(Failure::class)
    private fun activateSource(
        job: CoreJob,
        source: SourceRegistry.Lease,
        config: Map<String, String>?,
        progress: Consumer<JobStage>,
        deadline: Long,
        isolation: BenchmarkCaseIsolation?,
    ): ReloadResult {
        progress.accept(JobStage.JOB_STAGE_ACTIVATING_SOURCE)
        probe.event(job.requestId, "ACTIVATING_SOURCE")
        val activation = try {
            activator.begin(source)
        } catch (failure: SourceActivator.Failure) {
            throw Failure(failure.code, failure.message)
        }
        isolation?.sourcePublished(source.uuid)
        var original: Failure? = null
        var successful: ReloadResult? = null
        var activeStatePreserved = false
        try {
            val reload = reload(job, source, config, progress, deadline, isolation)
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
        isolation: BenchmarkCaseIsolation?,
    ): ReloadResult {
        val reload = reload(job, source, config, progress, deadline, isolation)
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
        source: SourceRegistry.Lease,
        config: Map<String, String>?,
        progress: Consumer<JobStage>,
        deadline: Long,
        isolation: BenchmarkCaseIsolation?,
    ): ReloadResult {
        progress.accept(JobStage.JOB_STAGE_RELOADING_SHADERS)
        probe.event(job.requestId, "RELOADING_SHADERS")
        val reload = await(runtime.reloadVibrisShaderpack(config, job.cancellation.token()), job, deadline)
        if (reload.successful) {
            if (config != null) activeShaderSettings = java.util.Map.copyOf(config)
            if (isolation != null) {
                val explicit = config ?: throw Failure(
                    ErrorCode.BENCHMARK_BARRIER_FAILED,
                    "An isolated benchmark case attempted a preserve reload without an explicit snapshot.",
                )
                isolation.configApplied(source.uuid, explicit)
                isolation.shaderReloaded(source.uuid, explicit)
            }
        }
        return reload
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

    @Throws(Failure::class)
    fun restoreBenchmarkCase(job: CoreJob, isolation: BenchmarkCaseIsolation?) {
        if (isolation == null || isolation.restored()) return
        probe.event(job.requestId, "RESTORING_BENCHMARK_STATE")
        var activation: SourceActivator.Activation? = null
        var committed = false
        try {
            if (!activator.isActive(isolation.baselineSource)) {
                activation = activator.begin(isolation.baselineSource)
            }
            val reload = restoreAwait(
                runtime.reloadVibrisShaderpack(isolation.baselineShaderSettings, CancellationToken.none()),
            )
            if (!reload.successful) {
                throw IllegalStateException("The baseline shader source or config could not be reloaded.")
            }
            if (activation != null) {
                activator.commit(activation)
                committed = true
            }
            val context = restoreAwait(
                runtime.ensureWorldAndContext(isolation.baselineContext, CancellationToken.none()),
            )
            if (!context.successful) {
                throw IllegalStateException("The baseline scene context could not be restored.")
            }
            val reset = restoreAwait(runtime.resetTemporalState(CancellationToken.none()))
            if (!reset.successful) {
                throw IllegalStateException("Temporal state could not be reset after benchmark restoration.")
            }
            activeShaderSettings = isolation.baselineShaderSettings
            activeContext = context.context
            activator.verifyActiveSource()
            isolation.stateRestored()
        } catch (failure: Exception) {
            if (activation != null && !committed) {
                if (!activator.rollback(activation)) activator.fail(activation)
            }
            activator.markNotReady()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw Failure(
                ErrorCode.BENCHMARK_RESTORE_FAILED,
                failure.message ?: "The pre-matrix runtime state could not be restored.",
            )
        }
    }

    @Throws(Exception::class)
    private fun <T> restoreAwait(stage: CompletionStage<T>): T =
        stage.toCompletableFuture().get(RESTORE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun reloadPreviousSource(): Boolean {
        try {
            val result = runtime.reloadVibrisShaderpack(null, CancellationToken.none())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS)
            return result.successful
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (_: Exception) {
            return false
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
    ) : Exception(message) {
        constructor(code: ErrorCode, message: String?) :
            this(code, message, java.util.List.of(), java.util.List.of())

        constructor(code: ErrorCode, message: String?, artifact: ArtifactMetadata) :
            this(code, message, java.util.List.of(artifact), java.util.List.of())
    }

    private companion object {
        const val RESTORE_TIMEOUT_SECONDS = 10L
    }
}