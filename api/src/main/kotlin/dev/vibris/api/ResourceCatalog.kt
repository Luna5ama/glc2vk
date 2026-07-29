package dev.vibris.api

class ResourceCatalog(resources: List<ResourceDescriptor>) {
    private val resourcesValue = java.util.List.copyOf(resources)

    fun resources(): List<ResourceDescriptor> = resourcesValue

    override fun equals(other: Any?): Boolean =
        this === other || other is ResourceCatalog && resourcesValue == other.resourcesValue

    override fun hashCode(): Int = resourcesValue.hashCode()

    override fun toString(): String = "ResourceCatalog[resources=$resourcesValue]"

    @JvmRecord
    data class ResourceDescriptor(
        val logicalName: String,
        val kind: ResourceKind,
        val width: Int,
        val height: Int,
        val depth: Int,
        val mipLevels: Int,
        val layers: Int,
        val internalFormat: String,
        val channelCount: Int,
        val scalarType: ScalarType,
        val byteSize: Long,
        val frameId: Long,
        val semanticLabel: String,
    ) {
        init {
            require(
                width >= 0 &&
                    height >= 0 &&
                    depth >= 0 &&
                    mipLevels >= 0 &&
                    layers >= 0 &&
                    channelCount >= 0 &&
                    byteSize >= 0 &&
                    frameId >= 0,
            ) {
                "Resource metadata must not be negative"
            }
        }

        constructor(logicalName: String, kind: ResourceKind) : this(
            logicalName,
            kind,
            0,
            0,
            0,
            0,
            0,
            "",
            0,
            ScalarType.UNSPECIFIED,
            0,
            0,
            "",
        )
    }

    enum class ResourceKind {
        FINAL_FRAMEBUFFER,
        TEXTURE,
        BUFFER,
    }

    enum class ScalarType {
        UNSPECIFIED,
        UINT8,
        SINT8,
        UINT16,
        SINT16,
        UINT32,
        SINT32,
        FLOAT16,
        FLOAT32,
    }

    companion object {
        @JvmStatic
        fun empty(): ResourceCatalog = ResourceCatalog(emptyList())
    }
}