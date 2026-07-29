package dev.vibris.api

class CaptureResult(
    private val frameIdValue: Long,
    artifacts: Map<String, ResourceCatalog.ResourceDescriptor>,
) {
    private val artifactsValue = java.util.Map.copyOf(artifacts)

    init {
        require(frameIdValue >= 0) { "frameId must not be negative" }
    }

    fun frameId(): Long = frameIdValue

    fun artifacts(): Map<String, ResourceCatalog.ResourceDescriptor> = artifactsValue

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is CaptureResult &&
            frameIdValue == other.frameIdValue &&
            artifactsValue == other.artifactsValue

    override fun hashCode(): Int = 31 * frameIdValue.hashCode() + artifactsValue.hashCode()

    override fun toString(): String = "CaptureResult[frameId=$frameIdValue, artifacts=$artifactsValue]"
}