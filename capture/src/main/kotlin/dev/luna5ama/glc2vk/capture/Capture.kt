package dev.luna5ama.glc2vk.capture

import dev.luna5ama.glc2vk.common.*
import dev.luna5ama.glwrapper.ShaderProgramResourceManager
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.GLSLDataType
import dev.luna5ama.glwrapper.enums.GLSLDataType.UniformType
import dev.luna5ama.glwrapper.enums.ImageFormat
import dev.luna5ama.glwrapper.enums.ShaderStage
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.*
import org.lwjgl.system.MemoryUtil
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText

private class CaptureContext(val shaderInfo: ShaderInfo, val resourceManager: ShaderProgramResourceManager) {
    val tempGPUBuffer = BufferObject.Immutable()

    fun ensureTempGPUBufferCapacity(size: Long) {
        if (tempGPUBuffer.size < size) {
            tempGPUBuffer.destroy()
            tempGPUBuffer.allocate(size, GL_MAP_READ_BIT or GL_CLIENT_STORAGE_BIT)
        }
    }

    val imageNames = mutableMapOf<Int, String>()
    val images = mutableListOf<ImageData>()
    val imageMetadata = mutableListOf<ImageMetadata>()
    val samplerBindings = mutableListOf<SamplerBinding>()
    val imageBindings = mutableListOf<ImageBinding>()

    fun samplerBinding(name: String, imageIndex: Int, samplerInfo: SamplerInfo, bindingIndex: Int) {
        val prev = imageNames[imageIndex]
        imageNames[imageIndex] = prev?.let { "${it}_$name" } ?: name
        val binding = SamplerBinding(
            name = name,
            imageIndex = imageIndex,
            set = 0,
            binding = bindingIndex,
            samplerInfo = samplerInfo
        )
        samplerBindings += binding
    }

    fun imageBinding(name: String, imageIndex: Int, bindingIndex: Int) {
        val prev = imageNames[imageIndex]
        imageNames[imageIndex] = prev?.let { "${it}_$name" } ?: name
        val binding = ImageBinding(
            name = name,
            imageIndex = imageIndex,
            set = 1,
            binding = bindingIndex,
            format = shaderInfo.imageTypeOverrides[name]?.let { glFormatToVkFormat(it) } ?: imageMetadata[imageIndex].format
        )
        imageBindings += binding
    }

    val bufferNames = mutableMapOf<Int, String>()
    val buffers = mutableListOf<Arr>()
    val bufferMetadata = mutableListOf<BufferMetadata>()
    val storageBufferBindings = mutableListOf<BufferBinding>()
    val uniformBufferBindings = mutableListOf<BufferBinding>()

    fun storageBufferBinding(name: String, bufferIndex: Int, bindingIndex: Int, offset: Long) {
        val prev = bufferNames[bufferIndex]
        bufferNames[bufferIndex] = prev?.let { "${it}_$name" } ?: name
        val binding = BufferBinding(
            name = name,
            bufferIndex = bufferIndex,
            set = 2,
            binding = bindingIndex,
            offset = offset
        )
        storageBufferBindings += binding
    }

    fun uniformBufferBinding(name: String, bufferIndex: Int, bindingIndex: Int, offset: Long) {
        val prev = bufferNames[bufferIndex]
        bufferNames[bufferIndex] = prev?.let { "${it}_$name" } ?: name
        val binding = BufferBinding(
            name = name,
            bufferIndex = bufferIndex,
            set = 3,
            binding = bindingIndex,
            offset = offset
        )
        uniformBufferBindings += binding
    }


    val bufferIDToIndex = mutableMapOf<Int, Int>()

