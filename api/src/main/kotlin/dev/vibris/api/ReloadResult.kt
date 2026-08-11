package dev.vibris.api

@JvmRecord
data class ReloadResult(
    val successful: Boolean,
    val activeStatePreserved: Boolean,
    val effectiveSettings: EffectiveShaderSettings,
    @field:DefensiveSnapshot val diagnostics: List<Diagnostic>,
) {
    init {
        require(!successful || !activeStatePreserved) {
            "A successful reload establishes a new verified active state"
        }
    }

    @JvmRecord
    data class Diagnostic(
        val severity: Severity,
        val source: String,
        val line: Int,
        val message: String,
    ) {
        init {
            require(line >= 0) { "line must not be negative" }
        }
    }

    enum class Severity {
        INFO,
        WARNING,
        ERROR,
    }

    companion object {
        @JvmStatic
        fun success(
            effectiveSettings: EffectiveShaderSettings,
            diagnostics: List<Diagnostic>,
        ): ReloadResult = ReloadResult(true, false, effectiveSettings, diagnostics)

        @JvmStatic
        fun failure(diagnostics: List<Diagnostic>): ReloadResult =
            ReloadResult(false, false, EffectiveShaderSettings.empty(), diagnostics)

        @JvmStatic
        fun failurePreservingActiveState(
            effectiveSettings: EffectiveShaderSettings,
            diagnostics: List<Diagnostic>,
        ): ReloadResult = ReloadResult(false, true, effectiveSettings, diagnostics)
    }
}