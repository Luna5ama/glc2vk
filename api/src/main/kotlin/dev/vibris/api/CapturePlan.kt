package dev.vibris.api

import java.util.Locale
import java.util.regex.Pattern

@JvmRecord
data class CapturePlan(val targets: List<Target>) {

    @JvmRecord
    data class Target(
        val kind: ResourceCatalog.ResourceKind,
        val logicalName: String,
        val format: ArtifactFormat,
        val artifactName: String,
        val mipLevel: Int,
        val layer: Int,
    ) {
        init {
            require(mipLevel >= 0 && layer >= 0) {
                "Mip level and layer must be non-negative"
            }
            require(SAFE_ARTIFACT_NAME.matcher(artifactName).matches()) {
                "Artifact name must be a safe file name"
            }
        }

        fun fileName(): String {
            val extension = "." + format.name.lowercase(Locale.ROOT)
            return if (artifactName.lowercase(Locale.ROOT).endsWith(extension)) {
                artifactName
            } else {
                artifactName + extension
            }
        }

        fun metadataFileName(): String = "$artifactName.json"

        companion object {
            private val SAFE_ARTIFACT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        }
    }

    enum class ArtifactFormat {
        PNG,
        EXR,
        RAW,
        BIN,
    }

    companion object {
        @JvmStatic
        fun empty(): CapturePlan = CapturePlan(java.util.List.of())
    }
}