    fun getBufferIndex(bufferID: Int): Int {
        MemoryStack {
            val temp = malloc(8L)
            val tempPtr = temp.ptr
            if (bufferIDToIndex.putIfAbsent(bufferID, buffers.size) == null) {
                glGetNamedBufferParameteriv(bufferID, GL_BUFFER_SIZE, tempPtr)
                val bufferSize = tempPtr.getInt()
                ensureTempGPUBufferCapacity(bufferSize.toLong())
                glCopyNamedBufferSubData(bufferID, tempGPUBuffer.id, 0L, 0L, bufferSize.toLong())

                val cpuBufferData = transferBuffer(bufferSize.toLong())



                glGetObjectLabel(GL_BUFFER, bufferID, 0, tempPtr, Ptr.NULL)
                val labelLen = tempPtr.getInt()
                val str = if (labelLen > 0) {
                    val labelBuffer = malloc(labelLen + 1L)
                    glGetObjectLabel(GL_BUFFER, bufferID, labelLen + 1, tempPtr, labelBuffer.ptr)
                    MemoryUtil.memUTF8(labelBuffer.ptr.address)
                } else {
                    ""
                }

                buffers.add(cpuBufferData)
                bufferMetadata.add(
                    BufferMetadata(
                        name = str,
                        size = bufferSize.toLong()
                    )
                )
            }
            val bufferIndex = bufferIDToIndex[bufferID]!!
            return bufferIndex
        }
    }

    fun build(command: Command): CaptureData {
        val metadata = CaptureMetadata(
            images = imageMetadata.mapIndexed { i, metadata ->
                metadata.copy(name = metadata.name.ifEmpty {
                    imageNames[i] ?: "buffer_$i"
                })
            },
            buffers = bufferMetadata.mapIndexed { i, metadata ->
                metadata.copy(name = metadata.name.ifEmpty {
                    bufferNames[i] ?: "buffer_$i"
                })
            },
            samplerBindings = samplerBindings,
            imageBindings = imageBindings,
            storageBufferBindings = storageBufferBindings,
            uniformBufferBindings = uniformBufferBindings,
            command = command
        )
        return CaptureData(
            metadata = metadata,
            imageData = images,
            bufferData = buffers
        )
    }

    fun transferBuffer(size: Long): Arr {
        glFinish()
        val cpuBuffer = Arr.malloc(size)
        val imageData = tempGPUBuffer.map(GL_MAP_READ_BIT)
        memcpy(imageData.ptr, 0L, cpuBuffer.ptr, 0L, size)
        tempGPUBuffer.unmap()
        return cpuBuffer
    }

    fun destroy() {
        tempGPUBuffer.destroy()
    }
}

