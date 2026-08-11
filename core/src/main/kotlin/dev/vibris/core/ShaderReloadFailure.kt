package dev.vibris.core

import dev.vibris.api.ReloadResult
import dev.vibris.protocol.v2.ErrorCode
import java.io.IOException

internal object ShaderReloadFailure {
    @JvmStatic
    fun create(
        logs: ShaderLogSink,
        job: CoreJob,
        reload: ReloadResult,
    ): RuntimeJobExecutor.Failure {
        val message = reload.diagnostics
            .firstOrNull { it.severity == ReloadResult.Severity.ERROR }
            ?.message
            ?: "Shader reload failed."
        return try {
            val artifact = logs.writeShaderLog(job.workspaceId, job.requestId, reload.diagnostics)
            RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_SHADER_COMPILE_FAILED,
                message,
                java.util.List.of(artifact),
                reload.diagnostics,
                null,
                false,
            )
        } catch (_: IOException) {
            RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_SHADER_COMPILE_FAILED,
                message,
                java.util.List.of(),
                reload.diagnostics,
                null,
                false,
            )
        }
    }
}
