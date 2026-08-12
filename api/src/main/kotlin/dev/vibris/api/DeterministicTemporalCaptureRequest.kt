package dev.vibris.api

@JvmRecord
data class DeterministicTemporalCaptureRequest(
    val context: SceneContext,
    val preserveCurrentSettings: Boolean,
    @field:DefensiveSnapshot val settings: Map<String, String>,
    val warmupFrames: Int,
) {
    init {
        require(!preserveCurrentSettings || settings.isEmpty()) {
            "Preserving current settings cannot include setting overrides"
        }
        require(warmupFrames >= 0) { "Warmup frame count must not be negative" }
    }
}