private fun CaptureContext.captureDefaultUniformBlock() {
    val defaultUniformData = Arr.malloc(0L)

    val struct = UniformBlock {
        MemoryStack {
            resourceManager.uniformResource.entries.values.asSequence()
                .filter { it.blockIndex == -1 }
                .filter { it.location != -1 }
                .filter { it.type is GLSLDataType.Value }
                .forEach {
                    fun getData() {
                        val lastAttribute = this@UniformBlock.last
                        val offset = lastAttribute.alignOffset.toLong()
                        val size = lastAttribute.baseAlign.toLong()
                        defaultUniformData.ensureCapacity(offset + size, true)
                        val dstPtr = defaultUniformData.ptr + offset

                        if (it.arraySize > 1) {
                            when (it.type) {
                                is UniformType.Bool -> {
                                    throw UnsupportedOperationException("Boolean uniforms are not supported")
                                }

                                is UniformType.Int -> {
                                    glGetnUniformiv(resourceManager.programID, it.location, it.arraySize, dstPtr)
                                }

                                is UniformType.UInt -> {
                                    glGetnUniformuiv(resourceManager.programID, it.location, it.arraySize, dstPtr)
                                }

                                is UniformType.Float -> {
                                    glGetnUniformfv(resourceManager.programID, it.location, it.arraySize, dstPtr)
                                }

                                is UniformType.Double -> {
                                    glGetnUniformdv(resourceManager.programID, it.location, it.arraySize, dstPtr)
                                }

                                else -> {
                                    throw UnsupportedOperationException("Unsupported uniform type: ${it.type}")
                                }
                            }
                        } else {
                            when (it.type) {
                                is UniformType.Bool -> {
                                    throw UnsupportedOperationException("Boolean uniforms are not supported")
                                }

                                is UniformType.Int -> {
                                    glGetUniformiv(resourceManager.programID, it.location, dstPtr)
                                }

                                is UniformType.UInt -> {
                                    glGetUniformuiv(resourceManager.programID, it.location, dstPtr)
                                }

                                is UniformType.Float -> {
                                    glGetUniformfv(resourceManager.programID, it.location, dstPtr)
                                }

                                is UniformType.Double -> {
                                    glGetUniformdv(resourceManager.programID, it.location, dstPtr)
                                }

                                else -> {
                                    throw UnsupportedOperationException("Unsupported uniform type: ${it.type}")
                                }
                            }
                        }
                    }
                    when (it.type) {
                        GLSLDataType.Int, GLSLDataType.UInt, GLSLDataType.Float -> {
                            scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.Double -> {
                            scalar64(it.name)
                            getData()
                        }

                        GLSLDataType.IVec2, GLSLDataType.UVec2, GLSLDataType.Vec2 -> {
                            vec2Scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.IVec3, GLSLDataType.UVec3, GLSLDataType.Vec3 -> {
                            vec3Scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.IVec4, GLSLDataType.UVec4, GLSLDataType.Vec4 -> {
                            vec4Scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.Mat2 -> {
                            mat2Scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.Mat3 -> {
                            mat3Scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.Mat4 -> {
                            mat4Scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.Mat2x3 -> {
                            mat2x3Scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.Mat2x4 -> {
                            mat2x4Scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.Mat3x2 -> {
                            mat3x2Scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.Mat3x4 -> {
                            mat3x4Scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.Mat4x2 -> {
                            mat4x2Scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.Mat4x3 -> {
                            mat4x3Scalar32(it.name)
                            getData()
                        }

                        GLSLDataType.DMat2 -> {
                            mat2Scalar64(it.name)
                            getData()
                        }

                        GLSLDataType.DMat3 -> {
                            mat3Scalar64(it.name)
                            getData()
                        }

                        GLSLDataType.DMat4 -> {
                            mat4Scalar64(it.name)
                            getData()
                        }

                        GLSLDataType.DMat2x3 -> {
                            mat2x3Scalar64(it.name)
                            getData()
                        }

                        GLSLDataType.DMat2x4 -> {
                            mat2x4Scalar64(it.name)
                            getData()
                        }

                        GLSLDataType.DMat3x2 -> {
                            mat3x2Scalar64(it.name)
                            getData()
                        }

                        GLSLDataType.DMat3x4 -> {
                            mat3x4Scalar64(it.name)
                            getData()
                        }

                        GLSLDataType.DMat4x2 -> {
                            mat4x2Scalar64(it.name)
                            getData()
                        }

                        GLSLDataType.DMat4x3 -> {
                            mat4x3Scalar64(it.name)
                            getData()
                        }

                        else -> {
                            throw UnsupportedOperationException("Unsupported uniform type: ${it.type}")
                        }
                    }
                }
        }
    }

    check(buffers.isEmpty())
    check(bufferMetadata.isEmpty())

    buffers += defaultUniformData.realloc(struct.size.toLong(), false)
    bufferMetadata += BufferMetadata(
        name = "",
        size = buffers.last().len
    )
    uniformBufferBinding("DefaultUniforms", 0, shaderInfo.ubos["DefaultUniforms"]!!.binding, 0L)
}

private fun CaptureContext.captureImages() {
    val imageIDToIndex = mutableMapOf<Int, Int>()

    MemoryStack {
        val temp = malloc(8 * 4 * 4L)
        val tempPtr = temp.ptr

        fun getImageIndex(imageID: Int): Int {
            if (imageIDToIndex.putIfAbsent(imageID, images.size) == null) {
                val imageIndex = images.size
                glGetTextureParameteriv(imageID, GL_TEXTURE_TARGET, tempPtr)
                val target = tempPtr.getInt()
                val type = glImageTargetToVKImageViewType(target)
                val width: Int
                val height: Int
                val depth: Int
                val arrayLayers: Int
                when (type) {
                    VkImageViewType.`1D` -> {
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_WIDTH, tempPtr)
                        width = tempPtr.getInt()
                        height = 1
                        depth = 1
                        arrayLayers = 1
                    }

                    VkImageViewType.`2D` -> {
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_WIDTH, tempPtr)
                        width = tempPtr.getInt()
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_HEIGHT, tempPtr)
                        height = tempPtr.getInt()
                        depth = 1
                        arrayLayers = 1
                    }

                    VkImageViewType.`3D` -> {
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_WIDTH, tempPtr)
                        width = tempPtr.getInt()
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_HEIGHT, tempPtr)
                        height = tempPtr.getInt()
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_DEPTH, tempPtr)
                        depth = tempPtr.getInt()
                        arrayLayers = 1
                    }

                    VkImageViewType.CUBE -> {
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_WIDTH, tempPtr)
                        width = tempPtr.getInt()
                        height = width
                        depth = 1
                        arrayLayers = 1
                    }

                    VkImageViewType.`1D_ARRAY` -> {
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_WIDTH, tempPtr)
                        width = tempPtr.getInt()
                        height = 1
                        depth = 1
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_DEPTH, tempPtr)
                        arrayLayers = tempPtr.getInt()
                    }

                    VkImageViewType.`2D_ARRAY` -> {
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_WIDTH, tempPtr)
                        width = tempPtr.getInt()
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_HEIGHT, tempPtr)
                        height = tempPtr.getInt()
                        depth = 1
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_DEPTH, tempPtr)
                        arrayLayers = tempPtr.getInt()
                    }

                    VkImageViewType.CUBE_ARRAY -> {
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_WIDTH, tempPtr)
                        width = tempPtr.getInt()
                        height = width
                        depth = 1
                        glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_DEPTH, tempPtr)
                        arrayLayers = tempPtr.getInt()
                    }
                }

                glGetTextureLevelParameteriv(imageID, 0, GL_TEXTURE_INTERNAL_FORMAT, tempPtr)
                val typedFormat = ImageFormat[tempPtr.getInt()]
                val format = glFormatToVkFormat(typedFormat)

                val mipData = mutableListOf<Arr>()

                // Thanks mutable texture storage
                var mipLevels = 0
                for (mip in 0 until 69) {
                    glGetTextureLevelParameteriv(imageID, mip, GL_TEXTURE_WIDTH, tempPtr)
                    val mipWidth = tempPtr.getInt()
                    if (mipWidth == 0) {
                        break
                    }

                    glGetTextureLevelParameteriv(imageID, mip, GL_TEXTURE_COMPRESSED, tempPtr)
                    val compressed = tempPtr.getInt() != GL_FALSE
                    val size: Int
                    if (compressed) {
                        glGetTextureLevelParameteriv(imageID, mip, GL_TEXTURE_COMPRESSED_IMAGE_SIZE, tempPtr)
                        size = tempPtr.getInt()
                        ensureTempGPUBufferCapacity(size.toLong())
                        tempGPUBuffer.bind(GL_PIXEL_PACK_BUFFER)
                        glGetCompressedTextureImage(imageID, mip, size, 0L)
                    } else {
                        typedFormat as ImageFormat.Uncompressed
                        glGetTextureLevelParameteriv(imageID, mip, GL_TEXTURE_HEIGHT, tempPtr)
                        val mipHeight = tempPtr.getInt()
                        glGetTextureLevelParameteriv(imageID, mip, GL_TEXTURE_DEPTH, tempPtr)
                        val mipDepth = tempPtr.getInt()
                        val pixelSize = typedFormat.totalBits / 8
                        size = mipWidth * mipHeight * mipDepth * pixelSize
                        ensureTempGPUBufferCapacity(size.toLong())
                        tempGPUBuffer.bind(GL_PIXEL_PACK_BUFFER)
                        glGetTextureImage(imageID, mip, typedFormat.pixelFormat.value, typedFormat.pixelType, size, 0L)
                    }

                    val cpuImageData = transferBuffer(size.toLong())
                    mipData.add(cpuImageData)

                    mipLevels++
                }

                glGetObjectLabel(GL_TEXTURE, imageID, 0, tempPtr, Ptr.NULL)
                val labelLen = tempPtr.getInt()
                val str = if (labelLen > 0) {
                    val labelBuffer = malloc(labelLen + 1L)
                    glGetObjectLabel(GL_TEXTURE, imageID, labelLen + 1, tempPtr, labelBuffer.ptr)
                    MemoryUtil.memUTF8(labelBuffer.ptr.address)
                } else {
                    ""
                }

                images.add(ImageData(mipData))
                imageMetadata.add(
                    ImageMetadata(
                        name = str,
                        width = width,
                        height = height,
                        depth = depth,
                        mipLevels = mipLevels,
                        arrayLayers = arrayLayers,
                        format = format,
                        type = type,
                        levelDataSizes = mipData.map { it.len }
                    )
                )
            }

            val imageIndex = imageIDToIndex[imageID]!!
            return imageIndex
        }

        fun getTextureDefaultSamplerInfo(imageID: Int): SamplerInfo {
            fun glGetTextureParameteri(texture: Int, pname: Int): Int {
                glGetTextureParameteriv(texture, pname, tempPtr)
                return tempPtr.getInt()
            }

            fun glGetTextureParameterf(texture: Int, pname: Int): Float {
                glGetTextureParameterfv(texture, pname, tempPtr)
                return tempPtr.getFloat()
            }

            val magFilter = glGetTextureParameteri(imageID, GL_TEXTURE_MAG_FILTER)
            val minFilter = glGetTextureParameteri(imageID, GL_TEXTURE_MIN_FILTER)
            val warpU = glGetTextureParameteri(imageID, GL_TEXTURE_WRAP_S)
            val warpV = glGetTextureParameteri(imageID, GL_TEXTURE_WRAP_T)
            val warpW = glGetTextureParameteri(imageID, GL_TEXTURE_WRAP_R)
            val mipLodBias = glGetTextureParameterf(imageID, GL_TEXTURE_LOD_BIAS)
            val maxAnisotropy = glGetTextureParameterf(imageID, GL_TEXTURE_MAX_ANISOTROPY)
            val compareMode = glGetTextureParameteri(imageID, GL_TEXTURE_COMPARE_MODE)
            val compareFunc = glGetTextureParameteri(imageID, GL_TEXTURE_COMPARE_FUNC)
            val minLod = glGetTextureParameterf(imageID, GL_TEXTURE_MIN_LOD)
            val maxLod = glGetTextureParameterf(imageID, GL_TEXTURE_MAX_LOD)
            glGetTextureParameterfv(imageID, GL_TEXTURE_BORDER_COLOR, tempPtr)
            val borderColorR = tempPtr.getFloat(0)
            val borderColorG = tempPtr.getFloat(4)
            val borderColorB = tempPtr.getFloat(8)
            val borderColorA = tempPtr.getFloat(12)

            return SamplerInfo(
                magFilter = glMagFilterToVkFilter(magFilter),
                minFilter = glMinFilterToVkFilter(minFilter),
                mipmapMode = glMinFilterTOVkSamplerMipmapMode(minFilter),
                addressModeU = glWarpModeToVkSamplerAddressMode(warpU),
                addressModeV = glWarpModeToVkSamplerAddressMode(warpV),
                addressModeW = glWarpModeToVkSamplerAddressMode(warpW),
                mipLodBias = mipLodBias,
                anisotropyEnable = maxAnisotropy > 1.0f,
                maxAnisotropy = maxAnisotropy,
                compareEnable = compareMode != GL_NONE,
                compareOp = glCompareFuncToVkCompareOp(compareFunc),
                minLod = minLod,
                maxLod = maxLod,
                boarderColorR = borderColorR,
                boarderColorG = borderColorG,
                boarderColorB = borderColorB,
                boarderColorA = borderColorA,
                false
            )
        }

        fun getSamplerInfo(samplerID: Int): SamplerInfo {
            fun glGetSamplerParameteri(sampler: Int, pname: Int): Int {
                glGetSamplerParameteriv(sampler, pname, tempPtr)
                return tempPtr.getInt()
            }

            fun glGetSamplerParameterf(sampler: Int, pname: Int): Float {
                glGetSamplerParameterfv(sampler, pname, tempPtr)
                return tempPtr.getFloat()
            }

            val magFilter = glGetSamplerParameteri(samplerID, GL_TEXTURE_MAG_FILTER)
            val minFilter = glGetSamplerParameteri(samplerID, GL_TEXTURE_MIN_FILTER)
            val warpU = glGetSamplerParameteri(samplerID, GL_TEXTURE_WRAP_S)
            val warpV = glGetSamplerParameteri(samplerID, GL_TEXTURE_WRAP_T)
            val warpW = glGetSamplerParameteri(samplerID, GL_TEXTURE_WRAP_R)
            val mipLodBias = glGetSamplerParameterf(samplerID, GL_TEXTURE_LOD_BIAS)
            val maxAnisotropy = glGetSamplerParameterf(samplerID, GL_TEXTURE_MAX_ANISOTROPY)
            val compareMode = glGetSamplerParameteri(samplerID, GL_TEXTURE_COMPARE_MODE)
            val compareFunc = glGetSamplerParameteri(samplerID, GL_TEXTURE_COMPARE_FUNC)
            val minLod = glGetSamplerParameterf(samplerID, GL_TEXTURE_MIN_LOD)
            val maxLod = glGetSamplerParameterf(samplerID, GL_TEXTURE_MAX_LOD)
            glGetSamplerParameterfv(samplerID, GL_TEXTURE_BORDER_COLOR, tempPtr)
            val borderColorR = tempPtr.getFloat(0)
            val borderColorG = tempPtr.getFloat(4)
            val borderColorB = tempPtr.getFloat(8)
            val borderColorA = tempPtr.getFloat(12)

            return SamplerInfo(
                magFilter = glMagFilterToVkFilter(magFilter),
                minFilter = glMinFilterToVkFilter(minFilter),
                mipmapMode = glMinFilterTOVkSamplerMipmapMode(minFilter),
                addressModeU = glWarpModeToVkSamplerAddressMode(warpU),
                addressModeV = glWarpModeToVkSamplerAddressMode(warpV),
                addressModeW = glWarpModeToVkSamplerAddressMode(warpW),
                mipLodBias = mipLodBias,
                anisotropyEnable = maxAnisotropy > 1.0f,
                maxAnisotropy = maxAnisotropy,
                compareEnable = compareMode != GL_NONE,
                compareOp = glCompareFuncToVkCompareOp(compareFunc),
                minLod = minLod,
                maxLod = maxLod,
                boarderColorR = borderColorR,
                boarderColorG = borderColorG,
                boarderColorB = borderColorB,
                boarderColorA = borderColorA,
                false
            )
        }

        resourceManager.uniformResource.entries.values.asSequence()
            .filter { it.type is GLSLDataType.Opaque.Image }
            .forEach {
                glGetUniformiv(resourceManager.programID, it.location, tempPtr)
                val imageUnit = tempPtr.getInt()
                glGetIntegeri_v(GL_IMAGE_BINDING_NAME, imageUnit, tempPtr)
                val boundImageID = tempPtr.getInt()
                val imageIndex = getImageIndex(boundImageID)

                imageBinding(it.name, imageIndex, shaderInfo.uniforms[it.name]!!.binding)
            }

        resourceManager.uniformResource.entries.values.asSequence()
            .filter { it.type is GLSLDataType.Opaque.Sampler }
            .forEach { uniformEntry ->
                glGetUniformiv(resourceManager.programID, uniformEntry.location, tempPtr)
                val textureUnit = tempPtr.getInt()
                val bindingTarget = when (uniformEntry.type) {
                    is GLSLDataType.Opaque.Sampler.Sampler1D -> GL_TEXTURE_BINDING_1D
                    is GLSLDataType.Opaque.Sampler.Sampler2D -> GL_TEXTURE_BINDING_2D
                    is GLSLDataType.Opaque.Sampler.Sampler3D -> GL_TEXTURE_BINDING_3D
                    is GLSLDataType.Opaque.Sampler.SamplerCube -> GL_TEXTURE_BINDING_CUBE_MAP
                    is GLSLDataType.Opaque.Sampler.Sampler1DArray -> GL_TEXTURE_BINDING_1D_ARRAY
                    is GLSLDataType.Opaque.Sampler.Sampler2DArray -> GL_TEXTURE_BINDING_2D_ARRAY
                    is GLSLDataType.Opaque.Sampler.SamplerCubeArray -> GL_TEXTURE_BINDING_CUBE_MAP_ARRAY
                    else -> error("Unsupported sampler type: ${uniformEntry.type}")
                }
                glGetIntegeri_v(bindingTarget, textureUnit, tempPtr)
                val boundImageID = tempPtr.getInt()
                val imageIndex = getImageIndex(boundImageID)
                glGetIntegeri_v(GL_SAMPLER_BINDING, textureUnit, tempPtr)
                val boundSamplerID = tempPtr.getInt()
                val samplerInfo = if (glIsSampler(boundSamplerID)) {
                    getSamplerInfo(boundSamplerID)
                } else {
                    getTextureDefaultSamplerInfo(boundImageID)
                }

                samplerBinding(
                    uniformEntry.name,
                    imageIndex,
                    samplerInfo,
                    shaderInfo.uniforms[uniformEntry.name]!!.binding
                )
            }
    }
}

