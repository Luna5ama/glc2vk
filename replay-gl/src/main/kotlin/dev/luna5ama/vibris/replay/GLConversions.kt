package dev.luna5ama.vibris.replay

import dev.luna5ama.vibris.common.VkCompareOp
import dev.luna5ama.vibris.common.VkFilter
import dev.luna5ama.vibris.common.VkFormat
import dev.luna5ama.vibris.common.VkSamplerAddressMode
import dev.luna5ama.vibris.common.VkSamplerMipmapMode
import dev.luna5ama.glwrapper.base.GL_ALWAYS
import dev.luna5ama.glwrapper.base.GL_CLAMP_TO_BORDER
import dev.luna5ama.glwrapper.base.GL_CLAMP_TO_EDGE
import dev.luna5ama.glwrapper.base.GL_EQUAL
import dev.luna5ama.glwrapper.base.GL_GEQUAL
import dev.luna5ama.glwrapper.base.GL_GREATER
import dev.luna5ama.glwrapper.base.GL_LEQUAL
import dev.luna5ama.glwrapper.base.GL_LESS
import dev.luna5ama.glwrapper.base.GL_LINEAR
import dev.luna5ama.glwrapper.base.GL_LINEAR_MIPMAP_LINEAR
import dev.luna5ama.glwrapper.base.GL_LINEAR_MIPMAP_NEAREST
import dev.luna5ama.glwrapper.base.GL_MIRRORED_REPEAT
import dev.luna5ama.glwrapper.base.GL_MIRROR_CLAMP_TO_EDGE
import dev.luna5ama.glwrapper.base.GL_NEAREST
import dev.luna5ama.glwrapper.base.GL_NEAREST_MIPMAP_LINEAR
import dev.luna5ama.glwrapper.base.GL_NEAREST_MIPMAP_NEAREST
import dev.luna5ama.glwrapper.base.GL_NEVER
import dev.luna5ama.glwrapper.base.GL_NOTEQUAL
import dev.luna5ama.glwrapper.base.GL_REPEAT
import dev.luna5ama.glwrapper.enums.ImageFormat as GLImageFormat

fun VkFormat.toGLImageFormat(): GLImageFormat.Sized = when (this) {
    VkFormat.R8_UNORM -> GLImageFormat.R8_UN
    VkFormat.R8_SNORM -> GLImageFormat.R8_SN
    VkFormat.R8_UINT -> GLImageFormat.R8_UI
    VkFormat.R8_SINT -> GLImageFormat.R8_SI
    VkFormat.R16_UNORM -> GLImageFormat.R16_UN
    VkFormat.R16_SNORM -> GLImageFormat.R16_SN
    VkFormat.R16_UINT -> GLImageFormat.R16_UI
    VkFormat.R16_SINT -> GLImageFormat.R16_SI
    VkFormat.R16_SFLOAT -> GLImageFormat.R16_F
    VkFormat.R32_UINT -> GLImageFormat.R32_UI
    VkFormat.R32_SINT -> GLImageFormat.R32_SI
    VkFormat.R32_SFLOAT -> GLImageFormat.R32_F
    VkFormat.R8G8_UNORM -> GLImageFormat.R8G8_UN
    VkFormat.R8G8_SNORM -> GLImageFormat.R8G8_SN
    VkFormat.R8G8_UINT -> GLImageFormat.R8G8_UI
    VkFormat.R8G8_SINT -> GLImageFormat.R8G8_SI
    VkFormat.R16G16_UNORM -> GLImageFormat.R16G16_UN
    VkFormat.R16G16_SNORM -> GLImageFormat.R16G16_SN
    VkFormat.R16G16_UINT -> GLImageFormat.R16G16_UI
    VkFormat.R16G16_SINT -> GLImageFormat.R16G16_SI
    VkFormat.R16G16_SFLOAT -> GLImageFormat.R16G16_F
    VkFormat.R32G32_UINT -> GLImageFormat.R32G32_UI
    VkFormat.R32G32_SINT -> GLImageFormat.R32G32_SI
    VkFormat.R32G32_SFLOAT -> GLImageFormat.R32G32_F
    VkFormat.R8G8B8_UNORM -> GLImageFormat.R8G8B8_UN
    VkFormat.R8G8B8_SNORM -> GLImageFormat.R8G8B8_SN
    VkFormat.R8G8B8_UINT -> GLImageFormat.R8G8B8_UI
    VkFormat.R8G8B8_SINT -> GLImageFormat.R8G8B8_SI
    VkFormat.R16G16B16_UNORM -> GLImageFormat.R16G16B16_UN
    VkFormat.R16G16B16_SNORM -> GLImageFormat.R16G16B16_SN
    VkFormat.R16G16B16_UINT -> GLImageFormat.R16G16B16_UI
    VkFormat.R16G16B16_SINT -> GLImageFormat.R16G16B16_SI
    VkFormat.R16G16B16_SFLOAT -> GLImageFormat.R16G16B16_F
    VkFormat.R32G32B32_UINT -> GLImageFormat.R32G32B32_UI
    VkFormat.R32G32B32_SINT -> GLImageFormat.R32G32B32_SI
    VkFormat.R32G32B32_SFLOAT -> GLImageFormat.R32G32B32_F
    VkFormat.R8G8B8A8_UNORM -> GLImageFormat.R8G8B8A8_UN
    VkFormat.R8G8B8A8_SNORM -> GLImageFormat.R8G8B8A8_SN
    VkFormat.R8G8B8A8_UINT -> GLImageFormat.R8G8B8A8_UI
    VkFormat.R8G8B8A8_SINT -> GLImageFormat.R8G8B8A8_SI
    VkFormat.R16G16B16A16_UNORM -> GLImageFormat.R16G16B16A16_UN
    VkFormat.R16G16B16A16_SNORM -> GLImageFormat.R16G16B16A16_SN
    VkFormat.R16G16B16A16_UINT -> GLImageFormat.R16G16B16A16_UI
    VkFormat.R16G16B16A16_SINT -> GLImageFormat.R16G16B16A16_SI
    VkFormat.R16G16B16A16_SFLOAT -> GLImageFormat.R16G16B16A16_F
    VkFormat.R32G32B32A32_UINT -> GLImageFormat.R32G32B32A32_UI
    VkFormat.R32G32B32A32_SINT -> GLImageFormat.R32G32B32A32_SI
    VkFormat.R32G32B32A32_SFLOAT -> GLImageFormat.R32G32B32A32_F
    VkFormat.B10G11R11_UFLOAT_PACK32 -> GLImageFormat.R11G11B10_F
    VkFormat.A2B10G10R10_UNORM_PACK32 -> GLImageFormat.R10G10B10A2_UN
    VkFormat.A2B10G10R10_UINT_PACK32 -> GLImageFormat.R10G10B10A2_UI
    VkFormat.R8G8B8_SRGB -> GLImageFormat.R8G8B8_SRGB
    VkFormat.R8G8B8A8_SRGB -> GLImageFormat.R8G8B8A8_SRGB
    VkFormat.E5B9G9R9_UFLOAT_PACK32 -> GLImageFormat.R9G9B9E5_UN
    VkFormat.D16_UNORM -> GLImageFormat.Depth16
    VkFormat.D24_UNORM_S8_UINT -> GLImageFormat.Depth24Stencil8
    VkFormat.D32_SFLOAT -> GLImageFormat.Depth32F
    VkFormat.D32_SFLOAT_S8_UINT -> GLImageFormat.Depth32FStencil8
    VkFormat.S8_UINT -> GLImageFormat.Stencil8
    VkFormat.BC4_UNORM_BLOCK -> GLImageFormat.R_UN_RGTC1
    VkFormat.BC4_SNORM_BLOCK -> GLImageFormat.R_SN_RGTC1
    VkFormat.BC5_UNORM_BLOCK -> GLImageFormat.RG_UN_RGTC2
    VkFormat.BC5_SNORM_BLOCK -> GLImageFormat.RG_SN_RGTC2
    VkFormat.BC7_UNORM_BLOCK -> GLImageFormat.RGBA_UN_BPTC
    VkFormat.BC7_SRGB_BLOCK -> GLImageFormat.RGBA_SRGB_BPTC
    VkFormat.BC6H_SFLOAT_BLOCK -> GLImageFormat.RGB_SF_BPTC
    VkFormat.BC6H_UFLOAT_BLOCK -> GLImageFormat.RGB_UF_BPTC
    VkFormat.BC1_RGB_UNORM_BLOCK -> GLImageFormat.RGB_S3TC_DXT1
    VkFormat.BC1_RGBA_UNORM_BLOCK -> GLImageFormat.RGBA_S3TC_DXT1
    VkFormat.BC2_UNORM_BLOCK -> GLImageFormat.RGBA_S3TC_DXT3
    VkFormat.BC3_UNORM_BLOCK -> GLImageFormat.RGBA_S3TC_DXT5
    VkFormat.ETC2_R8G8B8_SRGB_BLOCK -> GLImageFormat.RGB_SRGB_C
    VkFormat.ETC2_R8G8B8A8_SRGB_BLOCK -> GLImageFormat.RGBA_SRGB_C
    else -> error("Unsupported GL replay image format: $this")
}

