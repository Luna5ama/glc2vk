package dev.luna5ama.glc2vk.common

import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.memcpy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.io.path.*

@Serializable
sealed interface Command {
    @Serializable
    data class DispatchCommand(
        val x: Int,
        val y: Int,
        val z: Int,
        val shaderIndex: Int = 0,
        val debugLabels: List<String> = emptyList(),
        val samplerBindings: List<SamplerBinding> = emptyList(),
        val imageBindings: List<ImageBinding> = emptyList(),
        val storageBufferBindings: List<BufferBinding> = emptyList(),
        val uniformBufferBindings: List<BufferBinding> = emptyList(),
        val defaultUniformBindings: List<DefaultUniformBinding> = emptyList()
    ) : Command

    @Serializable
    data class DispatchIndirectCommand(
        val bufferIndex: Int,
        val offset: Long,
        val shaderIndex: Int = 0,
        val debugLabels: List<String> = emptyList(),
        val samplerBindings: List<SamplerBinding> = emptyList(),
        val imageBindings: List<ImageBinding> = emptyList(),
        val storageBufferBindings: List<BufferBinding> = emptyList(),
        val uniformBufferBindings: List<BufferBinding> = emptyList(),
        val defaultUniformBindings: List<DefaultUniformBinding> = emptyList()
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
data class DefaultUniformBinding(
    val name: String,
    val type: String,
    val bufferIndex: Int,
    val offset: Long,
    val arraySize: Int = 1
)

@Serializable
data class ShaderMetadata(
    val passName: String? = null,
    val programType: String? = null,
    val sourcePath: String? = null,
    val stage: String = "compute"
)

@Serializable
data class CaptureMetadata(
    val images: List<ImageMetadata>,
    val buffers: List<BufferMetadata>,
    val samplerBindings: List<SamplerBinding>,
    val imageBindings: List<ImageBinding>,
    val storageBufferBindings: List<BufferBinding>,
    val uniformBufferBindings: List<BufferBinding>,
    val command: Command? = null,
    val commands: List<Command> = emptyList(),
    val shaderCount: Int = 1,
    val shaders: List<ShaderMetadata> = emptyList()
) {
    fun commandsForReplay(): List<Command> {
        val sourceCommands = commands.ifEmpty { command?.let(::listOf).orEmpty() }
        return sourceCommands.map { command ->
            if (command.hasBindings()) {
                command
            } else {
                command.withBindings(
                    samplerBindings = samplerBindings,
                    imageBindings = imageBindings,
                    storageBufferBindings = storageBufferBindings,
                    uniformBufferBindings = uniformBufferBindings,
                    defaultUniformBindings = emptyList()
                )
            }
        }
    }

    fun allSamplerBindings(): List<SamplerBinding> = commandsForReplay().flatMap { it.samplerBindings() }.distinct()

    fun allImageBindings(): List<ImageBinding> = commandsForReplay().flatMap { it.imageBindings() }.distinct()

    fun allStorageBufferBindings(): List<BufferBinding> = commandsForReplay().flatMap { it.storageBufferBindings() }.distinct()

    fun allUniformBufferBindings(): List<BufferBinding> = commandsForReplay().flatMap { it.uniformBufferBindings() }.distinct()

    fun shaderMetadata(shaderIndex: Int): ShaderMetadata {
        return shaders.getOrNull(shaderIndex) ?: ShaderMetadata()
    }
}

fun Command.shaderIndex(): Int = when (this) {
    is Command.DispatchCommand -> shaderIndex
    is Command.DispatchIndirectCommand -> shaderIndex
}

fun Command.debugLabels(): List<String> = when (this) {
    is Command.DispatchCommand -> debugLabels
    is Command.DispatchIndirectCommand -> debugLabels
}

fun Command.samplerBindings(): List<SamplerBinding> = when (this) {
    is Command.DispatchCommand -> samplerBindings
    is Command.DispatchIndirectCommand -> samplerBindings
}

fun Command.imageBindings(): List<ImageBinding> = when (this) {
    is Command.DispatchCommand -> imageBindings
    is Command.DispatchIndirectCommand -> imageBindings
}

fun Command.storageBufferBindings(): List<BufferBinding> = when (this) {
    is Command.DispatchCommand -> storageBufferBindings
    is Command.DispatchIndirectCommand -> storageBufferBindings
}

fun Command.uniformBufferBindings(): List<BufferBinding> = when (this) {
    is Command.DispatchCommand -> uniformBufferBindings
    is Command.DispatchIndirectCommand -> uniformBufferBindings
}

fun Command.defaultUniformBindings(): List<DefaultUniformBinding> = when (this) {
    is Command.DispatchCommand -> defaultUniformBindings
    is Command.DispatchIndirectCommand -> defaultUniformBindings
}

fun Command.hasBindings(): Boolean {
    return samplerBindings().isNotEmpty() ||
            imageBindings().isNotEmpty() ||
            storageBufferBindings().isNotEmpty() ||
            uniformBufferBindings().isNotEmpty() ||
            defaultUniformBindings().isNotEmpty()
}

fun Command.withBindings(
    samplerBindings: List<SamplerBinding>,
    imageBindings: List<ImageBinding>,
    storageBufferBindings: List<BufferBinding>,
    uniformBufferBindings: List<BufferBinding>,
    defaultUniformBindings: List<DefaultUniformBinding>
): Command = when (this) {
    is Command.DispatchCommand -> copy(
        samplerBindings = samplerBindings,
        imageBindings = imageBindings,
        storageBufferBindings = storageBufferBindings,
        uniformBufferBindings = uniformBufferBindings,
        defaultUniformBindings = defaultUniformBindings
    )

    is Command.DispatchIndirectCommand -> copy(
        samplerBindings = samplerBindings,
        imageBindings = imageBindings,
        storageBufferBindings = storageBufferBindings,
        uniformBufferBindings = uniformBufferBindings,
        defaultUniformBindings = defaultUniformBindings
    )
}

class ImageData(
    val levels: List<Arr>,
    val levelRaw: List<ByteBuffer>
)
class BufferData(
    val arr: Arr,
    val raw: ByteBuffer
)

class CaptureData(
    val metadata: CaptureMetadata,
    val imageData: List<ImageData>,
    val bufferData: List<BufferData>
) {
    companion object {
        fun save(outputPath: Path, capture: CaptureData, block: () -> Unit): Thread {
            return thread(true) {
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
                        val channel = Channels.newChannel(zipOutput)
                        zipOutput.setMethod(ZipOutputStream.STORED)
                        fun writeEntry(name: String, data: ByteBuffer) {
                            data.rewind()
                            val entry = ZipEntry(name)
                            entry.size = data.remaining().toLong()
                            entry.compressedSize = data.remaining().toLong()
                            val crc32 = CRC32()
                            crc32.update(data)
                            entry.crc = crc32.value
                            zipOutput.putNextEntry(entry)
                            channel.write(data)
                            zipOutput.closeEntry()
                        }

                        fun writeEntry(name: String, data: ByteBuffer, arr: Arr) {
                            println("Writing entry $name, size=${arr.len}, ptr=${"0x%016X".format(arr.ptr.address)}")
                            writeEntry(name, data)
                        }

                        capture.imageData.forEachIndexed { imageIndex, data ->
                            data.levelRaw.indices.forEach { level ->
                                writeEntry("image_${imageIndex}_$level.bin", data.levelRaw[level], data.levels[level])
                            }
                        }
                        capture.bufferData.forEachIndexed {  i, bufferData ->
                            writeEntry("buffer_$i.bin", bufferData.raw, bufferData.arr)
                        }
                    }
                    proc.waitFor()

                block()
            }
        }

        fun load(inputPath: Path): CaptureData = runBlocking {
            val metadata = async(Dispatchers.IO) {
                val metadataPath = inputPath.resolve("resource_metadata.json")
                Json.decodeFromString<CaptureMetadata>(metadataPath.readText())
            }

            val imageDataBytes = ConcurrentHashMap<String, ByteBuffer>()
            val bufferDataBytes = ConcurrentHashMap<String, ByteBuffer>()

            val imageDataCallback = ConcurrentHashMap<String, Continuation<ByteBuffer>>()
            val bufferDataCallback = ConcurrentHashMap<String, Continuation<ByteBuffer>>()

            val imageData = async(Dispatchers.Default) {
                metadata.await().images.mapIndexed { i, imageMeta ->
                    async {
                        val levels = imageMeta.levelDataSizes.indices.map { levelIndex ->
                            val key = "image_${i}_$levelIndex.bin"
                            imageDataBytes[key] ?: suspendCancellableCoroutine {
                                imageDataCallback[key] = it
                            }
                        }
                        ImageData(levels.map { Arr.wrap(it) }, levels)
                    }
                }.awaitAll()
            }
            val bufferData = async(Dispatchers.Default) {
                metadata.await().buffers.indices.map { i ->
                    async {
                        val key = "buffer_$i.bin"
                        val raw = bufferDataBytes[key] ?: suspendCancellableCoroutine {
                            bufferDataCallback[key] = it
                        }
                        BufferData(Arr.wrap(raw), raw)
                    }
                }.awaitAll()
            }

            val resourcesPath = inputPath.resolve("resources.zip.xz")

            val proc = ProcessBuilder()
                .command("7z", "e", resourcesPath.absolutePathString(), "-so")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

            ZipInputStream(proc.inputStream).use { zipInput ->
                val channel = Channels.newChannel(zipInput)
                var entry = zipInput.nextEntry
                while (entry != null) {
                    val byteBuffer = ByteBuffer.allocateDirect(entry.size.toInt())
                    channel.read(byteBuffer)
                    byteBuffer.flip()
                    when {
                        entry.name.startsWith("image_") -> {
                            imageDataBytes[entry.name] = byteBuffer
                            imageDataCallback[entry.name]?.resume(byteBuffer)
                        }
                        entry.name.startsWith("buffer_") -> {
                            bufferDataBytes[entry.name] = byteBuffer
                            bufferDataCallback[entry.name]?.resume(byteBuffer)
                        }
                        else -> error("Got unexpected file ${entry.name} in resource capture")
                    }
                    entry = zipInput.nextEntry
                }
            }

            CaptureData(
                metadata.await(),
                imageData.await(),
                bufferData.await()
            )
        }
    }
}
