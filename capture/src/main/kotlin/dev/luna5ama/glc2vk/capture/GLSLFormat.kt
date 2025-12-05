package dev.luna5ama.glc2vk.capture

import dev.luna5ama.glwrapper.enums.ImageFormat

fun glslFormatToImageFormat(formatStr: String): ImageFormat? {
    return when (formatStr) {
        "rgba32f" -> ImageFormat.R32G32B32A32_F
        "rgba32i" -> ImageFormat.R32G32B32A32_SI
        "rgba32ui" -> ImageFormat.R32G32B32A32_UI
        "rgba16" -> ImageFormat.R16G16B16A16_UN
        "rgba16_snorm" -> ImageFormat.R16G16B16A16_SN
        "rgba16f" -> ImageFormat.R16G16B16A16_F
        "rgba16i" -> ImageFormat.R16G16B16A16_SI
        "rgba16ui" -> ImageFormat.R16G16B16A16_UI
        "rgba8" -> ImageFormat.R8G8B8A8_UN
        "rgba8_snorm" -> ImageFormat.R8G8B8A8_SN
        "rgba8i" -> ImageFormat.R8G8B8A8_SI
        "rgba8ui" -> ImageFormat.R8G8B8A8_UI
        "rg32f" -> ImageFormat.R32G32_F
        "rg32i" -> ImageFormat.R32G32_SI
        "rg32ui" -> ImageFormat.R32G32_UI
        "rg16" -> ImageFormat.R16G16_UN
        "rg16_snorm" -> ImageFormat.R16G16_SN
        "rg16f" -> ImageFormat.R16G16_F
        "rg16i" -> ImageFormat.R16G16_SI
        "rg16ui" -> ImageFormat.R16G16_UI
        "rg8" -> ImageFormat.R8G8_UN
        "rg8_snorm" -> ImageFormat.R8G8_SN
        "rg8i" -> ImageFormat.R8G8_SI
        "rg8ui" -> ImageFormat.R8G8_UI
        "r32f" -> ImageFormat.R32_F
        "r32i" -> ImageFormat.R32_SI
        "r32ui" -> ImageFormat.R32_UI
        "r16" -> ImageFormat.R16_UN
        "r16_snorm" -> ImageFormat.R16_SN
        "r16f" -> ImageFormat.R16_F
        "r16i" -> ImageFormat.R16_SI
        "r16ui" -> ImageFormat.R16_UI
        "r8" -> ImageFormat.R8_UN
        "r8_snorm" -> ImageFormat.R8_SN
        "r8i" -> ImageFormat.R8_SI
        "r8ui" -> ImageFormat.R8_UI
        else -> null
    }
}