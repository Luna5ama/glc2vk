package dev.vibris.core

import dev.vibris.api.CompileCatalog
import dev.vibris.protocol.v2.CompileState
import dev.vibris.protocol.v2.ShaderStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompileCatalogProtocolTest {
    @Test
    fun `maps graphics compute missing compile and link outcomes without loss`() {
        val compileError = diagnostic("compile failed")
        val linkError = diagnostic("link failed")
        val catalog = CompileCatalog.of(
            listOf(
                entry(
                    "missing",
                    listOf(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
                    CompileCatalog.CompileState.NOT_PRESENT,
                    CompileCatalog.CompileState.NOT_PRESENT,
                    "",
                ),
                entry(
                    "link-failed",
                    listOf(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
                    CompileCatalog.CompileState.SUCCEEDED,
                    CompileCatalog.CompileState.FAILED,
                    "d".repeat(64),
                    listOf(linkError),
                ),
                entry(
                    "graphics",
                    listOf(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
                    CompileCatalog.CompileState.SUCCEEDED,
                    CompileCatalog.CompileState.SUCCEEDED,
                    "a".repeat(64),
                ),
                entry(
                    "compute",
                    listOf(CompileCatalog.ShaderStage.COMPUTE),
                    CompileCatalog.CompileState.SUCCEEDED,
                    CompileCatalog.CompileState.SUCCEEDED,
                    "b".repeat(64),
                ),
                entry(
                    "compile-failed",
                    listOf(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
                    CompileCatalog.CompileState.FAILED,
                    CompileCatalog.CompileState.NOT_APPLICABLE,
                    "c".repeat(64),
                    listOf(compileError),
                ),
            ),
            31,
        )

        val wire = CompileCatalogProtocol.toProtocol(catalog)
        val programs = wire.programsList.associateBy { it.programId }

        assertEquals(catalog.mappingSha256, wire.mappingSha256)
        assertEquals(31, wire.shaderGeneration)
        assertEquals(
            listOf("compile-failed", "compute", "graphics", "link-failed", "missing"),
            wire.programsList.map { it.programId },
        )
        assertEquals(listOf(ShaderStage.SHADER_STAGE_COMPUTE), programs.getValue("compute").stagesList)
        assertEquals(CompileState.COMPILE_STATE_NOT_PRESENT, programs.getValue("missing").compileState)
        assertEquals(CompileState.COMPILE_STATE_NOT_APPLICABLE, programs.getValue("compile-failed").linkState)
        assertEquals(CompileState.COMPILE_STATE_FAILED, programs.getValue("link-failed").linkState)
        assertEquals(
            compileError.fingerprintSha256,
            programs.getValue("compile-failed").diagnosticsList.single().fingerprintSha256,
        )
    }

    private fun entry(
        id: String,
        stages: List<CompileCatalog.ShaderStage>,
        compileState: CompileCatalog.CompileState,
        linkState: CompileCatalog.CompileState,
        sourceHash: String,
        diagnostics: List<CompileCatalog.Diagnostic> = emptyList(),
    ) = CompileCatalog.ProgramEntry.of(
        id,
        "pass-$id",
        stages,
        compileState,
        linkState,
        sourceHash,
        diagnostics,
    )

    private fun diagnostic(message: String) = CompileCatalog.Diagnostic.of(
        CompileCatalog.DiagnosticSeverity.ERROR,
        "shader.glsl",
        1,
        1,
        message,
    )
}
