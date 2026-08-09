package dev.vibris.api

@JvmRecord
data class ScenePreset(
    val presetId: String,
    val displayName: String,
    val context: SceneContext,
    val version: String,
) {
    constructor(presetId: String, displayName: String, context: SceneContext) :
        this(presetId, displayName, context, "1")

    init {
        require(presetId.isNotBlank() && displayName.isNotBlank() && version.isNotBlank()) {
            "Preset names are blank"
        }
    }
}