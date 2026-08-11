package dev.vibris.core

import dev.vibris.api.ReloadResult
import dev.vibris.protocol.v2.ArtifactMetadata
import java.io.IOException

internal fun interface ShaderLogSink {
    @Throws(IOException::class)
    fun writeShaderLog(
        workspaceId: String,
        requestId: String,
        diagnostics: List<ReloadResult.Diagnostic>,
    ): ArtifactMetadata

    companion object {
        @JvmStatic
        fun none(): ShaderLogSink =
            ShaderLogSink { _, _, _ ->
                throw IOException("Shader log storage is unavailable.")
            }
    }
}