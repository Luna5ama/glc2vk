package dev.vibris.api

@JvmRecord
data class CaptureResult(
    val frameId: Long,
    @field:DefensiveSnapshot val groups: List<ArtifactGroup>,
) {
    init {
        require(frameId >= 0) { "frameId must not be negative" }
    }

    @JvmRecord
    data class ArtifactGroup(
        val name: String,
        val resource: ResourceCatalog.ResourceDescriptor,
        @field:DefensiveSnapshot val artifacts: List<CapturedArtifact>,
    )

    @JvmRecord
    data class CapturedArtifact(
        val fileName: String,
        val format: CapturePlan.ArtifactFormat,
        val role: CapturePlan.ArtifactRole,
        val subresourceIndex: Int?,
    )
}
