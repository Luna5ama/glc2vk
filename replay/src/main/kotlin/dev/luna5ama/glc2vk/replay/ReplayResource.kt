package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.common.CaptureData
import dev.luna5ama.glc2vk.common.ImageMetadata
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

    val imageList: List<VkImage>
    val imageBufferList: List<ImageBufferInfo>
    val samplerImageViewList: List<VkImageView>
    val storageImageViewList: List<VkImageView>

    val imageDeviceMemory: DoubleData<VkDeviceMemory>?
    val bufferDeviceMemory: DoubleData<VkDeviceMemory>?

    data class ImageBufferInfo(
        val buffer: VkBuffer,
        val mipLevelDataOffset: LongArrayList
    )

    context(_: MemoryStack)
    private fun allocateAndBindMemoryForBuffers(
        buffers: List<VkBuffer>,
        allocator: MemorySuballocator,
        suballocateOffsets: LongArrayList,
        memoryType: UInt
    ): VkDeviceMemory = MemoryStack {
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

        deviceMemory
    }


    context(_: MemoryStack)
    private fun allocateAndBindMemoryForImages(
        images: List<VkImage>,
        allocator: MemorySuballocator,
        suballocateOffsets: LongArrayList,
        memoryType: UInt
    ): VkDeviceMemory = MemoryStack {
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

        deviceMemory
    }

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
                                objectHandle = cpuBuffer.value.toULong()
                                pObjectName = "${it.name}_Backup".c_str()
                            }
                            device.setDebugUtilsObjectNameEXT(debugNameInfo.ptr()).getOrThrow()
                            cpuBuffer
                        }

                        DoubleData(cpuBuffer, gpuBuffer)
                    }
                }

                bufferDeviceMemory = if (bufferList.isNotEmpty()) {
                    val cpu = allocateAndBindMemoryForBuffers(
                        bufferList.map { it.cpu },
                        bufferSubAllocator.cpu,
                        bufferSuballocateOffsets.cpu,
                        memoryTypes.findType(
                            VkMemoryPropertyFlags.HOST_VISIBLE +
                                    VkMemoryPropertyFlags.HOST_COHERENT +
                                    VkMemoryPropertyFlags.DEVICE_LOCAL,
                            VkMemoryPropertyFlags.NONE
                        )
//                        memoryTypes.staging
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
                val imageSubAllocator = DoubleData(MemorySuballocator(0L), MemorySuballocator(0L))

                fun NValue<VkImageCreateInfo>.setFrom(metadata: ImageMetadata) {
                    val imageType = when (metadata.type) {
                        dev.luna5ama.glc2vk.common.VkImageViewType.`1D` -> VkImageType.`1D`
                            dev.luna5ama.glc2vk.common.VkImageViewType.`2D` -> VkImageType.`2D`
                            dev.luna5ama.glc2vk.common.VkImageViewType.`3D` -> VkImageType.`3D`
                            dev.luna5ama.glc2vk.common.VkImageViewType.`1D_ARRAY` -> VkImageType.`1D`
                            dev.luna5ama.glc2vk.common.VkImageViewType.`2D_ARRAY` -> VkImageType.`2D`
                            dev.luna5ama.glc2vk.common.VkImageViewType.CUBE -> VkImageType.`2D`
                            dev.luna5ama.glc2vk.common.VkImageViewType.CUBE_ARRAY -> VkImageType.`2D`
                    }
                    this.imageType = imageType
                    this.format = VkFormat.fromNativeData(metadata.format.value)
                    this.extent {
                        width = metadata.width.toUInt()
                        height = metadata.height.toUInt()
                        depth = metadata.depth.toUInt()
                    }
                    this.mipLevels = metadata.mipLevels.toUInt()
                    this.arrayLayers = metadata.arrayLayers.toUInt()
                    this.samples = VkSampleCountFlags.`1_BIT`
                }

                imageList = captureData.metadata.images.map {
                    MemoryStack {
                        val vkImageGPU = MemoryStack {
                            val createInfo = VkImageCreateInfo.allocate()
                            createInfo.setFrom(it)
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

                        vkImageGPU
                    }
                }
                imageBufferList = captureData.metadata.images.map { metadata ->
                    MemoryStack {
                        val mipDataSuballocator = MemorySuballocator(0L)
                        val mipLevelDataOffsets = LongArrayList()
                        metadata.levelDataSizes.forEach {
                            mipLevelDataOffsets.add(mipDataSuballocator.allocate(it, 128L))
                        }

                        val usageCPU = VkBufferUsageFlags2.TRANSFER_SRC

                        val createInfoCPU = VkBufferCreateInfo.allocate()
                        createInfoCPU.sharingMode = VkSharingMode.EXCLUSIVE
                        createInfoCPU.queueFamilyIndexes(queueFamiliesIndicesNArray)
                        createInfoCPU.size = mipDataSuballocator.allocatedSize.toULong()

                        val flagInfoCPU = VkBufferUsageFlags2CreateInfo.allocate {
                            this.usage = usageCPU
                        }
                        createInfoCPU.pNext = flagInfoCPU.ptr()

                        val memReqCPU = VkMemoryRequirements.allocate()
                        val cpuBuffer = device.createBuffer(createInfoCPU.ptr(), nullptr()).getOrThrow()
                        device.getBufferMemoryRequirements(cpuBuffer, memReqCPU.ptr())
                        imageMemoryTypeBits.cpu += VkMemoryPropertyFlags.fromNativeData(memReqCPU.memoryTypeBits.toInt())
                        imageSuballocateOffsets.cpu.add(
                            imageSubAllocator.cpu.allocate(
                                memReqCPU.size.toLong(),
                                memReqCPU.alignment.toLong()
                            )
                        )
                        val debugNameInfo = VkDebugUtilsObjectNameInfoEXT.allocate {
                            objectType = VkObjectType.BUFFER
                            objectHandle = cpuBuffer.value.toULong()
                            pObjectName = "${metadata.name}_Backup".c_str()
                        }
                        device.setDebugUtilsObjectNameEXT(debugNameInfo.ptr()).getOrThrow()

                        ImageBufferInfo(
                            cpuBuffer,
                            mipLevelDataOffsets
                        )
                    }
                }

                imageDeviceMemory = MemoryStack {

                    if (imageList.isNotEmpty()) {
                        val cpu = allocateAndBindMemoryForBuffers(
                            imageBufferList.map { it.buffer },
                            imageSubAllocator.cpu,
                            imageSuballocateOffsets.cpu,
                            memoryTypes.findType(
                                VkMemoryPropertyFlags.HOST_VISIBLE +
                                        VkMemoryPropertyFlags.HOST_COHERENT +
                                        VkMemoryPropertyFlags.DEVICE_LOCAL,
                                VkMemoryPropertyFlags.NONE
                            )
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

                        captureData.imageData.forEachIndexed { imageIndex, imageData ->
                            val bufferOffset = imageSuballocateOffsets.cpu.getLong(imageIndex)
                            val offsetInBuffer = imageBufferList[imageIndex].mipLevelDataOffset
                            imageData.levels.forEachIndexed { levelIndex, levelData ->
                                val offset = bufferOffset + offsetInBuffer.getLong(levelIndex)
                                val dataWrapped = NPointer<NUInt8>(levelData.ptr.address)
                                dataWrapped.copyTo(mappedPtr + offset, levelData.len)
                            }
                        }

                        device.unmapMemory(cpu)
                        DoubleData(
                            cpu,
                            allocateAndBindMemoryForImages(
                                imageList,
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

                samplerImageViewList = captureData.metadata.samplerBindings.map { binding ->
                    val imageIndex = binding.imageIndex
                    val metadata = captureData.metadata.images[imageIndex]
                    val createInfo = VkImageViewCreateInfo.allocate {
                        image = imageList[imageIndex]
                        viewType = VkImageViewType.fromNativeData(metadata.type.value)
                        format = VkFormat.fromNativeData(metadata.format.value)
                        subresourceRange {
                            aspectMask = VkImageAspectFlags.COLOR
                            baseMipLevel = 0u
                            levelCount = metadata.mipLevels.toUInt()
                            baseArrayLayer = 0u
                            layerCount = metadata.arrayLayers.toUInt()
                        }
                    }
                    val imageView = device.createImageView(createInfo.ptr(), nullptr()).getOrThrow()
                    val debugNameInfo = VkDebugUtilsObjectNameInfoEXT.allocate {
                        objectType = VkObjectType.IMAGE_VIEW
                        objectHandle = imageView.value.toULong()
                        pObjectName = "${binding.name}_View".c_str()
                    }
                    device.setDebugUtilsObjectNameEXT(debugNameInfo.ptr()).getOrThrow()
                    imageView
                }

                storageImageViewList = captureData.metadata.imageBindings.map { binding ->
                    val imageIndex = binding.imageIndex
                    val metadata = captureData.metadata.images[imageIndex]
                    val createInfo = VkImageViewCreateInfo.allocate {
                        image = imageList[imageIndex]
                        viewType = VkImageViewType.fromNativeData(metadata.type.value)
                        format = VkFormat.fromNativeData(binding.format.value)
                        subresourceRange {
                            aspectMask = VkImageAspectFlags.COLOR
                            baseMipLevel = 0u
                            levelCount = metadata.mipLevels.toUInt()
                            baseArrayLayer = 0u
                            layerCount = metadata.arrayLayers.toUInt()
                        }
                    }
                    val imageView = device.createImageView(createInfo.ptr(), nullptr()).getOrThrow()
                    val debugNameInfo = VkDebugUtilsObjectNameInfoEXT.allocate {
                        objectType = VkObjectType.IMAGE_VIEW
                        objectHandle = imageView.value.toULong()
                        pObjectName = "${binding.name}_View".c_str()
                    }
                    device.setDebugUtilsObjectNameEXT(debugNameInfo.ptr()).getOrThrow()
                    imageView
                }
            }
        }
    }

    fun destroy() {
        samplerImageViewList.forEach {
            device.destroyImageView(it, null)
        }
        storageImageViewList.forEach {
            device.destroyImageView(it, null)
        }
        imageList.forEach {
            device.destroyImage(it, null)
        }
        imageBufferList.forEach {
            device.destroyBuffer(it.buffer, null)
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