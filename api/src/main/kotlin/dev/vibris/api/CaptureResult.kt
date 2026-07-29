package dev.vibris.api

@JvmRecord
data class CaptureResult(
    val frameId: Long,
    @field:DefensiveSnapshot val artifacts: Map<String, ResourceCatalog.ResourceDescriptor>,
) {
    init {
        require(frameId >= 0) { "frameId must not be negative" }
    }
}