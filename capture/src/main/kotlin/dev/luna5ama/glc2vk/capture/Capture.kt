package dev.luna5ama.glc2vk.capture

import dev.luna5ama.glc2vk.common.*
import dev.luna5ama.glwrapper.ShaderProgramResourceManager
import dev.luna5ama.glwrapper.base.*
import dev.luna5ama.glwrapper.enums.GLSLDataType
import dev.luna5ama.glwrapper.enums.GLSLDataType.UniformType
import dev.luna5ama.glwrapper.objects.BufferObject
import dev.luna5ama.kmogus.Arr
import dev.luna5ama.kmogus.MemoryStack
import dev.luna5ama.kmogus.Ptr
import dev.luna5ama.kmogus.ensureCapacity
import dev.luna5ama.kmogus.memcpy

private class CaptureContext(val shaderInfo: ShaderInfo, val resourceManager: ShaderProgramResourceManager) {
    val tempGPUBuffer = BufferObject.Immutable()

    fun ensureTempGPUBufferCapacity(size: Long) {
        if (tempGPUBuffer.size < size) {
            tempGPUBuffer.destroy()
            tempGPUBuffer.allocate(size, GL_MAP_READ_BIT or GL_CLIENT_STORAGE_BIT)
        }
    }

    val images = mutableListOf<Arr>()
    val imageMetadata = mutableListOf<ImageMetadata>()
    val samplerBindings = mutableListOf<SamplerBinding>()
    val imageBindings = mutableListOf<ImageBinding>()

    fun samplerBinding(name: String, imageIndex: Int, samplerInfo: SamplerInfo, bindingIndex: Int) {
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
        val binding = ImageBinding(
            name = name,
            imageIndex = imageIndex,
            set = 1,
            binding = bindingIndex
        )
        imageBindings += binding
    }

    val buffers = mutableListOf<Arr>()
    val bufferMetadata = mutableListOf<BufferMetadata>()
    val storageBufferBindings = mutableListOf<BufferBinding>()
    val uniformBufferBindings = mutableListOf<BufferBinding>()

    fun storageBufferBinding(name: String, bufferIndex: Int, bindingIndex: Int, offset: Long) {
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
        val binding = BufferBinding(
            name = name,
            bufferIndex = bufferIndex,
            set = 3,
            binding = bindingIndex,
            offset = offset
        )
        uniformBufferBindings += binding
    }

    fun build(): ResourceCapture {
        val metadata = ResourceMetadata(
            images = imageMetadata,
            buffers = bufferMetadata,
            samplerBindings = samplerBindings,
            imageBindings = imageBindings,
            storageBufferBindings = storageBufferBindings,
            uniformBufferBindings = uniformBufferBindings
        )
        return ResourceCapture(
            metadata = metadata,
            imageData = images,
            bufferData = buffers
        )
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

    buffers += defaultUniformData.realloc(struct.size.toLong(), false)
    bufferMetadata += BufferMetadata(
        name = "DefaultUniforms",
        size = buffers.last().len
    )
    uniformBufferBinding("DefaultUniforms", 0, 0, 0L)
}

private fun CaptureContext.captureImages() {

}

private fun CaptureContext.captureBuffers() {
    val bufferIDToIndex = mutableMapOf<Int, Int>()

    fun getBufferIndex(boundBufferID: Int, tempPtr: Ptr): Int {
        if (bufferIDToIndex.putIfAbsent(boundBufferID, buffers.size) == null) {
            glGetNamedBufferParameteriv(boundBufferID, GL_BUFFER_SIZE, tempPtr)
            val bufferSize = tempPtr.getInt()
            ensureTempGPUBufferCapacity(bufferSize.toLong())
            glCopyNamedBufferSubData(boundBufferID, tempGPUBuffer.id, 0L, 0L, bufferSize.toLong())

            val bufferData = tempGPUBuffer.map(GL_MAP_READ_BIT)
            val cpuBufferData = Arr.malloc(bufferSize.toLong())
            memcpy(bufferData.ptr, 0L, cpuBufferData.ptr, 0L, bufferSize.toLong())

            buffers.add(cpuBufferData)
        }
        val bufferIndex = bufferIDToIndex[boundBufferID]!!
        return bufferIndex
    }

    MemoryStack {
        val temp = malloc(8 * 4 * 4L)
        val tempPtr = temp.ptr

        resourceManager.shaderStorageBlockResource.entries.values.forEach {
            glGetIntegeri_v(GL_SHADER_STORAGE_BUFFER_BINDING, it.bindingIndex, tempPtr)
            val boundBufferID = tempPtr.getInt()
            val bufferIndex = getBufferIndex(boundBufferID, tempPtr)

            glGetInteger64i_v(GL_SHADER_STORAGE_BUFFER_START, it.bindingIndex, tempPtr)
            val bufferOffset = tempPtr.getLong()

            storageBufferBinding(it.name, bufferIndex, shaderInfo.ssbos[it.name]!!.binding, bufferOffset)
        }

        resourceManager.uniformBlockResource.entries.values.forEach {
            glGetIntegeri_v(GL_UNIFORM_BUFFER_BINDING, it.bindingIndex, tempPtr)
            val boundBufferID = tempPtr.getInt()
            val bufferIndex = getBufferIndex(boundBufferID, tempPtr)

            glGetInteger64i_v(GL_UNIFORM_BUFFER_START, it.bindingIndex, tempPtr)
            val bufferOffset = tempPtr.getLong()

            uniformBufferBinding(it.name, bufferIndex, shaderInfo.ssbos[it.name]!!.binding, bufferOffset)
        }
    }
}

private fun CaptureContext.captureShaderProgramResources() {
    captureDefaultUniformBlock()
    captureImages()
    captureBuffers()
}

@Suppress("LocalVariableName")
fun captureGlDispatchCompute(
    shaderInfo: ShaderInfo,
    num_groups_x: Int,
    num_groups_y: Int,
    num_groups_z: Int
) {
    val currProgram = glGetInteger(GL_CURRENT_PROGRAM)
    val resourceManager = ShaderProgramResourceManager(currProgram)

    val captureContext = CaptureContext(shaderInfo, resourceManager)
    captureContext.captureShaderProgramResources()

    glDispatchCompute(num_groups_x, num_groups_y, num_groups_z)
}

@Suppress("LocalVariableName")
fun captureGlDispatchComputeIndirect(shaderInfo: ShaderInfo, indirect: Long) {
    val currProgram = glGetInteger(GL_CURRENT_PROGRAM)
    val resourceManager = ShaderProgramResourceManager(currProgram)

    val captureContext = CaptureContext(shaderInfo, resourceManager)
    captureContext.captureShaderProgramResources()
    val resourceCapture = captureContext.build()
    resourceCapture.apply {
        free()
    }

    glDispatchComputeIndirect(indirect)
}