package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResourceNotFoundException
import dev.vibris.api.CaptureResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.CompareReceipt
import dev.vibris.protocol.v2.CaptureReceipt
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobResult
import dev.vibris.protocol.v2.PatchedShadersReceipt
import dev.vibris.protocol.v2.WaitFramesReceipt
import java.io.IOException
import java.nio.charset.StandardCharsets

internal class CaptureJobExecutor(
    private val artifacts: ArtifactManager?,
    maxActions: Int = ServerConfiguration.DEFAULT_MAX_ACTIONS_PER_JOB,
) {
    private val programs = CaptureProgramBuilder(maxActions)
    private val protocol = CaptureProtocolArtifacts()
    private val comparisons = AbArtifactComparator()

    @Throws(RuntimeJobExecutor.Failure::class)
    fun prepareActions(
        job: CoreJob,
        catalog: ResourceCatalog,
        diagnostics: List<ReloadResult.Diagnostic>,
    ): ActionPrepared {
        val program = programs.actions(job, catalog)
        val captures = program.steps.flatMap { step ->
            when (step.type) {
                CaptureProgramBuilder.ActionType.CAPTURE,
                CaptureProgramBuilder.ActionType.PATCHED_SHADERS,
                -> listOf(step.capture!!)
                CaptureProgramBuilder.ActionType.AFTER_PASS -> step.afterPassActions.map { action ->
                    CapturePlan(listOf(action.request.target))
                }
                else -> emptyList()
            }
        }
        val prepared = if (captures.isEmpty() && !ProfileResultArtifacts.requested(job.submission)) {
            null
        } else {
            prepare(job, captures, program.estimatedBytes, diagnostics)
        }
        return ActionPrepared(program, prepared)
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    private fun prepare(
        job: CoreJob,
        capturePlans: List<CapturePlan>,
        estimate: Long,
        diagnostics: List<ReloadResult.Diagnostic>,
    ): Prepared {
        val manager = artifacts
            ?: throw RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_CAPTURE_FAILED, "Artifact storage is unavailable.")
        val shaderLog = shaderLog(diagnostics)
        var transaction: ArtifactManager.JobTransaction? = null
        try {
            val manifestFileCount = capturePlans.sumOf { plan ->
                plan.targets.sumOf { target -> target.outputs.size }
            } + 9
            transaction = manager.beginJob(
                job.workspaceId,
                job.submission.jobId,
                job.requestId,
                job.submission.workloadCase.name.removeSuffix("_WORKLOAD").lowercase(),
                Math.addExact(
                    Math.addExact(estimate, shaderLog.size.toLong()),
                    ArtifactManifest.reservationBytes(manifestFileCount),
                ),
            )
            return Prepared(transaction, capturePlans, diagnostics)
        } catch (exception: Exception) {
            closeAfterFailure(transaction, exception)
            throw failure(exception)
        }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun commit(job: CoreJob, prepared: Prepared, captured: CaptureResult): JobResult =
        commit(job, prepared, listOf(captured), null)

    @Throws(RuntimeJobExecutor.Failure::class)
    fun commit(
        job: CoreJob,
        prepared: Prepared,
        captured: List<CaptureResult>,
        comparison: CompareReceipt?,
    ): JobResult = commit(job, prepared, prepared.plans, captured, comparison)

    @Throws(RuntimeJobExecutor.Failure::class)
    fun commit(
        job: CoreJob,
        prepared: Prepared,
        plans: List<CapturePlan>,
        captured: List<CaptureResult>,
        comparison: CompareReceipt?,
        additionalArtifacts: List<GeneratedArtifact> = emptyList(),
        physicalNames: Map<String, String> = emptyMap(),
    ): JobResult {
        try {
            prepared.writeShaderLog()
            return protocol.commit(
                job,
                plans,
                captured,
                prepared.transaction,
                prepared.diagnostics,
                comparison,
                additionalArtifacts,
                physicalNames,
            )
        } catch (exception: Exception) {
            throw failure(exception)
        }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun compare(
        prepared: Prepared,
        comparison: CaptureProgramBuilder.Comparison,
    ): CompareReceipt {
        try {
            return comparisons.compare(
                prepared.transaction,
                comparison.baselinePlan,
                comparison.candidatePlan,
                comparison.baselineLabel,
                comparison.candidateLabel,
                comparison.thresholds,
            )
        } catch (exception: Exception) {
            throw failure(exception)
        }
    }

    fun captureReceipt(
        plan: CapturePlan,
        captured: CaptureResult,
        capture: CaptureProgramBuilder.CaptureAction,
        committed: JobResult,
        internalWait: WaitFramesReceipt?,
    ): CaptureReceipt = protocol.captureReceipt(
        plan,
        captured,
        capture.targetIndex,
        committed,
        internalWait,
    )

    fun patchedShadersReceipt(
        plan: CapturePlan,
        captured: CaptureResult,
        committed: JobResult,
    ): PatchedShadersReceipt = protocol.patchedShadersReceipt(plan, captured, committed)

    fun afterPassReceipt(
        receipt: CapturePlan.AfterPassReceipt,
        committed: JobResult,
    ): CaptureReceipt = protocol.afterPassReceipt(receipt, committed)

    inner class Prepared internal constructor(
        internal val transaction: ArtifactManager.JobTransaction,
        capturePlans: List<CapturePlan>,
        diagnostics: List<ReloadResult.Diagnostic>,
    ) : AutoCloseable {
        internal val plans: List<CapturePlan> = java.util.List.copyOf(capturePlans)
        internal val diagnostics = ArrayList(diagnostics)
        private var shaderLogWritten = false

        fun plan(): CapturePlan = plans.first()

        fun sink(): ArtifactManager.JobTransaction = transaction

        fun checkpoint(): ArtifactJobTransaction.Checkpoint = transaction.checkpoint()

        @Throws(IOException::class)
        fun rollback(checkpoint: ArtifactJobTransaction.Checkpoint) {
            transaction.rollback(checkpoint)
        }

        fun addDiagnostics(additional: List<ReloadResult.Diagnostic>) {
            check(!shaderLogWritten) { "Shader log has already been finalized." }
            diagnostics.addAll(additional)
        }

        @Throws(IOException::class)
        internal fun writeShaderLog() {
            if (shaderLogWritten) {
                throw IOException("Shader log has already been written.")
            }
            transaction.open("shader.log").use { output -> output.write(shaderLog(diagnostics)) }
            shaderLogWritten = true
        }

        @Throws(IOException::class)
        override fun close() {
            transaction.close()
        }
    }

    @JvmRecord
    data class ActionPrepared(
        val program: CaptureProgramBuilder.ActionProgram,
        val prepared: Prepared?,
    )

    companion object {
        @JvmStatic
        fun failure(exception: Throwable?): RuntimeJobExecutor.Failure {
            var cause = exception
            while (cause != null && cause !== cause.cause) {
                when (cause) {
                    is ArtifactManager.JobTooLargeException,
                    is ArithmeticException,
                    -> return RuntimeJobExecutor.Failure(
                        ErrorCode.ERROR_CODE_ARTIFACT_TOO_LARGE,
                        "Artifact job is too large.",
                    )

                    is ArtifactManager.QuotaExceededException -> return RuntimeJobExecutor.Failure(
                        ErrorCode.ERROR_CODE_ARTIFACT_QUOTA_EXCEEDED,
                        cause.message,
                    )

                    is CaptureResourceNotFoundException -> return RuntimeJobExecutor.Failure(
                        ErrorCode.ERROR_CODE_RESOURCE_NOT_FOUND,
                        cause.message,
                    )

                    is RuntimeJobExecutor.Failure -> return cause
                }
                cause = cause.cause
            }
            return RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_CAPTURE_FAILED, "Capture artifact creation failed.")
        }

        private fun closeAfterFailure(transaction: ArtifactManager.JobTransaction?, original: Exception) {
            if (transaction == null) {
                return
            }
            try {
                transaction.close()
            } catch (closeFailure: IOException) {
                original.addSuppressed(closeFailure)
            }
        }

        private fun shaderLog(diagnostics: List<ReloadResult.Diagnostic>): ByteArray {
            if (diagnostics.isEmpty()) {
                return "Shader reload succeeded.\n".toByteArray(StandardCharsets.UTF_8)
            }
            val output = StringBuilder()
            for (diagnostic in diagnostics) {
                output.append('[').append(diagnostic.severity).append("] ").append(diagnostic.source)
                if (diagnostic.line > 0) {
                    output.append(':').append(diagnostic.line)
                }
                output.append(": ").append(diagnostic.message).append(System.lineSeparator())
            }
            return output.toString().toByteArray(StandardCharsets.UTF_8)
        }
    }
}
