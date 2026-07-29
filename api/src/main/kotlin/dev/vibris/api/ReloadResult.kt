package dev.vibris.api

class ReloadResult(
    private val successfulValue: Boolean,
    private val activeStatePreservedValue: Boolean,
    diagnostics: List<Diagnostic>,
) {
    private val diagnosticsValue = java.util.List.copyOf(diagnostics)

    fun successful(): Boolean = successfulValue

    fun activeStatePreserved(): Boolean = activeStatePreservedValue

    fun diagnostics(): List<Diagnostic> = diagnosticsValue

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ReloadResult &&
            successfulValue == other.successfulValue &&
            activeStatePreservedValue == other.activeStatePreservedValue &&
            diagnosticsValue == other.diagnosticsValue

    override fun hashCode(): Int =
        31 * (31 * successfulValue.hashCode() + activeStatePreservedValue.hashCode()) +
            diagnosticsValue.hashCode()

    override fun toString(): String =
        "ReloadResult[successful=$successfulValue, activeStatePreserved=$activeStatePreservedValue, " +
            "diagnostics=$diagnosticsValue]"

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
        fun success(diagnostics: List<Diagnostic>): ReloadResult = ReloadResult(true, false, diagnostics)

        @JvmStatic
        fun failure(diagnostics: List<Diagnostic>): ReloadResult = ReloadResult(false, false, diagnostics)

        @JvmStatic
        fun failurePreservingActiveState(diagnostics: List<Diagnostic>): ReloadResult =
            ReloadResult(false, true, diagnostics)
    }
}