private fun CaptureContext.captureBuffers() {
    MemoryStack {
        val temp = malloc(8 * 4 * 4L)
        val tempPtr = temp.ptr

        resourceManager.shaderStorageBlockResource.entries.values.forEach {
            glGetIntegeri_v(GL_SHADER_STORAGE_BUFFER_BINDING, it.bindingIndex, tempPtr)
            val boundBufferID = tempPtr.getInt()
            val bufferIndex = getBufferIndex(boundBufferID)

            glGetInteger64i_v(GL_SHADER_STORAGE_BUFFER_START, it.bindingIndex, tempPtr)
            val bufferOffset = tempPtr.getLong()

            storageBufferBinding(it.name, bufferIndex, shaderInfo.ssbos[it.name]!!.binding, bufferOffset)
        }

        resourceManager.uniformBlockResource.entries.values.forEach {
            glGetIntegeri_v(GL_UNIFORM_BUFFER_BINDING, it.bindingIndex, tempPtr)
            val boundBufferID = tempPtr.getInt()
            val bufferIndex = getBufferIndex(boundBufferID)

            glGetInteger64i_v(GL_UNIFORM_BUFFER_START, it.bindingIndex, tempPtr)
            val bufferOffset = tempPtr.getLong()

            uniformBufferBinding(it.name, bufferIndex, shaderInfo.ubos[it.name]!!.binding, bufferOffset)
        }
    }
}

