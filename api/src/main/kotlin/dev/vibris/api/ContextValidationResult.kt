package dev.vibris.api

class ContextValidationResult(
    private val validValue: Boolean,
    errors: List<String>,
) {
    private val errorsValue = java.util.List.copyOf(errors)

    init {
        require(!validValue || errorsValue.isEmpty()) {
            "A valid context cannot have errors"
        }
    }

    fun valid(): Boolean = validValue

    fun errors(): List<String> = errorsValue

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ContextValidationResult &&
            validValue == other.validValue &&
            errorsValue == other.errorsValue

    override fun hashCode(): Int = 31 * validValue.hashCode() + errorsValue.hashCode()

    override fun toString(): String = "ContextValidationResult[valid=$validValue, errors=$errorsValue]"

    companion object {
        @JvmStatic
        fun accepted(): ContextValidationResult = ContextValidationResult(true, emptyList())

        @JvmStatic
        fun invalid(error: String): ContextValidationResult = ContextValidationResult(false, listOf(error))
    }
}