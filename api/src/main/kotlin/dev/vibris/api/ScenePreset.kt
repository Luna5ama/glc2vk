package dev.vibris.api

@JvmRecord
data class ScenePreset(
    val presetId: String,
    val displayName: String,
    val context: SceneContext,
) {
    init {
        require(presetId.isNotBlank() && displayName.isNotBlank()) {
            "Preset names are blank"
        }
    }
}