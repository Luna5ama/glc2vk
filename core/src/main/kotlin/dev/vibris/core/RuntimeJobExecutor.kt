package dev.vibris.core

import dev.vibris.api.CancellationToken
import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.ContextApplyResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.TemporalResetResult
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.ArtifactMetadata
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobCompleted
import dev.vibris.protocol.v2.JobStage
import java.io.IOException
import java.util.concurrent.CompletionStage
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

    @Throws(Failure::class)
    fun execute(job: CoreJob, progress: Consumer<JobStage>): TerminalResult {
        val startedAtUnixMs = System.currentTimeMillis()
        val startedNanos = System.nanoTime()
        val deadline = RuntimeJobContext.deadline(job)
        val completed = awaiter.withTimings(
            job,
            actions.execute(job, progress, deadline),
            startedAtUnixMs,
            startedNanos,
        )
        try {
            activator.verifyActiveSource()
        } catch (failure: SourceActivator.Failure) {
            throw Failure(failure.code, failure.message)
        }
        return TerminalResult.completed(
            JobCompleted.newBuilder()
                .setJobId(job.submission.jobId)
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
            throw Failure(ErrorCode.ERROR_CODE_WORLD_LOAD_FAILED, context.message)
        }
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
        return await(runtime.reloadVibrisShaderpack(config, job.cancellation.token()), job, deadline)
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

}
