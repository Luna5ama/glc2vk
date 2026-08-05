package dev.luna5ama.vibris.common

import dev.luna5ama.kmogus.Arr
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
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
import kotlin.io.path.*

internal class CaptureEntries<T> {
    private val entries = ConcurrentHashMap<String, CompletableDeferred<T>>()

    suspend fun await(name: String): T = entry(name).await()

    fun complete(name: String, value: T) {
        check(entry(name).complete(value)) { "Capture entry $name was completed more than once" }
    }

    private fun entry(name: String): CompletableDeferred<T> =
        entries.computeIfAbsent(name) { CompletableDeferred() }
}

@Serializable
data class PassInfo(
    val shaderIndex: Int = 0,
    val samplerBindings: List<SamplerBinding> = emptyList(),
    val imageBindings: List<ImageBinding> = emptyList(),
    val storageBufferBindings: List<BufferBinding> = emptyList(),
    val uniformBufferBindings: List<BufferBinding> = emptyList(),
    val defaultUniformBindings: List<DefaultUniformBinding> = emptyList(),
)

@Serializable
data class VertexAttribute(
    val location: Int,
    val name: String? = null,
    val bufferIndex: Int,
    val size: Int,
    val type: Int,
    val normalized: Boolean,
    val integer: Boolean,
    val long: Boolean,
    val stride: Int,
    val offset: Long,
    val divisor: Int,
)

@Serializable
data class FramebufferAttachment(
    val attachment: Int,
    val imageIndex: Int,
    val level: Int = 0,
    val layer: Int = -1,
)

@Serializable
data class BlendState(
    val enabled: Boolean,
    val sourceRgb: Int,
    val destinationRgb: Int,
    val sourceAlpha: Int,
    val destinationAlpha: Int,
    val equationRgb: Int,
    val equationAlpha: Int,
    val colorMask: Int,
)

@Serializable
data class GraphicsState(
    val viewport: List<Int>,
    val scissorEnabled: Boolean,
    val scissor: List<Int>,
    val depthTest: Boolean,
    val depthFunction: Int,
    val depthWrite: Boolean,
    val cullEnabled: Boolean,
    val cullFace: Int,
    val frontFace: Int,
    val polygonMode: Int,
    val polygonOffsetEnabled: Boolean,
    val polygonOffsetFactor: Float,
    val polygonOffsetUnits: Float,
    val lineWidth: Float,
    val blends: List<BlendState>,
    val blendColor: List<Float> = listOf(0f, 0f, 0f, 0f),
)

@Serializable
data class GraphicsPassInfo(
    val shaderIndices: List<Int>,
    val resources: PassInfo,
    val vertexAttributes: List<VertexAttribute>,
    val framebufferAttachments: List<FramebufferAttachment>,
    val drawBuffers: List<Int>,
    val state: GraphicsState,
)

@Serializable
sealed class Command {
    @Serializable
    sealed class PassCommand : Command() {
        abstract val passInfo: PassInfo
    }

    @Serializable
    @SerialName("PushDebugLabel")
    data class PushDebugLabelCommand(
        val label: String
    ) : Command()

    @Serializable
    @SerialName("PopDebugLabel")
    object PopDebugLabelCommand : Command()

    @Serializable
    @SerialName("Dispatch")
    data class DispatchCommand(
        val x: Int,
        val y: Int,
        val z: Int,
        override val passInfo: PassInfo = PassInfo()
    ) : PassCommand()

    @Serializable
    @SerialName("DispatchIndirect")
    data class DispatchIndirectCommand(
        val bufferIndex: Int,
        val offset: Long,
        override val passInfo: PassInfo = PassInfo()
    ) : PassCommand()

    @Serializable
    sealed class GraphicsCommand : Command() {
        abstract val graphicsInfo: GraphicsPassInfo
    }

    @Serializable
    @SerialName("DrawArrays")
    data class DrawArraysCommand(
        val mode: Int,
        val first: Int,
        val count: Int,
        val instanceCount: Int = 1,
        override val graphicsInfo: GraphicsPassInfo,
    ) : GraphicsCommand()

    @Serializable
    @SerialName("DrawElements")
    data class DrawElementsCommand(
        val mode: Int,
        val count: Int,
        val indexType: Int,
        val indexOffset: Long,
        val baseVertex: Int = 0,
        val instanceCount: Int = 1,
        val indexBufferIndex: Int,
        override val graphicsInfo: GraphicsPassInfo,
    ) : GraphicsCommand()

