package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.common.CaptureData
import dev.luna5ama.glc2vk.common.BufferBinding
import dev.luna5ama.glc2vk.common.Command
import dev.luna5ama.glc2vk.common.ImageMetadata
import dev.luna5ama.glc2vk.common.SamplerInfo
import dev.luna5ama.glc2vk.common.VkImageViewType
import dev.luna5ama.glc2vk.common.defaultUniformBindings
import dev.luna5ama.glc2vk.common.imageBindings
import dev.luna5ama.glc2vk.common.samplerBindings
import dev.luna5ama.glc2vk.common.storageBufferBindings
import dev.luna5ama.glc2vk.common.uniformBufferBindings
import dev.luna5ama.glwrapper.base.GL_COMPARE_REF_TO_TEXTURE
import dev.luna5ama.glwrapper.base.GL_DISPATCH_INDIRECT_BUFFER
import dev.luna5ama.glwrapper.base.GL_DYNAMIC_STORAGE_BIT
import dev.luna5ama.glwrapper.base.GL_NONE
import dev.luna5ama.glwrapper.base.GL_READ_WRITE
import dev.luna5ama.glwrapper.base.GL_SHADER_STORAGE_BUFFER
import dev.luna5ama.glwrapper.base.GL_TEXTURE_BORDER_COLOR
import dev.luna5ama.glwrapper.base.GL_TEXTURE_COMPARE_FUNC
import dev.luna5ama.glwrapper.base.GL_TEXTURE_COMPARE_MODE
import dev.luna5ama.glwrapper.base.GL_TEXTURE_LOD_BIAS
import dev.luna5ama.glwrapper.base.GL_TEXTURE_MAG_FILTER
import dev.luna5ama.glwrapper.base.GL_TEXTURE_MAX_LOD
import dev.luna5ama.glwrapper.base.GL_TEXTURE_MIN_FILTER
import dev.luna5ama.glwrapper.base.GL_TEXTURE_MIN_LOD
import dev.luna5ama.glwrapper.base.GL_TEXTURE_WRAP_R
import dev.luna5ama.glwrapper.base.GL_TEXTURE_WRAP_S
import dev.luna5ama.glwrapper.base.GL_TEXTURE_WRAP_T
import dev.luna5ama.glwrapper.base.GL_UNIFORM_BUFFER
import dev.luna5ama.glwrapper.base.glBindBufferRange
import dev.luna5ama.glwrapper.base.glBindImageTexture
import dev.luna5ama.glwrapper.base.glBindSampler
import dev.luna5ama.glwrapper.base.glGetUniformLocation
import dev.luna5ama.glwrapper.base.glUniform1fv
import dev.luna5ama.glwrapper.base.glUniform1i
import dev.luna5ama.glwrapper.base.glUniform1iv
import dev.luna5ama.glwrapper.base.glUniform1uiv
import dev.luna5ama.glwrapper.base.glUniform2fv
import dev.luna5ama.glwrapper.base.glUniform2iv
import dev.luna5ama.glwrapper.base.glUniform2uiv
import dev.luna5ama.glwrapper.base.glUniform3fv
import dev.luna5ama.glwrapper.base.glUniform3iv
import dev.luna5ama.glwrapper.base.glUniform3uiv
import dev.luna5ama.glwrapper.base.glUniform4fv
import dev.luna5ama.glwrapper.base.glUniform4iv
import dev.luna5ama.glwrapper.base.glUniform4uiv
import dev.luna5ama.glwrapper.base.glUniformMatrix2fv
import dev.luna5ama.glwrapper.base.glUniformMatrix3fv
import dev.luna5ama.glwrapper.base.glUniformMatrix4fv
import dev.luna5ama.glwrapper.enums.ImageFormat as GLImageFormat
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.SamplerObject
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.kmogus.Ptr
import kotlin.math.max

class GLReplayResource(private val captureData: CaptureData) {
    val buffers = captureData.metadata.buffers.mapIndexed { i, metadata ->
        BufferObject.Immutable().apply {
            val data = captureData.bufferData[i]
            allocate(max(1L, metadata.size), GL_DYNAMIC_STORAGE_BIT)
            if (metadata.size > 0L) {
                upload(0L, metadata.size, data.ptr)
            }
        }
    }

    private val textures = captureData.metadata.images.mapIndexed { i, metadata ->
        TextureResource(createTexture(metadata), metadata, metadata.format.toGLImageFormat()).also {
            it.upload(captureData.imageData[i].levels)
        }
    }

    private val samplerBindings = captureData.metadata.allSamplerBindings()
    private val samplerIndex = samplerBindings.withIndex().associate { it.value to it.index }
    private val samplers = samplerBindings.map { binding ->
        SamplerObject().apply {
            configure(binding.samplerInfo, captureData.metadata.images[binding.imageIndex].mipLevels > 1)
        }
    }

