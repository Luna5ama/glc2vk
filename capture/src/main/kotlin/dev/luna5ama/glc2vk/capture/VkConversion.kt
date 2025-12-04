package dev.luna5ama.glc2vk.capture

import dev.luna5ama.glc2vk.common.*
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.ImageFormat

fun glImageTargetToVKImageViewType(target: Int): VkImageViewType = when (target) {
    GL_TEXTURE_1D -> VkImageViewType.`1D`
    GL_TEXTURE_2D -> VkImageViewType.`2D`
    GL_TEXTURE_3D -> VkImageViewType.`3D`
    GL_TEXTURE_CUBE_MAP -> VkImageViewType.CUBE
    GL_TEXTURE_1D_ARRAY -> VkImageViewType.`1D_ARRAY`
    GL_TEXTURE_2D_ARRAY -> VkImageViewType.`2D_ARRAY`
    GL_TEXTURE_CUBE_MAP_ARRAY -> VkImageViewType.CUBE_ARRAY
    else -> throw UnsupportedOperationException("Unsupported texture target: $target")
}

fun glFormatToVkFormat(format: ImageFormat): VkFormat = when (format) {
    // R
    ImageFormat.R8_UN -> VkFormat.R8_UNORM
    ImageFormat.R8_SN -> VkFormat.R8_SNORM
    ImageFormat.R8_UI -> VkFormat.R8_UINT
    ImageFormat.R8_SI -> VkFormat.R8_SINT

    ImageFormat.R16_UN -> VkFormat.R16_UNORM
    ImageFormat.R16_SN -> VkFormat.R16_SNORM
    ImageFormat.R16_UI -> VkFormat.R16_UINT
    ImageFormat.R16_SI -> VkFormat.R16_SINT
    ImageFormat.R16_F -> VkFormat.R16_SFLOAT

    ImageFormat.R32_UI -> VkFormat.R32_UINT
    ImageFormat.R32_SI -> VkFormat.R32_SINT
    ImageFormat.R32_F -> VkFormat.R32_SFLOAT

    // RG
    ImageFormat.R8G8_UN -> VkFormat.R8G8_UNORM
    ImageFormat.R8G8_SN -> VkFormat.R8G8_SNORM
    ImageFormat.R8G8_UI -> VkFormat.R8G8_UINT
    ImageFormat.R8G8_SI -> VkFormat.R8G8_SINT

    ImageFormat.R16G16_UN -> VkFormat.R16G16_UNORM
    ImageFormat.R16G16_SN -> VkFormat.R16G16_SNORM
    ImageFormat.R16G16_UI -> VkFormat.R16G16_UINT
    ImageFormat.R16G16_SI -> VkFormat.R16G16_SINT
    ImageFormat.R16G16_F -> VkFormat.R16G16_SFLOAT

    ImageFormat.R32G32_UI -> VkFormat.R32G32_UINT
    ImageFormat.R32G32_SI -> VkFormat.R32G32_SINT
    ImageFormat.R32G32_F -> VkFormat.R32G32_SFLOAT

    // RGB
    ImageFormat.R8G8B8_UN -> VkFormat.R8G8B8_UNORM
    ImageFormat.R8G8B8_SN -> VkFormat.R8G8B8_SNORM
    ImageFormat.R8G8B8_UI -> VkFormat.R8G8B8_UINT
    ImageFormat.R8G8B8_SI -> VkFormat.R8G8B8_SINT

    ImageFormat.R16G16B16_UN -> VkFormat.R16G16B16_UNORM
    ImageFormat.R16G16B16_SN -> VkFormat.R16G16B16_SNORM
    ImageFormat.R16G16B16_UI -> VkFormat.R16G16B16_UINT
    ImageFormat.R16G16B16_SI -> VkFormat.R16G16B16_SINT
    ImageFormat.R16G16B16_F -> VkFormat.R16G16B16_SFLOAT

    ImageFormat.R32G32B32_UI -> VkFormat.R32G32B32_UINT
    ImageFormat.R32G32B32_SI -> VkFormat.R32G32B32_SINT
    ImageFormat.R32G32B32_F -> VkFormat.R32G32B32_SFLOAT

    // RGBA
    ImageFormat.R8G8B8A8_UN -> VkFormat.R8G8B8A8_UNORM
    ImageFormat.R8G8B8A8_SN -> VkFormat.R8G8B8A8_SNORM
    ImageFormat.R8G8B8A8_UI -> VkFormat.R8G8B8A8_UINT
    ImageFormat.R8G8B8A8_SI -> VkFormat.R8G8B8A8_SINT

    ImageFormat.R16G16B16A16_UN -> VkFormat.R16G16B16A16_UNORM
    ImageFormat.R16G16B16A16_SN -> VkFormat.R16G16B16A16_SNORM
    ImageFormat.R16G16B16A16_UI -> VkFormat.R16G16B16A16_UINT
    ImageFormat.R16G16B16A16_SI -> VkFormat.R16G16B16A16_SINT
    ImageFormat.R16G16B16A16_F -> VkFormat.R16G16B16A16_SFLOAT

    ImageFormat.R32G32B32A32_UI -> VkFormat.R32G32B32A32_UINT
    ImageFormat.R32G32B32A32_SI -> VkFormat.R32G32B32A32_SINT
    ImageFormat.R32G32B32A32_F -> VkFormat.R32G32B32A32_SFLOAT

    // Packed / other
    ImageFormat.R11G11B10_F -> VkFormat.B10G11R11_UFLOAT_PACK32
    ImageFormat.R10G10B10A2_UN -> VkFormat.A2B10G10R10_UNORM_PACK32
    ImageFormat.R10G10B10A2_UI -> VkFormat.A2B10G10R10_UINT_PACK32
    ImageFormat.R8G8B8_SRGB -> VkFormat.R8G8B8_SRGB
    ImageFormat.R8G8B8A8_SRGB -> VkFormat.R8G8B8A8_SRGB
    ImageFormat.R9G9B9E5_UN -> VkFormat.E5B9G9R9_UFLOAT_PACK32

    // Depth / stencil
    ImageFormat.Depth16 -> VkFormat.D16_UNORM
    ImageFormat.Depth24 -> VkFormat.D24_UNORM_S8_UINT // map to combined where appropriate
    ImageFormat.Depth32 -> VkFormat.D32_SFLOAT
    ImageFormat.Depth32F -> VkFormat.D32_SFLOAT
    ImageFormat.Depth24Stencil8 -> VkFormat.D24_UNORM_S8_UINT
    ImageFormat.Depth32FStencil8 -> VkFormat.D32_SFLOAT_S8_UINT
    ImageFormat.Stencil8 -> VkFormat.S8_UINT

    // Compressed maps (use generic mappings where available)
    ImageFormat.R_UN_C -> VkFormat.BC4_UNORM_BLOCK
    ImageFormat.R_SN_RGTC1 -> VkFormat.BC4_SNORM_BLOCK
    ImageFormat.RG_UN_RGTC2 -> VkFormat.BC5_UNORM_BLOCK
    ImageFormat.RG_SN_RGTC2 -> VkFormat.BC5_SNORM_BLOCK
    ImageFormat.RGBA_UN_BPTC -> VkFormat.BC7_UNORM_BLOCK
    ImageFormat.RGBA_SRGB_BPTC -> VkFormat.BC7_SRGB_BLOCK
    ImageFormat.RGB_SF_BPTC -> VkFormat.BC6H_SFLOAT_BLOCK
    ImageFormat.RGB_UF_BPTC -> VkFormat.BC6H_UFLOAT_BLOCK
    ImageFormat.RGB_S3TC_DXT1 -> VkFormat.BC1_RGB_UNORM_BLOCK
    ImageFormat.RGBA_S3TC_DXT1 -> VkFormat.BC1_RGBA_UNORM_BLOCK
    ImageFormat.RGBA_S3TC_DXT3 -> VkFormat.BC2_UNORM_BLOCK
    ImageFormat.RGBA_S3TC_DXT5 -> VkFormat.BC3_UNORM_BLOCK
    ImageFormat.RGB_SRGB_C -> VkFormat.ETC2_R8G8B8_SRGB_BLOCK
    ImageFormat.RGBA_SRGB_C -> VkFormat.ETC2_R8G8B8A8_SRGB_BLOCK

    else -> throw UnsupportedOperationException("Unsupported image format: ${format::class}")
}