    @Serializable
    @SerialName("MultiDrawElements")
    data class MultiDrawElementsCommand(
        val mode: Int,
        val counts: List<Int>,
        val indexType: Int,
        val indexOffsets: List<Long>,
        val baseVertices: List<Int>,
        val indexBufferIndex: Int,
        override val graphicsInfo: GraphicsPassInfo,
    ) : GraphicsCommand()
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
    val commands: List<Command> = emptyList(),
    val shaders: List<ShaderMetadata> = emptyList()
) {
    fun commandsForReplay(): List<Command> = commands.normalizeExplicitDebugLabelCommands()

    fun allSamplerBindings(): List<SamplerBinding> = commandsForReplay().asSequence()
        .flatMap { it.resourceInfo() }
        .flatMap { it.samplerBindings }
        .distinct()
        .toList()

    fun allImageBindings(): List<ImageBinding> = commandsForReplay().asSequence()
        .flatMap { it.resourceInfo() }
        .flatMap { it.imageBindings }
        .distinct()
        .toList()

    fun allStorageBufferBindings(): List<BufferBinding> = commandsForReplay().asSequence()
        .flatMap { it.resourceInfo() }
        .flatMap { it.storageBufferBindings }
        .distinct()
        .toList()

    fun allUniformBufferBindings(): List<BufferBinding> = commandsForReplay().asSequence()
        .flatMap { it.resourceInfo() }
        .flatMap { it.uniformBufferBindings }
        .distinct()
        .toList()

    fun shaderMetadata(shaderIndex: Int): ShaderMetadata {
        return shaders.getOrNull(shaderIndex) ?: ShaderMetadata()
    }
}

private fun Command.resourceInfo(): Sequence<PassInfo> = when (this) {
    is Command.PassCommand -> sequenceOf(passInfo)
    is Command.GraphicsCommand -> sequenceOf(graphicsInfo.resources)
    else -> emptySequence()
}

private fun List<Command>.normalizeExplicitDebugLabelCommands(): List<Command> {
    val normalized = this.toMutableList()
    val depth = this.sumOf {
        when (it) {
            is Command.PushDebugLabelCommand -> 1
            Command.PopDebugLabelCommand -> -1
            else -> 0
        }
    }

    repeat(depth) {
        normalized += Command.PopDebugLabelCommand
    }

    return normalized
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
                        val entry = ZipEntry(name)
                        entry.size = data.remaining().toLong()
                        entry.compressedSize = data.remaining().toLong()
                        val crc32 = CRC32()

                        data.rewind()
                        crc32.update(data)

                        entry.crc = crc32.value
                        zipOutput.putNextEntry(entry)
                        data.rewind()
                        channel.write(data)
                        zipOutput.closeEntry()
                    }

                    fun writeEntry(name: String, data: ByteBuffer, arr: Arr) {
                        if (VIBRIS_DEBUG) println("Writing entry $name, size=${arr.len}, ptr=${"0x%016X".format(arr.ptr.address)}")
                        writeEntry(name, data)
                    }

                    capture.imageData.forEachIndexed { imageIndex, data ->
                        data.levelRaw.indices.forEach { level ->
                            writeEntry("image_${imageIndex}_$level.bin", data.levelRaw[level], data.levels[level])
                        }
                    }
                    capture.bufferData.forEachIndexed { i, bufferData ->
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

            val imageEntries = CaptureEntries<ByteBuffer>()
            val bufferEntries = CaptureEntries<ByteBuffer>()

            val imageData = async(Dispatchers.Default) {
                metadata.await().images.mapIndexed { i, imageMeta ->
                    async {
                        val levels = imageMeta.levelDataSizes.indices.map { levelIndex ->
                            val key = "image_${i}_$levelIndex.bin"
                            imageEntries.await(key)
                        }
                        ImageData(levels.map { Arr.wrap(it) }, levels)
                    }
                }.awaitAll()
            }
            val bufferData = async(Dispatchers.Default) {
                metadata.await().buffers.indices.map { i ->
                    async {
                        val key = "buffer_$i.bin"
                        val raw = bufferEntries.await(key)
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
                    while (byteBuffer.hasRemaining()) {
                        check(channel.read(byteBuffer) >= 0) { "Unexpected end of archive entry ${entry.name}" }
                    }
                    byteBuffer.flip()
                    when {
                        entry.name.startsWith("image_") -> {
                            imageEntries.complete(entry.name, byteBuffer)
                        }

                        entry.name.startsWith("buffer_") -> {
                            bufferEntries.complete(entry.name, byteBuffer)
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

