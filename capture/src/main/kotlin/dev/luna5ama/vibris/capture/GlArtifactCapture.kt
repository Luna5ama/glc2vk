package dev.luna5ama.vibris.capture

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL12C
import org.lwjgl.opengl.GL14C
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL21C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL42C
import org.lwjgl.opengl.GL45C
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.io.OutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import javax.imageio.ImageIO
import kotlin.math.roundToInt

data class GlCaptureMetadata(
    val width: Int,
    val height: Int,
    val depth: Int,
    val internalFormat: String,
    val channelCount: Int,
    val scalarType: ResourceCatalog.ScalarType,
    val byteSize: Long,
)

object GlArtifactCapture {
    @JvmStatic
    fun captureBuffer(bufferId: Int, output: OutputStream): GlCaptureMetadata {
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT)
        val size = GL45C.glGetNamedBufferParameteri64(bufferId, GL15C.GL_BUFFER_SIZE)
        requireAllocation(size, "buffer")
        val data = MemoryUtil.memAlloc(size.toInt())
        try {
            GL45C.glGetNamedBufferSubData(bufferId, 0, data)
            writeDirect(output, data)
        } finally {
            MemoryUtil.memFree(data as Buffer)
        }
        return GlCaptureMetadata(0, 0, 0, "binary", 0, ResourceCatalog.ScalarType.UINT8, size)
    }

    @JvmStatic
    fun captureTexture(
        textureId: Int,
        mipLevel: Int,
        layer: Int,
        format: CapturePlan.ArtifactFormat,
        output: OutputStream,
    ): GlCaptureMetadata = withPackState {
        require(format == CapturePlan.ArtifactFormat.PNG || format == CapturePlan.ArtifactFormat.RAW) {
            "Textures support PNG or RAW capture"
        }
        require(layer == 0) { "2D textures only expose layer 0" }
        val layout = textureLayout(textureId, mipLevel)
        val metadata = layout.metadata
        requireAllocation(metadata.byteSize, "texture")
        val totalBytes = metadata.byteSize.toInt()
        val data = MemoryUtil.memAlloc(totalBytes).order(ByteOrder.nativeOrder())
        try {
            GL42C.glMemoryBarrier(GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT)
            GL45C.glGetTextureImage(textureId, mipLevel, layout.pixelFormat, layout.pixelKind.type, data)
            if (format == CapturePlan.ArtifactFormat.RAW) writeDirect(output, data)
            else writePng(
                output,
                data,
                metadata.width,
                metadata.height,
                metadata.channelCount,
                layout.pixelKind,
            )
        } finally {
            MemoryUtil.memFree(data as Buffer)
        }
        metadata
    }

    @JvmStatic
    fun describeTexture(textureId: Int, mipLevel: Int): GlCaptureMetadata =
        textureLayout(textureId, mipLevel).metadata

    private fun writePng(
        output: OutputStream,
        data: ByteBuffer,
        width: Int,
        height: Int,
        components: Int,
        kind: PixelKind,
    ) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = (y * width + x) * components
                val red = kind.channel(data, pixel)
                val green = if (components > 1) kind.channel(data, pixel + 1) else red
                val blue = if (components > 2) kind.channel(data, pixel + 2) else red
                val alpha = if (components > 3) kind.channel(data, pixel + 3) else 255
                image.setRGB(x, height - y - 1, alpha shl 24 or (red shl 16) or (green shl 8) or blue)
            }
        }
        check(ImageIO.write(image, "png", output)) { "No PNG writer is available" }
    }

    private fun writeDirect(output: OutputStream, data: ByteBuffer) {
        val channel = Channels.newChannel(output)
        val bytes = data.duplicate().clear()
        while (bytes.hasRemaining()) channel.write(bytes)
    }

    private fun allocationSize(width: Int, height: Int, components: Int): Long {
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        return Math.multiplyExact(pixels, (components * BYTES_PER_COMPONENT).toLong())
    }

    private fun requireAllocation(bytes: Long, resource: String) {
        require(bytes in 0..MAX_CAPTURE_BYTES) { "$resource capture is too large: $bytes bytes" }
    }

    private fun textureComponents(textureId: Int, mipLevel: Int): Int {
        val componentSizes = intArrayOf(
            GL11C.GL_TEXTURE_RED_SIZE,
            GL11C.GL_TEXTURE_GREEN_SIZE,
            GL11C.GL_TEXTURE_BLUE_SIZE,
            GL11C.GL_TEXTURE_ALPHA_SIZE,
        )
        return componentSizes.count {
            GL45C.glGetTextureLevelParameteri(textureId, mipLevel, it) > 0
        }.coerceAtLeast(1)
    }

    private fun textureLayout(textureId: Int, mipLevel: Int): TextureLayout {
        val target = GL45C.glGetTextureParameteri(textureId, GL45C.GL_TEXTURE_TARGET)
        require(target == GL11C.GL_TEXTURE_2D) { "Only 2D texture capture is supported" }
        val width = GL45C.glGetTextureLevelParameteri(textureId, mipLevel, GL11C.GL_TEXTURE_WIDTH)
        val height = GL45C.glGetTextureLevelParameteri(textureId, mipLevel, GL11C.GL_TEXTURE_HEIGHT)
        require(width > 0 && height > 0) { "Texture has no requested mip image" }
        val internal = GL45C.glGetTextureLevelParameteri(textureId, mipLevel, GL11C.GL_TEXTURE_INTERNAL_FORMAT)
        val depthTexture = GL45C.glGetTextureLevelParameteri(
            textureId,
            mipLevel,
            GL14C.GL_TEXTURE_DEPTH_SIZE,
        ) > 0
        val redType = GL45C.glGetTextureLevelParameteri(textureId, mipLevel, GL30C.GL_TEXTURE_RED_TYPE)
        val pixelKind = if (depthTexture) PixelKind.FLOAT else PixelKind.fromComponentType(redType)
        val components = if (depthTexture) 1 else textureComponents(textureId, mipLevel)
        val byteSize = allocationSize(width, height, components)
        val metadata = GlCaptureMetadata(
            width, height, 1, "0x${internal.toString(16).uppercase()}", components,
            pixelKind.scalarType, byteSize,
        )
        val pixelFormat = if (depthTexture) GL11C.GL_DEPTH_COMPONENT else pixelKind.format(components)
        return TextureLayout(metadata, pixelFormat, pixelKind)
    }

    private fun <T> withPackState(action: () -> T): T {
        val buffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING)
        val alignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT)
        val swapBytes = GL11C.glGetInteger(GL11C.GL_PACK_SWAP_BYTES)
        val lsbFirst = GL11C.glGetInteger(GL11C.GL_PACK_LSB_FIRST)
        val rowLength = GL11C.glGetInteger(GL12C.GL_PACK_ROW_LENGTH)
        val imageHeight = GL11C.glGetInteger(GL12C.GL_PACK_IMAGE_HEIGHT)
        val skipPixels = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS)
        val skipRows = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS)
        val skipImages = GL11C.glGetInteger(GL12C.GL_PACK_SKIP_IMAGES)
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0)
        GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1)
        GL11C.glPixelStorei(GL11C.GL_PACK_SWAP_BYTES, 0)
        GL11C.glPixelStorei(GL11C.GL_PACK_LSB_FIRST, 0)
        GL11C.glPixelStorei(GL12C.GL_PACK_ROW_LENGTH, 0)
        GL11C.glPixelStorei(GL12C.GL_PACK_IMAGE_HEIGHT, 0)
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0)
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0)
        GL11C.glPixelStorei(GL12C.GL_PACK_SKIP_IMAGES, 0)
        try {
            return action()
        } finally {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, buffer)
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, alignment)
            GL11C.glPixelStorei(GL11C.GL_PACK_SWAP_BYTES, swapBytes)
            GL11C.glPixelStorei(GL11C.GL_PACK_LSB_FIRST, lsbFirst)
            GL11C.glPixelStorei(GL12C.GL_PACK_ROW_LENGTH, rowLength)
            GL11C.glPixelStorei(GL12C.GL_PACK_IMAGE_HEIGHT, imageHeight)
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, skipPixels)
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, skipRows)
            GL11C.glPixelStorei(GL12C.GL_PACK_SKIP_IMAGES, skipImages)
        }
    }

    private enum class PixelKind(
        val type: Int,
        val scalarType: ResourceCatalog.ScalarType,
    ) {
        FLOAT(GL11C.GL_FLOAT, ResourceCatalog.ScalarType.FLOAT32),
        SIGNED(GL11C.GL_INT, ResourceCatalog.ScalarType.SINT32),
        UNSIGNED(GL11C.GL_UNSIGNED_INT, ResourceCatalog.ScalarType.UINT32);

        fun format(components: Int): Int {
            val integer = this != FLOAT
            return when (components) {
                1 -> if (integer) GL30C.GL_RED_INTEGER else GL11C.GL_RED
                2 -> if (integer) GL30C.GL_RG_INTEGER else GL30C.GL_RG
                3 -> if (integer) GL30C.GL_RGB_INTEGER else GL11C.GL_RGB
                else -> if (integer) GL30C.GL_RGBA_INTEGER else GL11C.GL_RGBA
            }
        }

        fun channel(data: ByteBuffer, component: Int): Int {
            val offset = component * BYTES_PER_COMPONENT
            val normalized = when (this) {
                FLOAT -> data.getFloat(offset).coerceIn(0.0f, 1.0f)
                SIGNED -> (data.getInt(offset).toDouble() / Int.MAX_VALUE).coerceIn(0.0, 1.0).toFloat()
                UNSIGNED -> ((data.getInt(offset).toLong() and 0xffffffffL).toDouble() / 0xffffffffL).toFloat()
            }
            return (normalized * 255.0f).roundToInt()
        }

        companion object {
            fun fromComponentType(type: Int) = when (type) {
                GL11C.GL_INT -> SIGNED
                GL11C.GL_UNSIGNED_INT -> UNSIGNED
                else -> FLOAT
            }
        }
    }

    private data class TextureLayout(
        val metadata: GlCaptureMetadata,
        val pixelFormat: Int,
        val pixelKind: PixelKind,
    )

    private const val BYTES_PER_COMPONENT = 4
    private const val MAX_CAPTURE_BYTES = 256L * 1024L * 1024L
}