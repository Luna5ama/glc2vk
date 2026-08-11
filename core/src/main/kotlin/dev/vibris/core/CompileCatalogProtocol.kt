package dev.vibris.core

import dev.vibris.api.CompileCatalog
import dev.vibris.protocol.v2.CompileProgramEntry
import dev.vibris.protocol.v2.ShaderDiagnostic

internal object CompileCatalogProtocol {
    fun toProtocol(catalog: CompileCatalog): dev.vibris.protocol.v2.CompileCatalog =
        dev.vibris.protocol.v2.CompileCatalog.newBuilder()
            .addAllPrograms(catalog.programs.map(::program))
            .setMappingSha256(catalog.mappingSha256)
            .setShaderGeneration(catalog.shaderGeneration)
            .build()

    private fun program(entry: CompileCatalog.ProgramEntry): CompileProgramEntry = CompileProgramEntry.newBuilder()
        .setProgramId(entry.programId)
        .setPassId(entry.passId)
        .addAllStages(entry.stages.map(::stage))
        .setCompileState(state(entry.compileState))
        .setLinkState(state(entry.linkState))
        .setPatchedSourceSha256(entry.patchedSourceSha256)
        .addAllDiagnostics(entry.diagnostics.map(::diagnostic))
        .build()

    private fun diagnostic(value: CompileCatalog.Diagnostic): ShaderDiagnostic = ShaderDiagnostic.newBuilder()
        .setSeverity(
            when (value.severity) {
                CompileCatalog.DiagnosticSeverity.INFO ->
                    dev.vibris.protocol.v2.DiagnosticSeverity.DIAGNOSTIC_SEVERITY_INFO
                CompileCatalog.DiagnosticSeverity.WARNING ->
                    dev.vibris.protocol.v2.DiagnosticSeverity.DIAGNOSTIC_SEVERITY_WARNING
                CompileCatalog.DiagnosticSeverity.ERROR ->
                    dev.vibris.protocol.v2.DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR
            },
        )
        .setFileName(value.fileName)
        .setLine(value.line)
        .setColumn(value.column)
        .setMessage(value.message)
        .setFingerprintSha256(value.fingerprintSha256)
        .setLogPath(value.logPath)
        .build()

    private fun stage(value: CompileCatalog.ShaderStage): dev.vibris.protocol.v2.ShaderStage = when (value) {
        CompileCatalog.ShaderStage.VERTEX -> dev.vibris.protocol.v2.ShaderStage.SHADER_STAGE_VERTEX
        CompileCatalog.ShaderStage.TESS_CONTROL -> dev.vibris.protocol.v2.ShaderStage.SHADER_STAGE_TESS_CONTROL
        CompileCatalog.ShaderStage.TESS_EVALUATION ->
            dev.vibris.protocol.v2.ShaderStage.SHADER_STAGE_TESS_EVALUATION
        CompileCatalog.ShaderStage.GEOMETRY -> dev.vibris.protocol.v2.ShaderStage.SHADER_STAGE_GEOMETRY
        CompileCatalog.ShaderStage.FRAGMENT -> dev.vibris.protocol.v2.ShaderStage.SHADER_STAGE_FRAGMENT
        CompileCatalog.ShaderStage.COMPUTE -> dev.vibris.protocol.v2.ShaderStage.SHADER_STAGE_COMPUTE
    }

    private fun state(value: CompileCatalog.CompileState): dev.vibris.protocol.v2.CompileState = when (value) {
        CompileCatalog.CompileState.NOT_PRESENT -> dev.vibris.protocol.v2.CompileState.COMPILE_STATE_NOT_PRESENT
        CompileCatalog.CompileState.SUCCEEDED -> dev.vibris.protocol.v2.CompileState.COMPILE_STATE_SUCCEEDED
        CompileCatalog.CompileState.FAILED -> dev.vibris.protocol.v2.CompileState.COMPILE_STATE_FAILED
        CompileCatalog.CompileState.NOT_APPLICABLE ->
            dev.vibris.protocol.v2.CompileState.COMPILE_STATE_NOT_APPLICABLE
    }
}
