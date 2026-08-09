package dev.vibris.api

import java.util.Locale
import java.util.regex.Pattern

@JvmRecord
data class CapturePlan(@field:DefensiveSnapshot val targets: List<Target>) {

    @JvmRecord
    data class Target(
        val kind: ResourceCatalog.ResourceKind,
        val logicalName: String,
        val format: ArtifactFormat,
        val artifactName: String,
        val mipLevel: Int,
        val layer: Int,
        @field:DefensiveSnapshot val outputs: List<ArtifactOutputSpec>,
    ) {
        init {
            require(mipLevel >= 0 && layer >= 0) {
                "Mip level and layer must be non-negative"
            }
            require(SAFE_ARTIFACT_NAME.matcher(artifactName).matches()) {
                "Artifact name must be a safe file name"
            }
        }

        constructor(
            kind: ResourceCatalog.ResourceKind,
            logicalName: String,
            format: ArtifactFormat,
            artifactName: String,
            mipLevel: Int,
            layer: Int,
        ) : this(kind, logicalName, format, artifactName, mipLevel, layer, java.util.List.of())

        fun fileName(): String {
            outputs.firstOrNull { it.role != ArtifactRole.METADATA }?.let { return it.fileName }
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

    @JvmRecord
    data class ArtifactOutputSpec(
        val fileName: String,
        val format: ArtifactFormat,
        val role: ArtifactRole,
        val subresourceIndex: Int?,
    ) {
        init {
            require(SAFE_OUTPUT_NAME.matcher(fileName).matches()) { "Artifact output name must be safe" }
            require(subresourceIndex == null || subresourceIndex >= 0) { "Subresource index must not be negative" }
        }

        companion object {
            private val SAFE_OUTPUT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,191}")
        }
    }

    enum class ArtifactRole {
        PRIMARY,
        SUBRESOURCE,
        METADATA,
    }

    enum class ArtifactFormat {
        PNG,
        EXR,
        BIN,
        TEXT,
        JSON,
    }

    companion object {
        @JvmStatic
        fun empty(): CapturePlan = CapturePlan(java.util.List.of())
    }
}
