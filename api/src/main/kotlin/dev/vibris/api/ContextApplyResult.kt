package dev.vibris.api

@JvmRecord
data class ContextApplyResult(
    val successful: Boolean,
    val context: SceneContext,
    val message: String,
) {
    companion object {
        @JvmStatic
        fun success(context: SceneContext): ContextApplyResult = ContextApplyResult(true, context, "")

        @JvmStatic
        fun failure(context: SceneContext, message: String): ContextApplyResult =
            ContextApplyResult(false, context, message)
    }
}