    fun resetCapturedData() {
        captureData.metadata.buffers.forEachIndexed { i, metadata ->
            if (metadata.size > 0L) {
                buffers[i].upload(0L, metadata.size, captureData.bufferData[i].ptr)
            }
        }
        captureData.metadata.images.forEachIndexed { i, _ ->
            textures[i].upload(captureData.imageData[i].levels)
        }
    }

    fun bind(command: Command) {
        bindUniforms(command)
        bindImages(command)
        bindSamplers(command)
        bindBuffers(command.storageBufferBindings(), GL_SHADER_STORAGE_BUFFER)
        bindBuffers(command.uniformBufferBindings(), GL_UNIFORM_BUFFER)
    }

    fun bindDispatchIndirectBuffer(bufferIndex: Int) {
        buffers[bufferIndex].bind(GL_DISPATCH_INDIRECT_BUFFER)
    }

    fun destroy() {
        samplers.forEach { it.destroy() }
        textures.forEach { it.texture.destroy() }
        buffers.forEach { it.destroy() }
    }

    private fun bindImages(command: Command) {
        command.imageBindings().forEach { binding ->
            setOpaqueUniformUnit(binding.name, binding.binding)
            val image = textures[binding.imageIndex]
            val imageFormat = binding.format.toGLImageFormat()
            val layered = image.texture is TextureObject.LayeredTexture
            glBindImageTexture(
                binding.binding,
                image.texture.id,
                0,
                layered,
                0,
                GL_READ_WRITE,
                imageFormat.value
            )
        }
    }

    private fun bindSamplers(command: Command) {
        command.samplerBindings().forEach { binding ->
            setOpaqueUniformUnit(binding.name, binding.binding)
            val texture = textures[binding.imageIndex].texture
            texture.bindTextureUnit(binding.binding)
            glBindSampler(binding.binding, samplers[samplerIndex.getValue(binding)].id)
        }
    }

    private fun setOpaqueUniformUnit(name: String, unit: Int) {
        val location = glGetUniformLocation(currentProgram, name)
        if (location >= 0) {
            glUniform1i(location, unit)
        }
    }

    private var currentProgram: Int = 0

    fun useProgram(program: Int) {
        currentProgram = program
    }

    private fun bindUniforms(command: Command) {
        command.defaultUniformBindings().forEach { uniform ->
            val location = glGetUniformLocation(currentProgram, uniform.name)
            if (location < 0) return@forEach

            val data = captureData.bufferData[uniform.bufferIndex].ptr + uniform.offset
            val count = uniform.arraySize
            when (uniform.type) {
                "bool", "int" -> glUniform1iv(location, count, data)
                "uint" -> glUniform1uiv(location, count, data)
                "float" -> glUniform1fv(location, count, data)
                "bvec2", "ivec2" -> glUniform2iv(location, count, data)
                "uvec2" -> glUniform2uiv(location, count, data)
                "vec2" -> glUniform2fv(location, count, data)
                "bvec3", "ivec3" -> glUniform3iv(location, count, data)
                "uvec3" -> glUniform3uiv(location, count, data)
                "vec3" -> glUniform3fv(location, count, data)
                "bvec4", "ivec4" -> glUniform4iv(location, count, data)
                "uvec4" -> glUniform4uiv(location, count, data)
                "vec4" -> glUniform4fv(location, count, data)
                "mat2" -> glUniformMatrix2fv(location, count, false, data)
                "mat3" -> glUniformMatrix3fv(location, count, false, data)
                "mat4" -> glUniformMatrix4fv(location, count, false, data)
                else -> error("OpenGL replay does not support default uniform type ${uniform.type} for ${uniform.name}")
            }
        }
    }

    private fun bindBuffers(bindings: List<BufferBinding>, target: Int) {
        bindings.forEach { binding ->
            val metadata = captureData.metadata.buffers[binding.bufferIndex]
            val range = metadata.size - binding.offset
            if (range <= 0L) return@forEach
            glBindBufferRange(target, binding.binding, buffers[binding.bufferIndex].id, binding.offset, range)
        }
    }