private fun CaptureContext.captureShaderProgramResources() {
    captureDefaultUniformBlock()
    captureImages()
    captureBuffers()
}

private fun saveShader(
    outputPath: Path,
    shaderInfo: ShaderInfo,
    stage: ShaderStage
) {
    val extension = "${stage.shortName}.glsl"
    val glslPath = outputPath.resolve("shader.$extension")
    val spvPath = outputPath.resolve("shader.${stage.shortName}.spv")
    glslPath.writeText(shaderInfo.patchedSource)


    ProcessBuilder()
        .command(
            "glslang",
            "-DGLSLANG=1",
            "-gVS",
            "--target-env",
            "vulkan1.3",
            "-o",
            spvPath.absolutePathString(),
            glslPath.absolutePathString()
        )
        .inheritIO()
        .start()
        .waitFor()
}

@Suppress("LocalVariableName")
fun captureGlDispatchCompute(
    shaderInfo: ShaderInfo,
    outputPath: Path,
    num_groups_x: Int,
    num_groups_y: Int,
    num_groups_z: Int
) {
    val currProgram = glGetInteger(GL_CURRENT_PROGRAM)
    val resourceManager = ShaderProgramResourceManager(currProgram)

    val captureContext = CaptureContext(shaderInfo, resourceManager)
    captureContext.captureShaderProgramResources()

    val command = Command.DispatchCommand(
        x = num_groups_x,
        y = num_groups_y,
        z = num_groups_z
    )

    val resourceCapture = captureContext.build(command)

    CaptureData.save(outputPath, resourceCapture) {
        saveShader(outputPath, shaderInfo, ShaderStage.ComputeShader)
    }

    glDispatchCompute(num_groups_x, num_groups_y, num_groups_z)
}

fun captureGlDispatchComputeIndirect(shaderInfo: ShaderInfo, outputPath: Path, indirect: Long) {
    val currProgram = glGetInteger(GL_CURRENT_PROGRAM)
    val resourceManager = ShaderProgramResourceManager(currProgram)

    val captureContext = CaptureContext(shaderInfo, resourceManager)
    captureContext.captureShaderProgramResources()

    val boundIndirectBuffer = glGetInteger(GL_DISPATCH_INDIRECT_BUFFER_BINDING)
    val bufferIndex = captureContext.getBufferIndex(boundIndirectBuffer)

    val command = Command.DispatchIndirectCommand(
        bufferIndex = bufferIndex,
        offset = indirect
    )

    val resourceCapture = captureContext.build(command)

    CaptureData.save(outputPath, resourceCapture) {
        saveShader(outputPath, shaderInfo, ShaderStage.ComputeShader)
    }

    glDispatchComputeIndirect(indirect)
}