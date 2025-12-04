package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.common.CaptureData
import dev.luna5ama.glc2vk.common.VkImageViewType
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.echonolix.caelum.*
import net.echonolix.caelum.vulkan.*
import net.echonolix.caelum.vulkan.enums.*
import net.echonolix.caelum.vulkan.flags.*
import net.echonolix.caelum.vulkan.handles.*
import net.echonolix.caelum.vulkan.structs.*
import java.lang.foreign.Arena

class ReplayResource(
    private val captureData: CaptureData,
    private val device: VkDevice,
    private val graphicsQueueFamilyIndex: UInt,
    private val memoryTypes: MemoryTypeManager
) {
    data class DoubleData<T>(val cpu: T, val gpu: T)
    data class MutableDoubleData<T>(var cpu: T, var gpu: T)

    val bufferSuballocateOffsets = DoubleData(LongArrayList(), LongArrayList())
    val bufferList: List<DoubleData<VkBuffer>>

    val imageMemoryTypeBits = MutableDoubleData(VkMemoryPropertyFlags.NONE, VkMemoryPropertyFlags.NONE)
    val imageSuballocateOffsets = DoubleData(LongArrayList(), LongArrayList())
    val imageSubAllocator = DoubleData(MemorySuballocator(0L), MemorySuballocator(0L))

    val imageList: List<DoubleData<VkImage>>

    val imageDeviceMemory: DoubleData<VkDeviceMemory>?
    val bufferDeviceMemory: DoubleData<VkDeviceMemory>?

    init {
        MemoryStack {
            val queueFamiliesIndicesNArray = NUInt32.arrayOf(graphicsQueueFamilyIndex)

            MemoryStack {
                val bufferSubAllocator = DoubleData(MemorySuballocator(0L), MemorySuballocator(0L))
                val bufferMemoryTypeBits = MutableDoubleData(VkMemoryPropertyFlags.NONE, VkMemoryPropertyFlags.NONE)
                bufferList = captureData.metadata.buffers.map {
                    MemoryStack {
                        val gpuBuffer = MemoryStack {
                            val usages = VkBufferUsageFlags2.STORAGE_TEXEL_BUFFER +
                                    VkBufferUsageFlags2.UNIFORM_TEXEL_BUFFER +
                                    VkBufferUsageFlags2.STORAGE_BUFFER +
                                    VkBufferUsageFlags2.UNIFORM_BUFFER +
                                    VkBufferUsageFlags2.TRANSFER_DST +
                                    VkBufferUsageFlags2.INDIRECT_BUFFER

                            val createInfo = VkBufferCreateInfo.allocate()
                            createInfo.sharingMode = VkSharingMode.EXCLUSIVE
                            createInfo.queueFamilyIndexes(queueFamiliesIndicesNArray)
                            createInfo.size = it.size.toULong()

                            val flagInfo = VkBufferUsageFlags2CreateInfo.allocate {
                                this.usage = usages
                            }
                            createInfo.pNext = flagInfo.ptr()

                            val memReqGPU = VkMemoryRequirements.allocate()

                            val gpuBuffer = device.createBuffer(createInfo.ptr(), nullptr()).getOrThrow()
                            device.getBufferMemoryRequirements(gpuBuffer, memReqGPU.ptr())
                            bufferMemoryTypeBits.gpu += VkMemoryPropertyFlags.fromNativeData(memReqGPU.memoryTypeBits.toInt())
                            bufferSuballocateOffsets.gpu.add(
                                bufferSubAllocator.gpu.allocate(
                                    memReqGPU.size.toLong(),
                                    memReqGPU.alignment.toLong()
                                )
                            )
                            val debugNameInfo = VkDebugUtilsObjectNameInfoEXT.allocate {
                                objectType = VkObjectType.BUFFER
                                objectHandle = gpuBuffer.value.toULong()
                                pObjectName = it.name.c_str()
                            }
                            device.setDebugUtilsObjectNameEXT(debugNameInfo.ptr()).getOrThrow()
                            gpuBuffer
                        }

                        val cpuBuffer = MemoryStack {
                            val usageCPU = VkBufferUsageFlags2.TRANSFER_SRC

                            val createInfoCPU = VkBufferCreateInfo.allocate()
                            createInfoCPU.sharingMode = VkSharingMode.EXCLUSIVE
                            createInfoCPU.queueFamilyIndexes(queueFamiliesIndicesNArray)
                            createInfoCPU.size = it.size.toULong()

                            val flagInfoCPU = VkBufferUsageFlags2CreateInfo.allocate {
                                this.usage = usageCPU
                            }
                            createInfoCPU.pNext = flagInfoCPU.ptr()

                            val memReqCPU = VkMemoryRequirements.allocate()
                            val cpuBuffer = device.createBuffer(createInfoCPU.ptr(), nullptr()).getOrThrow()
                            device.getBufferMemoryRequirements(cpuBuffer, memReqCPU.ptr())
                            bufferMemoryTypeBits.cpu += VkMemoryPropertyFlags.fromNativeData(memReqCPU.memoryTypeBits.toInt())
                            bufferSuballocateOffsets.cpu.add(
                                bufferSubAllocator.cpu.allocate(
                                    memReqCPU.size.toLong(),
                                    memReqCPU.alignment.toLong()
                                )
                            )
                            val debugNameInfo = VkDebugUtilsObjectNameInfoEXT.allocate {
                                objectType = VkObjectType.BUFFER
                                objectHandle = gpuBuffer.value.toULong()
                                pObjectName = "${it.name}_Backup".c_str()
                            }
                            device.setDebugUtilsObjectNameEXT(debugNameInfo.ptr()).getOrThrow()
                            cpuBuffer
                        }

                        DoubleData(cpuBuffer, gpuBuffer)
                    }
                }

                fun allocateAndBindMemoryForBuffers(
                    buffers: List<VkBuffer>,
                    allocator: MemorySuballocator,
                    suballocateOffsets: LongArrayList,
                    memoryType: UInt
                ): VkDeviceMemory {
                    // Allocate memory for all buffers
                    val memoryAllocateInfo = VkMemoryAllocateInfo.allocate {
                        allocationSize = allocator.allocatedSize.toULong()
                        memoryTypeIndex = memoryType
                    }
                    val deviceMemoryReturn = VkDeviceMemory.malloc()
                    device.allocateMemory(memoryAllocateInfo.ptr(), nullptr(), deviceMemoryReturn.ptr()).getOrThrow()
                    val deviceMemory = VkDeviceMemory.fromNativeData(device, deviceMemoryReturn.value)

                    // Using heap allocation because buffer count can be large
                    Arena.ofConfined().useAllocateScope {
                        val bufferCount = buffers.size.toLong()
                        // Bind buffers to memory
                        val bindInfos = VkBindBufferMemoryInfo.allocate(bufferCount)
                        for (i in 0L..<bufferCount) {
                            bindInfos[i].buffer = buffers[i.toInt()]
                            bindInfos[i].memory = deviceMemory
                            bindInfos[i].memoryOffset = suballocateOffsets.getLong(i.toInt()).toULong()
                        }
                        device.bindBufferMemory2(bufferCount.toUInt(), bindInfos.ptr()).getOrThrow()
                        deviceMemory
                    }

                    return deviceMemory
                }

                bufferDeviceMemory = if (bufferList.isNotEmpty()) {
                    val cpu = allocateAndBindMemoryForBuffers(
                        bufferList.map { it.cpu },
                        bufferSubAllocator.cpu,
                        bufferSuballocateOffsets.cpu,
                        memoryTypes.staging
//                    memoryTypes.run {
//                        findType(
//                            bufferMemoryTypeBits.cpu,
//                            VkMemoryPropertyFlags.DEVICE_LOCAL
//                        ).findType(
//                            bufferMemoryTypeBits.cpu,
//                            VkMemoryPropertyFlags.NONE
//                        )
//                    }
                    )


                    val temp = NPointer.malloc<NUInt8>(1)
                    @Suppress("UNCHECKED_CAST")
                    device.mapMemory(
                        cpu, 0UL, VK_WHOLE_SIZE, VkMemoryMapFlags.NONE,
                        temp.ptr() as NPointer<NPointer<*>>
                    ).getOrThrow()

                    val mappedPtr = temp[0]

                    captureData.bufferData.forEachIndexed { index, data ->
                        val offset = bufferSuballocateOffsets.cpu.getLong(index)
                        val dataWrapped = NPointer<NUInt8>(data.ptr.address)
                        dataWrapped.copyTo(mappedPtr + offset, data.len)
                    }

                    device.unmapMemory(cpu)

                    DoubleData(
                        cpu,
                        allocateAndBindMemoryForBuffers(
                            bufferList.map { it.gpu },
                            bufferSubAllocator.gpu,
                            bufferSuballocateOffsets.gpu,
                            memoryTypes.device
//                    memoryTypes.findType(
//                        bufferMemoryTypeBits.gpu + VkMemoryPropertyFlags.DEVICE_LOCAL,
//                        VkMemoryPropertyFlags.NONE
//                    )
                        )
                    )
                } else {
                    null
                }
            }

            MemoryStack {
                imageList = captureData.metadata.images.map {
                    MemoryStack {
                        val imageType = when (it.type) {
                            VkImageViewType.`1D` -> VkImageType.`1D`
                            VkImageViewType.`2D` -> VkImageType.`2D`
                            VkImageViewType.`3D` -> VkImageType.`3D`
                            VkImageViewType.`1D_ARRAY` -> VkImageType.`1D`
                            VkImageViewType.`2D_ARRAY` -> VkImageType.`2D`
                            VkImageViewType.CUBE -> VkImageType.`2D`
                            VkImageViewType.CUBE_ARRAY -> VkImageType.`2D`
                        }
                        val vkImageGPU = MemoryStack {
                            val createInfo = VkImageCreateInfo.allocate()
                            createInfo.imageType = imageType
                            createInfo.format = VkFormat.fromNativeData(it.format.value)
                            createInfo.extent.width = it.width.toUInt()
                            createInfo.extent.height = it.height.toUInt()
                            createInfo.extent.depth = it.depth.toUInt()
                            createInfo.mipLevels = it.mipLevels.toUInt()
                            createInfo.arrayLayers = it.arrayLayers.toUInt()
                            createInfo.samples = VkSampleCountFlags.`1_BIT`
                            createInfo.tiling = VkImageTiling.OPTIMAL
                            createInfo.initialLayout = VkImageLayout.UNDEFINED
                            createInfo.usage =
                                VkImageUsageFlags.TRANSFER_DST + VkImageUsageFlags.STORAGE + VkImageUsageFlags.SAMPLED

                            val memReq = VkMemoryRequirements.allocate()
                            val vkImage = device.createImage(createInfo.ptr(), nullptr()).getOrThrow()
                            device.getImageMemoryRequirements(vkImage, memReq.ptr())
                            imageMemoryTypeBits.gpu += VkMemoryPropertyFlags.fromNativeData(memReq.memoryTypeBits.toInt())
                            imageSuballocateOffsets.gpu.add(
                                imageSubAllocator.gpu.allocate(
                                    memReq.size.toLong(),
                                    memReq.alignment.toLong()
                                )
                            )
                            val debugNameInfo = VkDebugUtilsObjectNameInfoEXT.allocate {
                                objectType = VkObjectType.IMAGE
                                objectHandle = vkImage.value.toULong()
                                pObjectName = it.name.c_str()
                            }
                            device.setDebugUtilsObjectNameEXT(debugNameInfo.ptr()).getOrThrow()
                            vkImage
                        }

                        // TODO mipmap support
                        val vkImageCPU = MemoryStack {
                            val createInfo = VkImageCreateInfo.allocate()
                            createInfo.imageType = imageType
                            createInfo.format = VkFormat.fromNativeData(it.format.value)
                            createInfo.extent.width = it.width.toUInt()
                            createInfo.extent.height = it.height.toUInt()
                            createInfo.extent.depth = it.depth.toUInt()
                            createInfo.mipLevels = it.mipLevels.toUInt()
                            createInfo.arrayLayers = it.arrayLayers.toUInt()
                            createInfo.samples = VkSampleCountFlags.`1_BIT`
                            createInfo.tiling = VkImageTiling.LINEAR
                            createInfo.usage = VkImageUsageFlags.TRANSFER_SRC
                            createInfo.initialLayout = VkImageLayout.PREINITIALIZED

                            val memReq = VkMemoryRequirements.allocate()
                            val vkImage = device.createImage(createInfo.ptr(), nullptr()).getOrThrow()
                            device.getImageMemoryRequirements(vkImage, memReq.ptr())
                            imageMemoryTypeBits.cpu += VkMemoryPropertyFlags.fromNativeData(memReq.memoryTypeBits.toInt())
                            imageSuballocateOffsets.cpu.add(
                                imageSubAllocator.cpu.allocate(
                                    memReq.size.toLong(),
                                    memReq.alignment.toLong()
                                )
                            )
                            val debugNameInfo = VkDebugUtilsObjectNameInfoEXT.allocate {
                                objectType = VkObjectType.IMAGE
                                objectHandle = vkImage.value.toULong()
                                pObjectName = "${it.name}_Backup".c_str()
                            }
                            device.setDebugUtilsObjectNameEXT(debugNameInfo.ptr()).getOrThrow()
                            vkImage
                        }

                        DoubleData(vkImageCPU, vkImageGPU)
                    }
                }

                fun allocateAndBindMemoryForImages(
                    images: List<VkImage>,
                    allocator: MemorySuballocator,
                    suballocateOffsets: LongArrayList,
                    memoryType: UInt
                ): VkDeviceMemory {
                    // Allocate memory for all buffers
                    val memoryAllocateInfo = VkMemoryAllocateInfo.allocate {
                        allocationSize = allocator.allocatedSize.toULong()
                        memoryTypeIndex = memoryType
                    }
                    val deviceMemoryReturn = VkDeviceMemory.malloc()
                    device.allocateMemory(memoryAllocateInfo.ptr(), nullptr(), deviceMemoryReturn.ptr()).getOrThrow()
                    val deviceMemory = VkDeviceMemory.fromNativeData(device, deviceMemoryReturn.value)

                    // Using heap allocation because buffer count can be large
                    Arena.ofConfined().useAllocateScope {
                        val bufferCount = images.size.toLong()
                        // Bind buffers to memory
                        val bindInfos = VkBindImageMemoryInfo.allocate(bufferCount)
                        for (i in 0L..<bufferCount) {
                            bindInfos[i].image = images[i.toInt()]
                            bindInfos[i].memory = deviceMemory
                            bindInfos[i].memoryOffset = suballocateOffsets.getLong(i.toInt()).toULong()
                        }
                        device.bindImageMemory2(bufferCount.toUInt(), bindInfos.ptr()).getOrThrow()
                        deviceMemory
                    }

                    return deviceMemory
                }

                imageDeviceMemory = if (imageList.isNotEmpty()) {
                    val cpu = allocateAndBindMemoryForImages(
                        imageList.map { it.cpu },
                        imageSubAllocator.cpu,
                        imageSuballocateOffsets.cpu,
                        memoryTypes.staging
//                    memoryTypes.run {
//                        findType(
//                            imageMemoryTypeBits.cpu,
//                            VkMemoryPropertyFlags.DEVICE_LOCAL
//                        ).findType(
//                            imageMemoryTypeBits.cpu,
//                            VkMemoryPropertyFlags.NONE
//                        )
//                    }
                    )

                    val temp = NPointer.malloc<NUInt8>(1)
                    @Suppress("UNCHECKED_CAST")
                    device.mapMemory(
                        cpu, 0UL, VK_WHOLE_SIZE, VkMemoryMapFlags.NONE,
                        temp.ptr() as NPointer<NPointer<*>>
                    ).getOrThrow()

                    val mappedPtr = temp[0]

                    captureData.imageData.forEachIndexed { index, dataV ->
                        val data = dataV.levels.first()
                        val offset = imageSuballocateOffsets.cpu.getLong(index)
                        val dataWrapped = NPointer<NUInt8>(data.ptr.address)
                        dataWrapped.copyTo(mappedPtr + offset, data.len)
                    }

                    device.unmapMemory(cpu)
                    DoubleData(
                        cpu,
                        allocateAndBindMemoryForImages(
                            imageList.map { it.gpu },
                            imageSubAllocator.gpu,
                            imageSuballocateOffsets.gpu,
                            memoryTypes.device
//                    memoryTypes.findType(
//                        imageMemoryTypeBits.gpu + VkMemoryPropertyFlags.DEVICE_LOCAL,
//                        VkMemoryPropertyFlags.NONE
//                    )
                        )
                    )
                } else {
                    null
                }
            }
        }
    }

    fun destroy() {
        imageList.forEach {
            device.destroyImage(it.cpu, null)
            device.destroyImage(it.gpu, null)
        }
        bufferList.forEach {
            device.destroyBuffer(it.cpu, null)
            device.destroyBuffer(it.gpu, null)
        }
        imageDeviceMemory?.let {
            device.freeMemory(it.cpu, null)
            device.freeMemory(it.gpu, null)
        }
        bufferDeviceMemory?.let {
            device.freeMemory(it.cpu, null)
            device.freeMemory(it.gpu, null)
        }
    }
}