    private class TextureResource(
        val texture: TextureObject,
        val metadata: ImageMetadata,
        val format: GLImageFormat.Sized
    ) {
        fun upload(levels: List<dev.luna5ama.kmogus.Arr>) {
            levels.forEachIndexed { mip, data ->
                uploadLevel(mip, data.ptr, data.len)
            }
        }

        private fun uploadLevel(mip: Int, data: Ptr, dataLen: Long) {
            val width = max(1, metadata.width shr mip)
            val height = max(1, metadata.height shr mip)
            val depth = max(1, metadata.depth shr mip)

            when (val typedFormat = format) {
                is GLImageFormat.Compressed -> uploadCompressed(mip, width, height, depth, typedFormat, dataLen, data)
                is GLImageFormat.Uncompressed -> uploadUncompressed(mip, width, height, depth, typedFormat, data)
            }
        }

        private fun uploadUncompressed(
            mip: Int,
            width: Int,
            height: Int,
            depth: Int,
            format: GLImageFormat.Uncompressed,
            data: Ptr
        ) {
            when (val texture = texture) {
                is TextureObject.Tex1D -> texture.upload(mip, 0, width, format.pixelFormat.value, format.pixelType, data)
                is TextureObject.Tex2D -> texture.upload(
                    mip,
                    0,
                    0,
                    width,
                    textureUploadHeight(height),
                    format.pixelFormat.value,
                    format.pixelType,
                    data
                )

                is TextureObject.Tex3D -> texture.upload(
                    mip,
                    0,
                    0,
                    0,
                    width,
                    height,
                    textureUploadDepth(depth),
                    format.pixelFormat.value,
                    format.pixelType,
                    data
                )
            }
        }

        private fun uploadCompressed(
            mip: Int,
            width: Int,
            height: Int,
            depth: Int,
            format: GLImageFormat.Compressed,
            dataLen: Long,
            data: Ptr
        ) {
            require(dataLen <= Int.MAX_VALUE) {
                "Compressed image level is too large for OpenGL upload: ${metadata.name} mip=$mip size=$dataLen"
            }
            val imageSize = dataLen.toInt()
            when (val texture = texture) {
                is TextureObject.Tex1D -> texture.uploadCompressed(mip, 0, width, format.value, imageSize, data)
                is TextureObject.Tex2D -> texture.uploadCompressed(
                    mip,
                    0,
                    0,
                    width,
                    textureUploadHeight(height),
                    format.value,
                    imageSize,
                    data
                )

                is TextureObject.Tex3D -> texture.uploadCompressed(
                    mip,
                    0,
                    0,
                    0,
                    width,
                    height,
                    textureUploadDepth(depth),
                    format.value,
                    imageSize,
                    data
                )
            }
        }

        private fun textureUploadHeight(height: Int): Int {
            return when (metadata.viewType) {
                VkImageViewType.`1D_ARRAY` -> metadata.arrayLayers
                else -> height
            }
        }

        private fun textureUploadDepth(depth: Int): Int {
            return when (metadata.viewType) {
                VkImageViewType.`2D_ARRAY` -> metadata.arrayLayers
                else -> depth
            }
        }
    }
}

private fun createTexture(metadata: ImageMetadata): TextureObject {
    val format = metadata.format.toGLImageFormat()
    val levels = metadata.levelDataSizes.size
    return when (metadata.viewType) {
        VkImageViewType.`1D` -> TextureObject.Texture1D().apply {
            allocate(levels, format, metadata.width)
        }

        VkImageViewType.`2D` -> TextureObject.Texture2D().apply {
            allocate(levels, format, metadata.width, metadata.height)
        }

        VkImageViewType.`3D` -> TextureObject.Texture3D().apply {
            allocate(levels, format, metadata.width, metadata.height, metadata.depth)
        }

        VkImageViewType.`1D_ARRAY` -> TextureObject.Texture1DArray().apply {
            allocate(levels, format, metadata.width, metadata.arrayLayers)
        }

        VkImageViewType.`2D_ARRAY` -> TextureObject.Texture2DArray().apply {
            allocate(levels, format, metadata.width, metadata.height, metadata.arrayLayers)
        }

        VkImageViewType.CUBE,
        VkImageViewType.CUBE_ARRAY -> error("OpenGL replay does not support captured cube textures yet: ${metadata.name}")
    }
}

private fun SamplerObject.configure(info: SamplerInfo, mipmapped: Boolean) {
    parameteri(GL_TEXTURE_MAG_FILTER, info.magFilter.toGLFilter())
    parameteri(GL_TEXTURE_MIN_FILTER, glMinFilter(info.minFilter, info.mipmapMode, mipmapped))
    parameteri(GL_TEXTURE_WRAP_S, info.addressModeU.toGLWrapMode())
    parameteri(GL_TEXTURE_WRAP_T, info.addressModeV.toGLWrapMode())
    parameteri(GL_TEXTURE_WRAP_R, info.addressModeW.toGLWrapMode())
    parameterf(GL_TEXTURE_LOD_BIAS, info.mipLodBias)
    parameterf(GL_TEXTURE_MIN_LOD, info.minLod)
    parameterf(GL_TEXTURE_MAX_LOD, info.maxLod)
    parameteri(GL_TEXTURE_COMPARE_MODE, if (info.compareEnable) GL_COMPARE_REF_TO_TEXTURE else GL_NONE)
    parameteri(GL_TEXTURE_COMPARE_FUNC, info.compareOp.toGLCompareFunc())
    parameterfv(
        GL_TEXTURE_BORDER_COLOR,
        info.boarderColorR,
        info.boarderColorG,
        info.boarderColorB,
        info.boarderColorA
    )
}
