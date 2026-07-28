package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL12C
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL21C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL42C
import org.lwjgl.opengl.GL43C
import org.lwjgl.opengl.GL45C
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.roundToInt

object GlShaderDebugResourceDumper : ShaderDebugResourceDumper {
    override fun dumpStorageBuffer(buffer: StorageBufferInfo, output: Path) = buildJsonObject {
        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT)
        val size = GL45C.glGetNamedBufferParameteri64(buffer.glId, GL15C.GL_BUFFER_SIZE)
        requireAllocation(size, "SSBO")
        val data = MemoryUtil.memAlloc(size.toInt())
        try {
            GL45C.glGetNamedBufferSubData(buffer.glId, 0, data)
            writeBytes(output, data)
        } finally {
            MemoryUtil.memFree(data)
        }
        put("success", true)
        put("path", output.toAbsolutePath().toString())
        put("bufferId", buffer.glId)
        put("totalBytes", size)
    }

    override fun dumpTexture(textureId: Int, output: Path, raw: Boolean) = withPackState {
        val target = GL45C.glGetTextureParameteri(textureId, GL45C.GL_TEXTURE_TARGET)
        require(target == GL11C.GL_TEXTURE_2D || target == GL12C.GL_TEXTURE_3D) {
            "Unsupported texture target: 0x${target.toString(16)}"
        }
        val width = GL45C.glGetTextureLevelParameteri(textureId, 0, GL11C.GL_TEXTURE_WIDTH)
        val height = GL45C.glGetTextureLevelParameteri(textureId, 0, GL11C.GL_TEXTURE_HEIGHT)
        val depth = if (target == GL12C.GL_TEXTURE_3D) {
            GL45C.glGetTextureLevelParameteri(textureId, 0, GL12C.GL_TEXTURE_DEPTH)
        } else {
            1
        }
        require(width > 0 && height > 0 && depth > 0) { "Texture $textureId has no level 0 image" }
        val internalFormat = GL45C.glGetTextureLevelParameteri(textureId, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT)
        val redType = GL45C.glGetTextureLevelParameteri(textureId, 0, GL30C.GL_TEXTURE_RED_TYPE)
        val pixelKind = PixelKind.fromComponentType(redType)
        val components = textureComponents(textureId)
        val totalBytes = allocationSize(width, height, depth, components)
        val data = MemoryUtil.memAlloc(totalBytes).order(ByteOrder.nativeOrder())
        try {
            GL45C.glGetTextureImage(textureId, 0, pixelKind.format(components), pixelKind.type, data)
            if (raw) writeBytes(output, data) else writePng(output, data, width, height, depth, components, pixelKind)
        } finally {
            MemoryUtil.memFree(data)
        }
        buildJsonObject {
            put("textureId", textureId)
            put("width", width)
            put("height", height)
            if (depth > 1) put("depth", depth)
            put("internalFormat", internalFormat)
            put("formatName", internalFormatName(internalFormat))
            put("success", true)
            put("path", if (!raw && depth > 1) layerWildcard(output) else output.toAbsolutePath().toString())
            if (raw) {
                put("components", components)
                put("bytesPerComponent", BYTES_PER_COMPONENT)
                put("pixelType", pixelKind.label)
                put("totalBytes", totalBytes)
            } else if (depth > 1) {
                put("layers", depth)
            }
        }
    }

    private fun writePng(
        output: Path,
        data: ByteBuffer,
        width: Int,
        height: Int,
        depth: Int,
        components: Int,
        kind: PixelKind
    ) {
        Files.createDirectories(output.parent)
        layerPaths(output, depth).forEachIndexed { layer, path ->
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = ((layer * height + y) * width + x) * components
                    val red = kind.channel(data, pixel)
                    val green = if (components > 1) kind.channel(data, pixel + 1) else red
                    val blue = if (components > 2) kind.channel(data, pixel + 2) else red
                    val alpha = if (components > 3) kind.channel(data, pixel + 3) else 255
                    image.setRGB(x, height - y - 1, alpha shl 24 or (red shl 16) or (green shl 8) or blue)
                }
            }
            check(ImageIO.write(image, "png", path.toFile())) { "No PNG writer available" }
        }
    }

    private fun layerPaths(output: Path, depth: Int): List<Path> {
        if (depth == 1) return listOf(output)
        val fileName = output.fileName.toString()
        val base = fileName.substringBeforeLast('.', fileName)
        return List(depth) { layer -> output.resolveSibling("${base}_layer$layer.png") }
    }

    private fun layerWildcard(output: Path): String {
        val fileName = output.fileName.toString()
        val base = fileName.substringBeforeLast('.', fileName)
        return output.toAbsolutePath().parent.toString() + output.fileSystem.separator + "${base}_layer*.png"
    }

    private fun writeBytes(output: Path, data: ByteBuffer) {
        Files.createDirectories(output.parent)
        val bytes = ByteArray(data.capacity())
        data.duplicate().clear().get(bytes)
        Files.write(output, bytes)
    }

    private fun allocationSize(width: Int, height: Int, depth: Int, components: Int): Int {
        val pixels = Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), depth.toLong())
        val bytes = Math.multiplyExact(pixels, (components * BYTES_PER_COMPONENT).toLong())
        requireAllocation(bytes, "texture")
        return bytes.toInt()
    }

    private fun requireAllocation(bytes: Long, resource: String) {
        require(bytes in 0..MAX_DUMP_BYTES) { "$resource dump is too large: $bytes bytes" }
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

    private fun textureComponents(textureId: Int): Int {
        val componentSizes = intArrayOf(
            GL11C.GL_TEXTURE_RED_SIZE,
            GL11C.GL_TEXTURE_GREEN_SIZE,
            GL11C.GL_TEXTURE_BLUE_SIZE,
            GL11C.GL_TEXTURE_ALPHA_SIZE
        )
        return componentSizes.count {
            GL45C.glGetTextureLevelParameteri(textureId, 0, it) > 0
        }.coerceAtLeast(1)
    }

    private fun internalFormatName(format: Int) = "0x${format.toString(16).uppercase()}"

    private enum class PixelKind(val type: Int, val label: String) {
        FLOAT(GL11C.GL_FLOAT, "float"),
        SIGNED(GL11C.GL_INT, "int"),
        UNSIGNED(GL11C.GL_UNSIGNED_INT, "uint");

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


    private const val BYTES_PER_COMPONENT = 4
    private const val MAX_DUMP_BYTES = 256L * 1024L * 1024L
}