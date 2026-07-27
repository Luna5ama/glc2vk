package dev.luna5ama.vibris.capture

import dev.luna5ama.glwrapper.enums.ImageFormat
import kotlin.test.Test
import kotlin.test.assertSame

class VkConversionTest {
    @Test
    fun convertsUncompressedRgbCaptureFormatsToRgba() {
        val mappings = mapOf(
            ImageFormat.R8G8B8_UN to ImageFormat.R8G8B8A8_UN,
            ImageFormat.R8G8B8_SN to ImageFormat.R8G8B8A8_SN,
            ImageFormat.R8G8B8_UI to ImageFormat.R8G8B8A8_UI,
            ImageFormat.R8G8B8_SI to ImageFormat.R8G8B8A8_SI,
            ImageFormat.R8G8B8_SRGB to ImageFormat.R8G8B8A8_SRGB,
            ImageFormat.R16G16B16_UN to ImageFormat.R16G16B16A16_UN,
            ImageFormat.R16G16B16_SN to ImageFormat.R16G16B16A16_SN,
            ImageFormat.R16G16B16_UI to ImageFormat.R16G16B16A16_UI,
            ImageFormat.R16G16B16_SI to ImageFormat.R16G16B16A16_SI,
            ImageFormat.R16G16B16_F to ImageFormat.R16G16B16A16_F,
            ImageFormat.R32G32B32_UI to ImageFormat.R32G32B32A32_UI,
            ImageFormat.R32G32B32_SI to ImageFormat.R32G32B32A32_SI,
            ImageFormat.R32G32B32_F to ImageFormat.R32G32B32A32_F,
        )

        mappings.forEach { (source, expected) ->
            assertSame(expected, captureImageFormat(source))
        }
    }

    @Test
    fun preservesNonRgbCaptureFormats() {
        assertSame(ImageFormat.R8G8B8A8_UN, captureImageFormat(ImageFormat.R8G8B8A8_UN))
    }
}
