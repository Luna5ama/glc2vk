package dev.vibris.api

@JvmRecord
data class ContextValidationResult(
    val valid: Boolean,
    val errors: List<String>,
) {
    init {
        require(!valid || errors.isEmpty()) {
            "A valid context cannot have errors"
        }
    }

    companion object {
        @JvmStatic
        fun accepted(): ContextValidationResult = ContextValidationResult(true, emptyList())

        @JvmStatic
        fun invalid(error: String): ContextValidationResult = ContextValidationResult(false, java.util.List.of(error))
    }
}