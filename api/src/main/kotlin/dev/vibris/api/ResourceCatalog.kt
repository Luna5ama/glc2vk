package dev.vibris.api

@JvmRecord
data class ResourceCatalog(@field:DefensiveSnapshot val resources: List<ResourceDescriptor>) {

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
        val category: String,
        val textureTarget: String,
        val channelLayout: String,
        val numericClass: String,
        val componentBits: Int,
        val readbackFormat: String,
        val readbackType: String,
    ) {
        init {
            require(
                width >= 0 &&
                    height >= 0 &&
                    depth >= 0 &&
                    mipLevels >= 0 &&
                    layers >= 0 &&
                    channelCount >= 0 &&
                    componentBits >= 0 &&
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
            "",
            "",
            "",
            "",
            0,
            "",
            "",
        )

        constructor(
            logicalName: String,
            kind: ResourceKind,
            width: Int,
            height: Int,
            depth: Int,
            mipLevels: Int,
            layers: Int,
            internalFormat: String,
            channelCount: Int,
            scalarType: ScalarType,
            byteSize: Long,
            frameId: Long,
            semanticLabel: String,
        ) : this(
            logicalName, kind, width, height, depth, mipLevels, layers, internalFormat, channelCount,
            scalarType, byteSize, frameId, semanticLabel, "", "", "", "", 0, "", "",
        )
    }

    enum class ResourceKind {
        FINAL_FRAMEBUFFER,
        TEXTURE,
        BUFFER,
        PATCHED_SHADERS,
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
        fun empty(): ResourceCatalog = ResourceCatalog(java.util.List.of())
    }
}
