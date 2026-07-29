package dev.vibris.api

@JvmRecord
data class ReloadResult(
    val successful: Boolean,
    val activeStatePreserved: Boolean,
    val diagnostics: List<Diagnostic>,
) {

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
        fun success(diagnostics: List<Diagnostic>): ReloadResult =
            ReloadResult(true, false, java.util.List.copyOf(diagnostics))

        @JvmStatic
        fun failure(diagnostics: List<Diagnostic>): ReloadResult =
            ReloadResult(false, false, java.util.List.copyOf(diagnostics))

        @JvmStatic
        fun failurePreservingActiveState(diagnostics: List<Diagnostic>): ReloadResult =
            ReloadResult(false, true, java.util.List.copyOf(diagnostics))
    }
}