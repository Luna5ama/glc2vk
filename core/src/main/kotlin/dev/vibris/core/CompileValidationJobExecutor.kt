package dev.vibris.core

import dev.vibris.api.CompileCatalog
import dev.vibris.protocol.v2.CompileCaseResult
import dev.vibris.protocol.v2.CompileValidationCase
import dev.vibris.protocol.v2.CompileValidationResult
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.JobResult
import dev.vibris.protocol.v2.JobStage
import dev.vibris.protocol.v2.ResultProvenance
import dev.vibris.protocol.v2.ShaderDiagnostic
import java.util.TreeMap
import java.util.function.Consumer

internal class CompileValidationJobExecutor(
    private val owner: RuntimeJobExecutor,
) {
    @Throws(RuntimeJobExecutor.Failure::class)
    fun execute(job: CoreJob, progress: Consumer<JobStage>, deadline: Long): JobResult {
        val request = job.submission.compileValidation
        require(request.casesCount > 0) { "Compile validation requires at least one case." }
        val baseline = if (request.hasBaseline()) {
            compile(job, request.baseline, progress, deadline).also { execution ->
                if (!succeeded(execution.result.catalog)) {
                    throw RuntimeJobExecutor.Failure(
                        ErrorCode.ERROR_CODE_SHADER_COMPILE_FAILED,
                        "Compile validation baseline did not compile and link every intended program.",
                    )
                }
            }
        } else {
            null
        }
        val result = CompileValidationResult.newBuilder()
        request.casesList.forEach { current ->
            result.addCases(caseResult(current, compile(job, current, progress, deadline), baseline, job))
        }
        return JobResult.newBuilder().setCompileValidation(result).build()
    }

    private fun compile(
        job: CoreJob,
        requested: CompileValidationCase,
        progress: Consumer<JobStage>,
        deadline: Long,
    ): Execution {
        val source = job.sources.firstOrNull { it.uuid().equals(requested.sourceId, ignoreCase = true) }
            ?: throw RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_INVALID_SOURCE,
                "Compile validation references an unprepared source.",
            )
        val result = owner.compileShader(job, source, requested.config, progress, deadline)
        if (!result.reload.successful && succeeded(result.catalog)) {
            throw RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_SHADER_COMPILE_FAILED,
                "Shader reload failed without a matching terminal compile catalog failure.",
            )
        }
        return Execution(source, result)
    }

    private fun succeeded(catalog: CompileCatalog): Boolean = catalog.programs.isNotEmpty() &&
        catalog.programs.all { program ->
            program.compileState == CompileCatalog.CompileState.SUCCEEDED &&
                program.linkState == CompileCatalog.CompileState.SUCCEEDED
        }

    private fun caseResult(
        requested: CompileValidationCase,
        current: Execution,
        baseline: Execution?,
        job: CoreJob,
    ): CompileCaseResult {
        val currentDiagnostics = diagnostics(current.result.catalog)
        val baselineDiagnostics = baseline?.let { diagnostics(it.result.catalog) }.orEmpty()
        val builder = CompileCaseResult.newBuilder()
            .setCaseId(requested.caseId)
            .setCatalog(CompileCatalogProtocol.toProtocol(current.result.catalog))
            .setProvenance(provenance(job, current))
        currentDiagnostics.filterKeys { it !in baselineDiagnostics }.values.forEach(builder::addAddedDiagnostics)
        baselineDiagnostics.filterKeys { it !in currentDiagnostics }.values.forEach(builder::addResolvedDiagnostics)
        currentDiagnostics.filterKeys { it in baselineDiagnostics }.values.forEach(builder::addUnchangedDiagnostics)
        return builder.build()
    }

    private fun diagnostics(catalog: CompileCatalog): Map<String, ShaderDiagnostic> {
        val result = TreeMap<String, ShaderDiagnostic>()
        CompileCatalogProtocol.toProtocol(catalog).programsList.forEach { program ->
            program.diagnosticsList.forEach { diagnostic -> result.putIfAbsent(diagnostic.fingerprintSha256, diagnostic) }
        }
        return result
    }

    private fun provenance(job: CoreJob, execution: Execution): ResultProvenance {
        val reference = execution.source.reference
        val builder = ResultProvenance.newBuilder()
            .setWorkspaceId(job.workspaceId)
            .setRequestedRevision(reference.requestedRevision)
            .setResolvedRevision(reference.resolvedRevision)
            .setSourceSnapshotSha256(execution.source.snapshotSha256)
            .setActiveSourceUuid(execution.source.uuid)
            .setPresetId(job.submission.presetId)
            .setShaderLoadedAtUnixMs(execution.result.loadedAtUnixMs)
            .setPassMappingSha256(execution.result.catalog.mappingSha256)
        if (reference.origin.hasWorkspace()) builder.worktreeRoot = reference.origin.workspace.worktreeRoot
        if (execution.result.reload.successful) {
            builder.configSha256 = execution.result.reload.effectiveSettings.settingsSha256
            builder.effectiveSettings = BenchmarkProvenance.effectiveSettings(execution.result.reload.effectiveSettings)
        }
        return builder.build()
    }

    private data class Execution(
        val source: SourceRegistry.Lease,
        val result: RuntimeJobExecutor.CompileResult,
    )
}
