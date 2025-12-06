package dev.luna5ama.glc2vk.common

import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.memcpy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.io.path.*

@Serializable
sealed interface Command {
    @Serializable
    data class DispatchCommand(
        val x: Int,
        val y: Int,
        val z: Int
    ) : Command

    @Serializable
    data class DispatchIndirectCommand(
        val bufferIndex: Int,
        val offset: Long
    ) : Command
}

@Serializable
enum class ImageDataType {
    COLOR,
    DEPTH,
    STENCIL,
    DEPTH_STENCIL
}

@Serializable
data class ImageMetadata(
    val name: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val mipLevels: Int,
    val arrayLayers: Int,
    val format: VkFormat,
    val dataType: ImageDataType,
    val viewType: VkImageViewType,
    val levelDataSizes: List<Long>
)

@Serializable
data class BufferMetadata(
    val name: String,
    val size: Long
)

@Serializable
data class SamplerInfo(
    val magFilter: VkFilter,
    val minFilter: VkFilter,
    val mipmapMode: VkSamplerMipmapMode,
    val addressModeU: VkSamplerAddressMode,
    val addressModeV: VkSamplerAddressMode,
    val addressModeW: VkSamplerAddressMode,
    val mipLodBias: Float,
    val anisotropyEnable: Boolean,
    val maxAnisotropy: Float,
    val compareEnable: Boolean,
    val compareOp: VkCompareOp,
    val minLod: Float,
    val maxLod: Float,
    val boarderColorR: Float,
    val boarderColorG: Float,
    val boarderColorB: Float,
    val boarderColorA: Float,
    val unnormalizedCoordinates: Boolean
)

@Serializable
data class SamplerBinding(
    val name: String,
    val imageIndex: Int,
    val set: Int,
    val binding: Int,
    val samplerInfo: SamplerInfo
)

@Serializable
data class ImageBinding(
    val name: String,
    val imageIndex: Int,
    val set: Int,
    val binding: Int,
    val format: VkFormat,
)

@Serializable
data class BufferBinding(
    val name: String,
    val bufferIndex: Int,
    val set: Int,
    val binding: Int,
    val offset: Long,
)

@Serializable
data class CaptureMetadata(
    val images: List<ImageMetadata>,
    val buffers: List<BufferMetadata>,
    val samplerBindings: List<SamplerBinding>,
    val imageBindings: List<ImageBinding>,
    val storageBufferBindings: List<BufferBinding>,
    val uniformBufferBindings: List<BufferBinding>,
    val command: Command
)

class ImageData(
    val levels: List<Arr>
) {
    fun free() {
        for (level in levels) {
            level.free()
        }
    }
}

class CaptureData(
    val metadata: CaptureMetadata,
    val imageData: List<ImageData>,
    val bufferData: List<Arr>,
) {
    fun free() {
        for (image in imageData) {
            image.free()
        }
        for (buffer in bufferData) {
            buffer.free()
        }
    }

    companion object {
        fun save(outputPath: Path, capture: CaptureData, block: () -> Unit) {
//            thread(true) {
                try {
                    println("Saving resource capture")
                    @OptIn(ExperimentalSerializationApi::class)
                    val jsonInstance = Json {
                        prettyPrint = true
                        prettyPrintIndent = "    "
                    }
                    println("Creating output directory: $outputPath")
                    outputPath.createDirectories()

                    println("Deleting existing resource capture if exists")
                    val resourceCapturePath = outputPath.resolve("resources.zip.xz")
                    resourceCapturePath.deleteIfExists()
                    println("Writing metadata")
                    val metadataPath = outputPath.resolve("resource_metadata.json")
                    val jsonStr = jsonInstance.encodeToString(capture.metadata)
                    metadataPath.writeText(jsonStr)

                    println("Writing resource data to ${resourceCapturePath.absolutePathString()} using 7z")
                    val proc = ProcessBuilder()
                        .command("7z", "a", "-mx1", resourceCapturePath.absolutePathString(), "-si")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()

                    println("Writing zip entries")
                    ZipOutputStream(proc.outputStream).use { zipOutput ->
                        zipOutput.setMethod(ZipOutputStream.STORED)
                        fun writeEntry(name: String, data: ByteArray) {
                            val entry = ZipEntry(name)
                            entry.size = data.size.toLong()
                            entry.compressedSize = data.size.toLong()
                            val crc32 = CRC32()
                            crc32.update(data)
                            entry.crc = crc32.value
                            zipOutput.putNextEntry(entry)
                            zipOutput.write(data)
                            zipOutput.closeEntry()
                        }

                        fun writeEntry(name: String, data: Arr, len: Long) {
                            println("Writing entry $name, size=${data.len}, ptr=${"0x%016X".format(data.ptr.address)}")
                            val byteArray = ByteArray(len.toInt())
                            memcpy(data.ptr, 0L, byteArray, 0L, len)
                            writeEntry(name, byteArray)
                        }

                        (capture.metadata.images zip capture.imageData).forEachIndexed { imageIndex, (metadata, data) ->
                            data.levels.forEachIndexed { level, data ->
                                writeEntry("image_${imageIndex}_$level.bin", data, metadata.levelDataSizes[level])
                            }
                        }
                        capture.metadata.buffers.forEachIndexed { i, _ ->
                            writeEntry("buffer_$i.bin", capture.bufferData[i], capture.metadata.buffers[i].size)
                        }
                    }
                    proc.waitFor()
                } finally {
                    capture.free()
                }

                block()
//            }
        }

        fun load(inputPath: Path): CaptureData {
            val metadataPath = inputPath.resolve("resource_metadata.json")
            val metadata = Json.decodeFromString<CaptureMetadata>(metadataPath.readText())

            val resourcesPath = inputPath.resolve("resources.zip.xz")

            val proc = ProcessBuilder()
                .command("7z", "e", resourcesPath.absolutePathString(), "-so")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

            val imageDataBytes = mutableMapOf<String, ByteArray>()
            val bufferDataBytes = mutableMapOf<String, ByteArray>()

            ZipInputStream(proc.inputStream).use { zipInput ->
                var entry = zipInput.nextEntry
                while (entry != null) {
                    when {
                        entry.name.startsWith("image_") -> imageDataBytes[entry.name] = zipInput.readBytes()
                        entry.name.startsWith("buffer_") -> bufferDataBytes[entry.name] = zipInput.readBytes()
                        else -> error("Got unexpected file ${entry.name} in resource capture")
                    }
                    entry = zipInput.nextEntry
                }
            }

            fun ByteArray.toArr(): Arr {
                val arr = Arr.malloc(this.size.toLong())
                memcpy(this, 0L, arr.ptr, 0L, this.size.toLong())
                return arr
            }

            val imageData = metadata.images.mapIndexed { i, imageMeta ->
                val levels = imageMeta.levelDataSizes.mapIndexed { levelIndex, levelSize ->
                    imageDataBytes["image_${i}_$levelIndex.bin"]!!.toArr()
                }
                ImageData(levels)
            }
            val bufferData = metadata.buffers.mapIndexed { i, bufferMeta ->
                bufferDataBytes["buffer_$i.bin"]!!.toArr()
            }
            return CaptureData(
                metadata,
                imageData,
                bufferData
            )
        }
    }
}