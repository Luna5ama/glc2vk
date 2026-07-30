package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.CaptureResourceNotFoundException
import dev.vibris.api.CaptureResult
import dev.vibris.api.ReloadResult
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v1.AbComparisonResult
import dev.vibris.protocol.v1.ErrorCode
import dev.vibris.protocol.v1.JobResult
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
        val captures = program.steps
            .filter { it.type == CaptureProgramBuilder.ActionType.CAPTURE }
            .map { it.capture!! }
        val prepared = if (captures.isEmpty()) {
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
            ?: throw RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, "Artifact storage is unavailable.")
        val shaderLog = shaderLog(diagnostics)
        var transaction: ArtifactManager.JobTransaction? = null
        try {
            transaction = manager.beginJob(
                job.workspaceId,
                job.requestId,
                Math.addExact(estimate, shaderLog.size.toLong()),
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
        comparison: AbComparisonResult?,
    ): JobResult {
        try {
            prepared.writeShaderLog()
            return protocol.commit(
                job,
                prepared.plans,
                captured,
                prepared.transaction,
                prepared.diagnostics,
                comparison,
            )
        } catch (exception: Exception) {
            throw failure(exception)
        }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun compare(
        prepared: Prepared,
        comparison: CaptureProgramBuilder.Comparison,
    ): AbComparisonResult {
        try {
            return comparisons.compare(
                prepared.transaction,
                prepared.plans[comparison.baselineCaptureIndex],
                prepared.plans[comparison.candidateCaptureIndex],
                comparison.baselineLabel,
                comparison.candidateLabel,
            )
        } catch (exception: Exception) {
            throw failure(exception)
        }
    }

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
                        ErrorCode.ARTIFACT_JOB_TOO_LARGE,
                        "Artifact job is too large.",
                    )

                    is ArtifactManager.QuotaExceededException -> return RuntimeJobExecutor.Failure(
                        ErrorCode.ARTIFACT_QUOTA_EXCEEDED,
                        cause.message,
                    )

                    is CaptureResourceNotFoundException -> return RuntimeJobExecutor.Failure(
                        ErrorCode.CAPTURE_RESOURCE_NOT_FOUND,
                        cause.message,
                    )

                    is RuntimeJobExecutor.Failure -> return cause
                }
                cause = cause.cause
            }
            return RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, "Capture artifact creation failed.")
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