fun VkFilter.toGLFilter(): Int = when (this) {
    VkFilter.NEAREST -> GL_NEAREST
    VkFilter.LINEAR -> GL_LINEAR
}

fun glMinFilter(filter: VkFilter, mipmapMode: VkSamplerMipmapMode, mipmapped: Boolean): Int {
    if (!mipmapped) return filter.toGLFilter()
    return when (filter) {
        VkFilter.NEAREST -> when (mipmapMode) {
            VkSamplerMipmapMode.NEAREST -> GL_NEAREST_MIPMAP_NEAREST
            VkSamplerMipmapMode.LINEAR -> GL_NEAREST_MIPMAP_LINEAR
        }

        VkFilter.LINEAR -> when (mipmapMode) {
            VkSamplerMipmapMode.NEAREST -> GL_LINEAR_MIPMAP_NEAREST
            VkSamplerMipmapMode.LINEAR -> GL_LINEAR_MIPMAP_LINEAR
        }
    }
}

fun VkSamplerAddressMode.toGLWrapMode(): Int = when (this) {
    VkSamplerAddressMode.REPEAT -> GL_REPEAT
    VkSamplerAddressMode.MIRRORED_REPEAT -> GL_MIRRORED_REPEAT
    VkSamplerAddressMode.CLAMP_TO_EDGE -> GL_CLAMP_TO_EDGE
    VkSamplerAddressMode.CLAMP_TO_BORDER -> GL_CLAMP_TO_BORDER
    VkSamplerAddressMode.MIRROR_CLAMP_TO_EDGE -> GL_MIRROR_CLAMP_TO_EDGE
}

fun VkCompareOp.toGLCompareFunc(): Int = when (this) {
    VkCompareOp.NEVER -> GL_NEVER
    VkCompareOp.LESS -> GL_LESS
    VkCompareOp.EQUAL -> GL_EQUAL
    VkCompareOp.LESS_OR_EQUAL -> GL_LEQUAL
    VkCompareOp.GREATER -> GL_GREATER
    VkCompareOp.NOT_EQUAL -> GL_NOTEQUAL
    VkCompareOp.GREATER_OR_EQUAL -> GL_GEQUAL
    VkCompareOp.ALWAYS -> GL_ALWAYS
}
