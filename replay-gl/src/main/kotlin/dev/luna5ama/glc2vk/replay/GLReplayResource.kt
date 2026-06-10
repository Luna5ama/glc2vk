package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.common.*
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.glwrapper.objects.SamplerObject
import dev.luna5ama.glwrapper.objects.TextureObject
import dev.luna5ama.kmogus.Ptr
import kotlin.math.max
import dev.luna5ama.glwrapper.enums.ImageFormat as GLImageFormat

class GLReplayResource(private val captureData: CaptureData) {
    internal val buffers = captureData.metadata.buffers.indices.map { BufferResource(it) }
    internal val textures = captureData.metadata.images.indices.map { TextureResource(it) }

    private val samplerBindings = captureData.metadata.allSamplerBindings()
    private val samplerIndex = samplerBindings.withIndex().associate { it.value to it.index }
    private val samplers = samplerBindings.map { binding ->
        SamplerObject().apply {
            configure(binding.samplerInfo, captureData.metadata.images[binding.imageIndex].mipLevels > 1)
        }
    }

    fun resetCapturedData() {
        buffers.forEach {
            it.resetCapturedData()
        }
        textures.forEach {
            it.resetCapturedData()
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
        buffers[bufferIndex].buffer.bind(GL_DISPATCH_INDIRECT_BUFFER)
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
            glBindBufferRange(target, binding.binding, buffers[binding.bufferIndex].buffer.id, binding.offset, range)
        }
    }

    internal inner class BufferResource(
        val index: Int
    ) {
        val metadata get() = captureData.metadata.buffers[index]
        val data get() = captureData.bufferData[index]
        val copySource = BufferObject.Immutable()
        val buffer = BufferObject.Immutable()

        init {
            val bufferSize = max(1L, metadata.size)
            copySource.allocate(bufferSize, if (metadata.size > 1) data.ptr else Ptr.NULL, 0)
            buffer.allocate(bufferSize, 0)
        }

        fun resetCapturedData() {
            if (metadata.size > 0L) {
                copySource.copyTo(buffer)
            }
        }

        fun destroy() {
            copySource.destroy()
            buffer.destroy()
        }
    }

    internal inner class TextureResource(
        val index: Int
    ) {
        val metadata get() = captureData.metadata.images[index]
        val glFormat = metadata.format.toGLImageFormat()
        val data get() = captureData.imageData[index]
        val copySource = createTexture(metadata)
        val texture = createTexture(metadata)

        init {
            data.levels.forEachIndexed { mip, data ->
                copySource.uploadLevel(mip, data.ptr, data.len)
            }
        }

        fun resetCapturedData() {
            when (val copySource = copySource) {
                is TextureObject.Tex1D -> copySource.copyTo(texture as TextureObject.Tex1D)
                is TextureObject.Texture1DArray -> copySource.copyTo(texture as TextureObject.Texture1DArray)
                is TextureObject.Texture2D -> copySource.copyTo(texture as TextureObject.Texture2D)
                is TextureObject.TextureCubemap -> copySource.copyTo(texture as TextureObject.TextureCubemap)
                is TextureObject.Texture2DArray -> copySource.copyTo(texture as TextureObject.Texture2DArray)
                is TextureObject.Texture3D -> copySource.copyTo(texture as TextureObject.Texture3D)
                is TextureObject.TextureCubemapArray -> copySource.copyTo(texture as TextureObject.TextureCubemapArray)
            }
        }

        private fun TextureObject.uploadLevel(mip: Int, data: Ptr, dataLen: Long) {
            val width = max(1, metadata.width shr mip)
            val height = max(1, metadata.height shr mip)
            val depth = max(1, metadata.depth shr mip)

            when (val typedFormat = metadata.format.toGLImageFormat()) {
                is GLImageFormat.Compressed -> uploadCompressed(mip, width, height, depth, typedFormat, dataLen, data)
                is GLImageFormat.Uncompressed -> uploadUncompressed(mip, width, height, depth, typedFormat, data)
            }
        }

        private fun TextureObject.uploadUncompressed(
            mip: Int,
            width: Int,
            height: Int,
            depth: Int,
            format: GLImageFormat.Uncompressed,
            data: Ptr
        ) {
            when (val texture = this) {
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

        private fun TextureObject.uploadCompressed(
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
            when (val texture = this) {
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
            }.apply {
                label = metadata.name
            }
        }

        fun destroy() {
            texture.destroy()
        }
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
