package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResult
import dev.vibris.api.EffectiveShaderSettings
import dev.vibris.api.ReloadResult
import dev.vibris.api.RuntimeAction
import dev.vibris.api.SceneContext
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.EmptyReceipt
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobResult
import dev.vibris.protocol.v2.JobStage
import dev.vibris.protocol.v2.PatchedShadersReceipt
import dev.vibris.protocol.v2.ResetTemporalReceipt
import dev.vibris.protocol.v2.RuntimeMutationReceipt
import dev.vibris.protocol.v2.ShaderInspectionReceipt
import dev.vibris.protocol.v2.WaitFramesReceipt
import java.io.IOException
import java.util.LinkedHashSet
import java.util.function.Consumer

internal class ActionJobExecutor(
    private val runtime: VibrisRuntimeAdapter,
    private val probe: CoreProbe,
    private val captures: CaptureJobExecutor,
    private val owner: RuntimeJobExecutor,
) {
    @Throws(RuntimeJobExecutor.Failure::class)
    fun execute(job: CoreJob, progress: Consumer<JobStage>, deadline: Long): JobResult {
        val receiptBook = ActionReceiptBook(job.submission.actionSequence.actionsList)
        val pendingCaptureIndices = LinkedHashSet<Int>()
        var activeIndices: List<Int> = emptyList()
        try {
            val diagnostics = ArrayList<ReloadResult.Diagnostic>()
            var preloadedActionIndex: Int? = null
            val wireActions = job.submission.actionSequence.actionsList
            if (wireActions.size > 1 && wireActions.first().hasLoadShader()) {
                activeIndices = listOf(0)
                val execution = load(job, wireActions.first().loadShader, progress, deadline)
                diagnostics.addAll(execution.reload.diagnostics)
                owner.observeCatalog(
                    owner.await(runtime.getCompileCatalog(job.cancellation.token()), job, deadline),
                )
                receiptBook.put(
                    0,
                    receiptBook.success(0)
                        .setRuntimeMutation(mutation(execution))
                        .build(),
                )
                activeIndices = emptyList()
                preloadedActionIndex = 0
            }
            val action = captures.prepareActions(job, runtime.getResourceCatalog(), diagnostics)
            val prepared = action.prepared
            val captured = ArrayList<CaptureResult>()
            val completedCapturePlans = ArrayList<CapturePlan>()
            val captureExecutions = ArrayList<CaptureExecution>()
            val afterPassExecutions = ArrayList<AfterPassExecution>()
            val patchedExecutions = ArrayList<PatchedExecution>()
            var comparison: dev.vibris.protocol.v2.CompareReceipt? = null

            fun executeSteps() {
                for (step in action.program.steps) {
                    if (step.actionIndex == preloadedActionIndex) {
                        check(step.type == CaptureProgramBuilder.ActionType.LOAD) {
                            "The post-load capture plan did not retain its load prelude."
                        }
                        continue
                    }
                    activeIndices = step.actionIndices()
                    when (step.type) {
                        CaptureProgramBuilder.ActionType.LOAD -> {
                            val load = step.loadShader!!
                            val execution = load(job, load, progress, deadline)
                            diagnostics.addAll(execution.reload.diagnostics)
                            prepared?.addDiagnostics(execution.reload.diagnostics)
                            owner.observeCatalog(
                                owner.await(runtime.getCompileCatalog(job.cancellation.token()), job, deadline),
                            )
                            receiptBook.put(
                                step.actionIndex,
                                receiptBook.success(step.actionIndex)
                                    .setRuntimeMutation(mutation(execution))
                                    .build(),
                            )
                        }
                        CaptureProgramBuilder.ActionType.ACTIVATE -> {
                            val execution = activate(job, step.sourceUuid!!, progress, deadline)
                            diagnostics.addAll(execution.reload.diagnostics)
                            prepared?.addDiagnostics(execution.reload.diagnostics)
                            receiptBook.put(
                                step.actionIndex,
                                receiptBook.success(step.actionIndex)
                                    .setRuntimeMutation(mutation(execution))
                                    .build(),
                            )
                        }
                        CaptureProgramBuilder.ActionType.RESET -> {
                            owner.reset(job, progress, deadline)
                            receiptBook.put(
                                step.actionIndex,
                                receiptBook.success(step.actionIndex)
                                    .setResetTemporal(
                                        ResetTemporalReceipt.newBuilder()
                                            .setCompletedAtUnixMs(System.currentTimeMillis()),
                                    )
                                    .build(),
                            )
                        }
                        CaptureProgramBuilder.ActionType.WAIT -> {
                            val endFrame = owner.waitFrames(job, progress, deadline, step.frames)
                            receiptBook.put(
                                step.actionIndex,
                                receiptBook.success(step.actionIndex)
                                    .setWaitFrames(waitReceipt(step.frames, endFrame))
                                    .build(),
                            )
                        }
                        CaptureProgramBuilder.ActionType.CAPTURE -> {
                            if (prepared == null) throw captureUnavailable()
                            val waits = HashMap<Int, WaitFramesReceipt>()
                            step.captureActions.forEach { capture ->
                                if (capture.beforeFrames > 0) {
                                    activeIndices = listOf(capture.actionIndex)
                                    val endFrame = owner.waitFrames(
                                        job,
                                        progress,
                                        deadline,
                                        capture.beforeFrames,
                                    )
                                    waits[capture.actionIndex] = waitReceipt(capture.beforeFrames, endFrame)
                                }
                                val placeholder = dev.vibris.protocol.v2.CaptureReceipt.newBuilder()
                                waits[capture.actionIndex]?.let(placeholder::setInternalWait)
                                receiptBook.put(
                                    capture.actionIndex,
                                    receiptBook.success(capture.actionIndex).setCapture(placeholder).build(),
                                )
                            }
                            activeIndices = step.captureActions.map(CaptureProgramBuilder.CaptureAction::actionIndex)
                            val result = owner.capture(job, progress, deadline, prepared, step.capture!!)
                            captured.add(result)
                            completedCapturePlans.add(step.capture)
                            step.captureActions.forEach { capture ->
                                receiptBook.replace(
                                    capture.actionIndex,
                                    receiptBook.success(capture.actionIndex)
                                        .setCapture(
                                            captures.captureReceipt(
                                                step.capture,
                                                result,
                                                capture,
                                                JobResult.getDefaultInstance(),
                                                waits[capture.actionIndex],
                                            ),
                                        )
                                        .build(),
                                )
                                pendingCaptureIndices.add(capture.actionIndex)
                            }
                            captureExecutions.add(CaptureExecution(step.capture, result, step.captureActions, waits))
                        }
                        CaptureProgramBuilder.ActionType.AFTER_PASS -> {
                            if (prepared == null) throw captureUnavailable()
                            val receipts = owner.captureAfterPass(
                                job,
                                progress,
                                deadline,
                                prepared,
                                step.afterPassActions,
                            )
                            check(receipts.size == step.afterPassActions.size) {
                                "Runtime after-pass receipt count did not match its requests."
                            }
                            receipts.zip(step.afterPassActions).forEach { (receipt, capture) ->
                                val plan = CapturePlan(listOf(capture.request.target))
                                captured.add(receipt.capture)
                                completedCapturePlans.add(plan)
                                receiptBook.put(
                                    capture.actionIndex,
                                    receiptBook.success(capture.actionIndex)
                                        .setCapture(
                                            captures.afterPassReceipt(
                                                receipt,
                                                JobResult.getDefaultInstance(),
                                            ),
                                        )
                                        .build(),
                                )
                                pendingCaptureIndices.add(capture.actionIndex)
                                afterPassExecutions.add(
                                    AfterPassExecution(capture.actionIndex, receipt),
                                )
                            }
                        }
                        CaptureProgramBuilder.ActionType.PATCHED_SHADERS -> {
                            if (prepared == null) throw captureUnavailable()
                            receiptBook.put(
                                step.actionIndex,
                                receiptBook.success(step.actionIndex)
                                    .setPatchedShaders(PatchedShadersReceipt.getDefaultInstance())
                                    .build(),
                            )
                            val placeholder = step.capture!!
                            val result = owner.capturePatchedShaders(
                                job,
                                progress,
                                deadline,
                                prepared,
                                placeholder.targets.single().artifactName,
                            )
                            val realized = CapturePlanBuilder.realizePatchedShaders(placeholder, result)
                            captured.add(result)
                            completedCapturePlans.add(realized)
                            pendingCaptureIndices.add(step.actionIndex)
                            patchedExecutions.add(PatchedExecution(step.actionIndex, realized, result))
                        }
                        CaptureProgramBuilder.ActionType.COMPARE -> {
                            if (prepared == null) throw captureUnavailable()
                            progress.accept(JobStage.JOB_STAGE_COMPARING)
                            probe.event(job.requestId, "COMPARING")
                            comparison = captures.compare(prepared, step.comparison!!)
                            receiptBook.put(
                                step.actionIndex,
                                receiptBook.success(step.actionIndex).setComparison(comparison).build(),
                            )
                        }
                        CaptureProgramBuilder.ActionType.INSPECT -> {
                            val catalog = owner.await(
                                runtime.getCompileCatalog(job.cancellation.token()),
                                job,
                                deadline,
                            )
                            owner.observeCatalog(catalog)
                            receiptBook.put(
                                step.actionIndex,
                                receiptBook.success(step.actionIndex)
                                    .setShaderInspection(
                                        ShaderInspectionReceipt.newBuilder()
                                            .setCatalog(CompileCatalogProtocol.toProtocol(catalog)),
                                    )
                                    .build(),
                            )
                        }
                        CaptureProgramBuilder.ActionType.RUNTIME -> {
                            val response = owner.await(
                                runtime.executeAction(RuntimeActionProtocol.toApi(step.runtimeAction!!)),
                                job,
                                deadline,
                            )
                            val receipt = receiptBook.success(step.actionIndex)
                            if (step.runtimeAction.hasGetGpuMetrics()) {
                                try {
                                    receipt.setGpuMetrics(
                                        RuntimeActionProtocol.gpuMetricsReceipt(step.runtimeAction, response),
                                    )
                                } catch (exception: IllegalArgumentException) {
                                    throw RuntimeJobExecutor.Failure(
                                        ErrorCode.ERROR_CODE_NO_GPU_SAMPLES,
                                        exception.message ?: "GPU timing response is invalid.",
                                    )
                                }
                            } else {
                                receipt.setEmpty(EmptyReceipt.getDefaultInstance())
                            }
                            receiptBook.put(step.actionIndex, receipt.build())
                        }
                    }
                    activeIndices = emptyList()
                }
            }

            if (prepared == null) {
                executeSteps()
                return receiptBook.complete().addTo(JobResult.newBuilder()).build()
            }
            prepared.use {
                executeSteps()
                progress.accept(JobStage.JOB_STAGE_WRITING_ARTIFACTS)
                probe.event(job.requestId, "WRITING_ARTIFACTS")
                val resultArtifacts = ProfileResultArtifacts.write(
                    job.submission,
                    prepared.transaction,
                    receiptBook.complete().actions,
                )
                progress.accept(JobStage.JOB_STAGE_FINALIZING)
                probe.event(job.requestId, "FINALIZING")
                activeIndices = pendingCaptureIndices.toList()
                val committed = captures.commit(
                    job,
                    prepared,
                    completedCapturePlans,
                    captured,
                    comparison,
                    resultArtifacts,
                    afterPassExecutions.associate { execution ->
                        execution.receipt.request.target.artifactName to execution.receipt.physicalName
                    },
                )
                captureExecutions.forEach { execution ->
                    execution.actions.forEach { capture ->
                        receiptBook.replace(
                            capture.actionIndex,
                            receiptBook.success(capture.actionIndex)
                                .setCapture(
                                    captures.captureReceipt(
                                        execution.plan,
                                        execution.result,
                                        capture,
                                        committed,
                                        execution.waits[capture.actionIndex],
                                    ),
                                )
                                .build(),
                        )
                    }
                }
                afterPassExecutions.forEach { execution ->
                    receiptBook.replace(
                        execution.actionIndex,
                        receiptBook.success(execution.actionIndex)
                            .setCapture(captures.afterPassReceipt(execution.receipt, committed))
                            .build(),
                    )
                }
                patchedExecutions.forEach { execution ->
                    receiptBook.replace(
                        execution.actionIndex,
                        receiptBook.success(execution.actionIndex)
                            .setPatchedShaders(
                                captures.patchedShadersReceipt(execution.plan, execution.result, committed),
                            )
                            .build(),
                    )
                }
                pendingCaptureIndices.clear()
                activeIndices = emptyList()
                return receiptBook.complete().addTo(committed.toBuilder()).build()
            }
        } catch (failure: RuntimeJobExecutor.Failure) {
            val receipts = receiptBook.fail(failure, pendingCaptureIndices + activeIndices)
            throw failure.withActionReceipts(receipts.actions, receipts.preludes)
        } catch (exception: IOException) {
            val failure = CaptureJobExecutor.failure(exception)
            val receipts = receiptBook.fail(failure, pendingCaptureIndices + activeIndices)
            throw failure.withActionReceipts(receipts.actions, receipts.preludes)
        } catch (exception: RuntimeException) {
            val failure = RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_INTERNAL,
                exception.message ?: "Action execution failed.",
            )
            val receipts = receiptBook.fail(failure, pendingCaptureIndices + activeIndices)
            throw failure.withActionReceipts(receipts.actions, receipts.preludes)
        }
    }

    private fun activate(
        job: CoreJob,
        uuid: String,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): MutationResult {
        val source = source(job, uuid, "Action references an unprepared source.")
        val reload = owner.activateSource(job, source, progress, deadline)
        val context = owner.applyContext(job, progress, deadline).context
        return MutationResult(source, reload, context)
    }

    private fun load(
        job: CoreJob,
        load: dev.vibris.protocol.v2.LoadShader,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): MutationResult {
        val source = source(job, load.sourceUuid, "Load action references an unprepared source.")
        val loaded = owner.loadShader(job, source, load.config, progress, deadline)
        return MutationResult(source, loaded.reload, loaded.context.context)
    }

    private fun source(job: CoreJob, uuid: String, message: String): SourceRegistry.Lease =
        job.sources.firstOrNull { it.uuid().equals(uuid, ignoreCase = true) }
            ?: throw RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_INVALID_SOURCE, message)

    private fun mutation(result: MutationResult): RuntimeMutationReceipt = RuntimeMutationReceipt.newBuilder()
        .setSourceUuid(result.source.uuid)
        .setSourceSha256(result.source.snapshotSha256)
        .setEffectiveSettings(BenchmarkProvenance.effectiveSettings(result.reload.effectiveSettings))
        .setSceneSha256(BenchmarkProvenance.sceneHash(result.context))
        .setCompletedAtUnixMs(System.currentTimeMillis())
        .build()

    private fun waitReceipt(frames: Int, endFrame: Long): WaitFramesReceipt {
        check(endFrame >= frames.toLong()) { "Runtime returned an incomplete frame wait." }
        return WaitFramesReceipt.newBuilder()
            .setRequestedFrames(frames)
            .setStartFrame(endFrame - frames)
            .setEndFrame(endFrame)
            .setCompletedFrames(frames)
            .build()
    }

    private fun CaptureProgramBuilder.ActionStep.actionIndices(): List<Int> =
        when (type) {
            CaptureProgramBuilder.ActionType.CAPTURE ->
                captureActions.map(CaptureProgramBuilder.CaptureAction::actionIndex)
            CaptureProgramBuilder.ActionType.AFTER_PASS ->
                afterPassActions.map(CaptureProgramBuilder.AfterPassAction::actionIndex)
            else -> listOf(actionIndex)
        }

    private fun captureUnavailable() = RuntimeJobExecutor.Failure(
        ErrorCode.ERROR_CODE_CAPTURE_FAILED,
        "Capture storage is unavailable.",
    )

    private data class MutationResult(
        val source: SourceRegistry.Lease,
        val reload: ReloadResult,
        val context: SceneContext,
    )

    private data class CaptureExecution(
        val plan: CapturePlan,
        val result: CaptureResult,
        val actions: List<CaptureProgramBuilder.CaptureAction>,
        val waits: Map<Int, WaitFramesReceipt>,
    )

    private data class PatchedExecution(
        val actionIndex: Int,
        val plan: CapturePlan,
        val result: CaptureResult,
    )

    private data class AfterPassExecution(
        val actionIndex: Int,
        val receipt: CapturePlan.AfterPassReceipt,
    )
}