fun glMagFilterToVkFilter(magFilter: Int): VkFilter = when (magFilter) {
    GL_NEAREST -> VkFilter.NEAREST
    GL_LINEAR -> VkFilter.LINEAR
    else -> throw UnsupportedOperationException("Unsupported mag filter: $magFilter")
}

fun glMinFilterToVkFilter(minFilter: Int): VkFilter = when (minFilter) {
    GL_NEAREST, GL_NEAREST_MIPMAP_NEAREST, GL_NEAREST_MIPMAP_LINEAR -> VkFilter.NEAREST
    GL_LINEAR, GL_LINEAR_MIPMAP_NEAREST, GL_LINEAR_MIPMAP_LINEAR -> VkFilter.LINEAR
    else -> throw UnsupportedOperationException("Unsupported min filter: $minFilter")
}

fun glMinFilterTOVkSamplerMipmapMode(minFilter: Int): VkSamplerMipmapMode = when (minFilter) {
    GL_NEAREST, GL_LINEAR -> VkSamplerMipmapMode.NEAREST
    GL_NEAREST_MIPMAP_NEAREST, GL_LINEAR_MIPMAP_NEAREST -> VkSamplerMipmapMode.NEAREST
    GL_NEAREST_MIPMAP_LINEAR, GL_LINEAR_MIPMAP_LINEAR -> VkSamplerMipmapMode.LINEAR
    else -> throw UnsupportedOperationException("Unsupported min filter: $minFilter")
}

fun glWarpModeToVkSamplerAddressMode(wrapMode: Int): VkSamplerAddressMode = when (wrapMode) {
    GL_REPEAT -> VkSamplerAddressMode.REPEAT
    GL_MIRRORED_REPEAT -> VkSamplerAddressMode.MIRRORED_REPEAT
    GL_CLAMP_TO_EDGE -> VkSamplerAddressMode.CLAMP_TO_EDGE
    GL_CLAMP_TO_BORDER -> VkSamplerAddressMode.CLAMP_TO_BORDER
    GL_MIRROR_CLAMP_TO_EDGE -> VkSamplerAddressMode.MIRROR_CLAMP_TO_EDGE
    else -> throw UnsupportedOperationException("Unsupported wrap mode: $wrapMode")
}

fun glCompareFuncToVkCompareOp(compareFunc: Int): VkCompareOp = when (compareFunc) {
    GL_NEVER -> VkCompareOp.NEVER
    GL_LESS -> VkCompareOp.LESS
    GL_EQUAL -> VkCompareOp.EQUAL
    GL_LEQUAL -> VkCompareOp.LESS_OR_EQUAL
    GL_GREATER -> VkCompareOp.GREATER
    GL_NOTEQUAL -> VkCompareOp.NOT_EQUAL
    GL_GEQUAL -> VkCompareOp.GREATER_OR_EQUAL
    GL_ALWAYS -> VkCompareOp.ALWAYS
    else -> throw UnsupportedOperationException("Unsupported compare func: $compareFunc")
}