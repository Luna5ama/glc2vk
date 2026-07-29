package dev.vibris.api

@JvmRecord
data class SceneContext(
    val saveId: String,
    val dimensionId: String,
    val timePresetId: String,
    val weatherPresetId: String,
    val cameraPresetId: String,
    val fov: Double,
    val resolution: Resolution,
    val settingsPresetId: String,
) {
    init {
        require(fov.isFinite() && fov > 0.0 && fov < 180.0) {
            "fov must be finite and between 0 and 180 degrees"
        }
    }

    @JvmRecord
    data class Resolution(
        val width: Int,
        val height: Int,
    ) {
        init {
            require(width >= 0 && height >= 0 && (width == 0) == (height == 0)) {
                "Resolution must be positive or unspecified"
            }
        }

        fun isSpecified(): Boolean = width != 0

        companion object {
            private val UNSPECIFIED = Resolution(0, 0)

            @JvmStatic
            fun unspecified(): Resolution = UNSPECIFIED
        }
    }
}