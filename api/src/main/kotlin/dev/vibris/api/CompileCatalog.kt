package dev.vibris.api

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Complete, canonical compile state for every intended shader program/pass. */
@JvmRecord
data class CompileCatalog(
    @field:DefensiveSnapshot val programs: List<ProgramEntry>,
    val mappingSha256: String,
    val shaderGeneration: Long,
) {
    init {
        require(programs.zipWithNext().all { (left, right) -> PROGRAM_ORDER.compare(left, right) < 0 }) {
            "programs must be uniquely ordered by program_id and pass_id"
        }
        require(mappingSha256 == stableMappingHash(programs)) {
            "mappingSha256 must match the canonical program/pass mapping"
        }
        require(shaderGeneration >= 0) { "shaderGeneration must not be negative" }
    }

    @JvmRecord
    data class ProgramEntry(
        val programId: String,
        val passId: String,
        @field:DefensiveSnapshot val stages: List<ShaderStage>,
        val compileState: CompileState,
        val linkState: CompileState,
        val patchedSourceSha256: String,
        @field:DefensiveSnapshot val diagnostics: List<Diagnostic>,
    ) {
        init {
            require(programId.isNotBlank()) { "programId must not be blank" }
            require(passId.isNotBlank()) { "passId must not be blank" }
            require(stages.isNotEmpty()) { "stages must not be empty" }
            require(stages.zipWithNext().all { (left, right) -> left.ordinal < right.ordinal }) {
                "stages must be uniquely ordered"
            }
            require(ShaderStage.COMPUTE !in stages || stages == listOf(ShaderStage.COMPUTE)) {
                "compute stages cannot be mixed with graphics stages"
            }
            require(compileState != CompileState.NOT_APPLICABLE) {
                "compileState must describe a present or missing program"
            }
            when (compileState) {
                CompileState.NOT_PRESENT -> {
                    require(linkState == CompileState.NOT_PRESENT) {
                        "missing programs must have a missing link state"
                    }
                    require(patchedSourceSha256.isEmpty()) {
                        "missing programs cannot have a patched source identity"
                    }
                }
                CompileState.FAILED -> {
                    require(linkState == CompileState.NOT_APPLICABLE) {
                        "compile-failed programs cannot have a link result"
                    }
                    requireSha256(patchedSourceSha256, "patchedSourceSha256")
                }
                CompileState.SUCCEEDED -> {
                    require(linkState == CompileState.SUCCEEDED || linkState == CompileState.FAILED) {
                        "compiled programs must have a terminal link result"
                    }
                    requireSha256(patchedSourceSha256, "patchedSourceSha256")
                }
                CompileState.NOT_APPLICABLE -> error("validated above")
            }
            require(diagnostics.zipWithNext().all { (left, right) ->
                left.fingerprintSha256 < right.fingerprintSha256
            }) {
                "diagnostics must be uniquely ordered by fingerprint"
            }
            if (compileState == CompileState.FAILED || linkState == CompileState.FAILED) {
                require(diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
                    "failed programs must include an error diagnostic"
                }
            }
        }

        companion object {
            @JvmStatic
            fun of(
                programId: String,
                passId: String,
                stages: Collection<ShaderStage>,
                compileState: CompileState,
                linkState: CompileState,
                patchedSourceSha256: String,
                diagnostics: Collection<Diagnostic>,
            ): ProgramEntry = ProgramEntry(
                programId,
                passId,
                stages.sortedBy(ShaderStage::ordinal),
                compileState,
                linkState,
                patchedSourceSha256,
                diagnostics.sortedBy(Diagnostic::fingerprintSha256),
            )
        }
    }

    @JvmRecord
    data class Diagnostic(
        val severity: DiagnosticSeverity,
        val fileName: String,
        val line: Int,
        val column: Int,
        val message: String,
        val fingerprintSha256: String,
        val logPath: String,
    ) {
        init {
            require(fileName.isNotBlank()) { "fileName must not be blank" }
            require(line >= 0) { "line must not be negative" }
            require(column >= 0) { "column must not be negative" }
            require(message.isNotBlank()) { "message must not be blank" }
            require(fingerprintSha256 == stableDiagnosticHash(severity, fileName, line, column, message)) {
                "fingerprintSha256 must match the canonical diagnostic"
            }
        }

        companion object {
            @JvmStatic
            @JvmOverloads
            fun of(
                severity: DiagnosticSeverity,
                fileName: String,
                line: Int,
                column: Int,
                message: String,
                logPath: String = "",
            ): Diagnostic = Diagnostic(
                severity,
                fileName,
                line,
                column,
                message,
                stableDiagnosticHash(severity, fileName, line, column, message),
                logPath,
            )
        }
    }

    enum class ShaderStage {
        VERTEX,
        TESS_CONTROL,
        TESS_EVALUATION,
        GEOMETRY,
        FRAGMENT,
        COMPUTE,
    }

    enum class CompileState {
        NOT_PRESENT,
        SUCCEEDED,
        FAILED,
        NOT_APPLICABLE,
    }

    enum class DiagnosticSeverity {
        INFO,
        WARNING,
        ERROR,
    }

    companion object {
        private val MAPPING_HASH_DOMAIN = "vibris-compile-mapping-v2".toByteArray(Charsets.UTF_8)
        private val DIAGNOSTIC_HASH_DOMAIN = "vibris-shader-diagnostic-v2".toByteArray(Charsets.UTF_8)
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val PROGRAM_ORDER = compareBy<ProgramEntry>(ProgramEntry::programId, ProgramEntry::passId)

        @JvmStatic
        fun of(programs: Collection<ProgramEntry>, shaderGeneration: Long): CompileCatalog {
            val ordered = programs.sortedWith(PROGRAM_ORDER)
            require(ordered.zipWithNext().all { (left, right) ->
                left.programId != right.programId || left.passId != right.passId
            }) {
                "program/pass identities must be unique"
            }
            return CompileCatalog(ordered, stableMappingHash(ordered), shaderGeneration)
        }

        @JvmStatic
        fun empty(shaderGeneration: Long): CompileCatalog = of(emptyList(), shaderGeneration)

        private fun stableMappingHash(programs: List<ProgramEntry>): String = hash(MAPPING_HASH_DOMAIN) { digest ->
            digest.updateCount(programs.size)
            programs.forEach { program ->
                digest.updateField(program.programId)
                digest.updateField(program.passId)
                digest.updateCount(program.stages.size)
                program.stages.forEach { digest.updateField(it.name) }
            }
        }

        private fun stableDiagnosticHash(
            severity: DiagnosticSeverity,
            fileName: String,
            line: Int,
            column: Int,
            message: String,
        ): String = hash(DIAGNOSTIC_HASH_DOMAIN) { digest ->
            digest.updateField(severity.name)
            digest.updateField(fileName)
            digest.updateCount(line)
            digest.updateCount(column)
            digest.updateField(message)
        }

        private fun hash(domain: ByteArray, fields: (MessageDigest) -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(domain)
            digest.update(0.toByte())
            fields(digest)
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun MessageDigest.updateCount(value: Int) {
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
        }

        private fun MessageDigest.updateField(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            updateCount(bytes.size)
            update(bytes)
        }

        private fun requireSha256(value: String, field: String) {
            require(SHA256.matches(value)) { "$field must be a lowercase SHA-256" }
        }
    }
}
