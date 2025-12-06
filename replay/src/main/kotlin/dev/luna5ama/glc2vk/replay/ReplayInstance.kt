package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.common.CaptureData
import dev.luna5ama.glc2vk.common.Command
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.echonolix.caelum.*
import net.echonolix.caelum.vulkan.*
import net.echonolix.caelum.vulkan.enums.*
import net.echonolix.caelum.vulkan.flags.*
import net.echonolix.caelum.vulkan.handles.*
import net.echonolix.caelum.vulkan.structs.*
import java.lang.foreign.Arena
import java.nio.file.Path

class ReplayInstance(
    private val captureData: CaptureData,
    private val device: VkDevice,
    private val captureDir: Path,
    private val graphicsQueueFamilyIndex: UInt
) {
    private val arena = Arena.ofShared()
    private val scope = arena.asAllocateScope()

    val descriptorPool: VkDescriptorPool

    val memoryProperties: NValue<VkPhysicalDeviceMemoryProperties2>
    val memoryTypes: MemoryTypeManager

    val commandPool: VkCommandPool
    val cmdBuffers: List<VkCommandBuffer>

    val renderFinishedSemaphore: VkSemaphore
    val copyFinishedSemaphore: VkSemaphore
    val imageAvailableSemaphore: VkSemaphore

    val pRenderFinishedSemaphore: NArray<VkSemaphoreHandle>
    val pCopyFinishedSemaphore: NArray<VkSemaphoreHandle>
    val pImageAvailableSemaphore: NArray<VkSemaphoreHandle>


    val inFlightFence: VkFence
    val fences: NArray<VkFenceHandle>

    val dependencyInfo1: NValue<VkDependencyInfo>
    val dependencyInfo2: NValue<VkDependencyInfo>

    val resource: ReplayResource
    private val pipelineInfo: ComputePipelineInfo

    init {
        MemoryStack {
            MemoryStack {
                val extra = 4u
                val createInfo = VkDescriptorPoolCreateInfo.allocate {
                    maxSets = 4u
                    val poolSizes = VkDescriptorPoolSize.allocate(4L)
                    poolSizes[0L].apply {
                        type = VkDescriptorType.UNIFORM_BUFFER
                        descriptorCount = maxOf(16u, captureData.metadata.uniformBufferBindings.size.toUInt() * extra)
                    }
                    poolSizes[1L].apply {
                        type = VkDescriptorType.STORAGE_BUFFER
                        descriptorCount = maxOf(16u, captureData.metadata.storageBufferBindings.size.toUInt() * extra)
                    }
                    poolSizes[2L].apply {
                        type = VkDescriptorType.STORAGE_IMAGE
                        descriptorCount = maxOf(16u, captureData.metadata.imageBindings.size.toUInt() * extra)
                    }
                    poolSizes[3L].apply {
                        type = VkDescriptorType.COMBINED_IMAGE_SAMPLER
                        descriptorCount = maxOf(16u, captureData.metadata.samplerBindings.size.toUInt() * extra)
                    }
                    poolSizes(poolSizes)
                }
                descriptorPool = device.createDescriptorPool(createInfo.ptr(), null).getOrThrow()
            }

            val commandPoolCreateInfo = VkCommandPoolCreateInfo.allocate {
                queueFamilyIndex = graphicsQueueFamilyIndex
            }
            commandPool = device.createCommandPool(commandPoolCreateInfo.ptr(), null).getOrThrow()

            memoryProperties = VkPhysicalDeviceMemoryProperties2.allocate(scope)
            device.physicalDevice.getPhysicalDeviceMemoryProperties2(memoryProperties.ptr())
            memoryTypes = MemoryTypeManager(memoryProperties.memoryProperties)


            MemoryStack {
                val cmdBufCount = 2
                val returnValues = VkCommandBuffer.malloc(scope, cmdBufCount.toLong())
                val commandBufferAllocateInfo = VkCommandBufferAllocateInfo.allocate {
                    commandPool = this@ReplayInstance.commandPool
                    level = VkCommandBufferLevel.PRIMARY
                    commandBufferCount = cmdBufCount.toUInt()
                }
                device.allocateCommandBuffers(commandBufferAllocateInfo.ptr(), returnValues.ptr())
                cmdBuffers = List(cmdBufCount) {
                    VkCommandBuffer.fromNativeData(commandPool, returnValues[it.toLong()])
                }
            }


            MemoryStack {
                val semaphoreCreateInfo = VkSemaphoreCreateInfo.allocate {}
                renderFinishedSemaphore = device.createSemaphore(semaphoreCreateInfo.ptr(), null).getOrThrow()
                copyFinishedSemaphore = device.createSemaphore(semaphoreCreateInfo.ptr(), null).getOrThrow()
                imageAvailableSemaphore = device.createSemaphore(semaphoreCreateInfo.ptr(), null).getOrThrow()


                pRenderFinishedSemaphore = VkSemaphore.arrayOf(scope, renderFinishedSemaphore)
                pCopyFinishedSemaphore = VkSemaphore.arrayOf(scope, copyFinishedSemaphore)
                pImageAvailableSemaphore = VkSemaphore.arrayOf(scope, imageAvailableSemaphore)
            }

            MemoryStack {
                val fenceCreateInfo = VkFenceCreateInfo.allocate {
                    flags = VkFenceCreateFlags.SIGNALED
                }
                inFlightFence = device.createFence(fenceCreateInfo.ptr(), null).getOrThrow()
                fences = VkFence.arrayOf(scope, inFlightFence)
            }

            resource = ReplayResource(captureData, device, graphicsQueueFamilyIndex, memoryTypes)

            dependencyInfo1 = VkDependencyInfo.allocate(scope) {
                val bufferMemoryBarriers = VkBufferMemoryBarrier2.allocate(scope, resource.bufferList.size.toLong())
                resource.bufferList.forEachIndexed { i, buffer ->
                    bufferMemoryBarriers[i.toLong()].apply {
                        srcStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                        srcAccessMask = VkAccessFlags2.NONE
                        dstStageMask = VkPipelineStageFlags2.COPY
                        dstAccessMask = VkAccessFlags2.TRANSFER_WRITE

                        ofWholeBuffer(buffer.gpu)
                    }
                }
                bufferMemoryBarriers(bufferMemoryBarriers)
                val imageMemoryBarriers = VkImageMemoryBarrier2.allocate(scope, resource.imageList.size.toLong())
                resource.imageList.forEachIndexed { i, image ->
                    imageMemoryBarriers[i.toLong()].apply {
                        srcStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                        srcAccessMask = VkAccessFlags2.NONE
                        dstStageMask = VkPipelineStageFlags2.COPY
                        dstAccessMask = VkAccessFlags2.TRANSFER_WRITE
                        oldLayout = VkImageLayout.UNDEFINED
                        newLayout = VkImageLayout.TRANSFER_DST_OPTIMAL

                        ofWholeImage(image.gpu, captureData.metadata.images[i].dataType.toAspectFlags())
                    }
                }
                imageMemoryBarriers(imageMemoryBarriers)
            }

            dependencyInfo2 = VkDependencyInfo.allocate(scope) {
                val bufferMemoryBarriers =
                    VkBufferMemoryBarrier2.allocate(scope, captureData.metadata.buffers.size.toLong())
                resource.bufferList.forEachIndexed { i, buffer ->
                    bufferMemoryBarriers[i.toLong()].apply {
                        srcStageMask = VkPipelineStageFlags2.COPY
                        srcAccessMask = VkAccessFlags2.TRANSFER_WRITE
                        dstStageMask = VkPipelineStageFlags2.ALL_COMMANDS + VkPipelineStageFlags2.DRAW_INDIRECT
                        dstAccessMask = VkAccessFlags2.MEMORY_READ + VkAccessFlags2.INDIRECT_COMMAND_READ

                        ofWholeBuffer(buffer.gpu)
                    }
                }
                bufferMemoryBarriers(bufferMemoryBarriers)
                val imageMemoryBarriers =
                    VkImageMemoryBarrier2.allocate(scope, captureData.metadata.images.size.toLong())
                resource.imageList.forEachIndexed { i, image ->
                    imageMemoryBarriers[i.toLong()].apply {
                        srcStageMask = VkPipelineStageFlags2.COPY
                        srcAccessMask = VkAccessFlags2.TRANSFER_WRITE
                        dstStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                        dstAccessMask = VkAccessFlags2.MEMORY_READ
                        oldLayout = VkImageLayout.TRANSFER_DST_OPTIMAL
                        newLayout = VkImageLayout.GENERAL

                        ofWholeImage(image.gpu, captureData.metadata.images[i].dataType.toAspectFlags())
                    }
                }
                imageMemoryBarriers(imageMemoryBarriers)
            }

            MemoryStack {
                pipelineInfo = makeComputePipeline()
            }
        }
    }

    context(_: MemoryStack)
    fun init(queue: VkQueue) = MemoryStack {
        data class ImageBufferInfo(
            val buffer: VkBuffer,
            val mipLevelDataOffset: LongArrayList
        )

        val imageSubAllocator = MemorySuballocator(0L)
        val imageStagingBufferSubAllocationOffsets = LongArrayList()
        val queueFamiliesIndicesNArray = NUInt32.arrayOf(graphicsQueueFamilyIndex)

        val imageBufferList = captureData.metadata.images.map { metadata ->
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
                imageStagingBufferSubAllocationOffsets.add(
                    imageSubAllocator.allocate(
                        memReqCPU.size.toLong(),
                        memReqCPU.alignment.toLong()
                    )
                )
                val debugNameInfo = VkDebugUtilsObjectNameInfoEXT.allocate {
                    objectType = VkObjectType.BUFFER
                    objectHandle = cpuBuffer.value.toULong()
                    pObjectName = "${metadata.name}_Temp".c_str()
                }
                device.setDebugUtilsObjectNameEXT(debugNameInfo.ptr()).getOrThrow()

                ImageBufferInfo(
                    cpuBuffer,
                    mipLevelDataOffsets
                )
            }
        }

        val imageStagingBufferDeviceMemory = resource.allocateDeviceMemory(
            imageSubAllocator,
            memoryTypes.stagingFast,
            0.0f
        )

        val temp = NPointer.malloc<NUInt8>(1)
        @Suppress("UNCHECKED_CAST")
        device.mapMemory(
            imageStagingBufferDeviceMemory, 0UL, VK_WHOLE_SIZE, VkMemoryMapFlags.NONE,
            temp.ptr() as NPointer<NPointer<*>>
        ).getOrThrow()

        val mappedPtr = temp[0]

        captureData.imageData.forEachIndexed { imageIndex, imageData ->
            val bufferOffset = imageStagingBufferSubAllocationOffsets.getLong(imageIndex)
            val offsetInBuffer = imageBufferList[imageIndex].mipLevelDataOffset
            imageData.levels.forEachIndexed { levelIndex, levelData ->
                val offset = bufferOffset + offsetInBuffer.getLong(levelIndex)
                val dataWrapped = NPointer<NUInt8>(levelData.ptr.address)
                dataWrapped.copyTo(mappedPtr + offset, levelData.len)
            }
        }

        device.unmapMemory(imageStagingBufferDeviceMemory)

        resource.bindMemoryForBuffers(
            imageStagingBufferDeviceMemory,
            imageBufferList.map { it.buffer },
            imageStagingBufferSubAllocationOffsets
        )

        MemoryStack {
            device.waitForFences(1u, fences.ptr(), VK_TRUE, ULong.MAX_VALUE)
            device.resetFences(1u, fences.ptr())

            device.resetCommandPool(commandPool, VkCommandPoolResetFlags.NONE)

            val beginInfo = VkCommandBufferBeginInfo.allocate {
                flags = VkCommandBufferUsageFlags.ONE_TIME_SUBMIT
            }
            cmdBuffers[0].beginCommandBuffer(beginInfo.ptr())
            MemoryStack {
                val dependencyInfo = VkDependencyInfo.allocate {
                    val bufferMemoryBarriers = VkBufferMemoryBarrier2.allocate(imageBufferList.size.toLong())
                    imageBufferList.forEachIndexed { i, imageBufferInfo ->
                        bufferMemoryBarriers[i.toLong()].apply {
                            srcStageMask = VkPipelineStageFlags2.HOST
                            srcAccessMask = VkAccessFlags2.HOST_WRITE
                            dstStageMask = VkPipelineStageFlags2.COPY
                            dstAccessMask = VkAccessFlags2.TRANSFER_READ

                            ofWholeBuffer(imageBufferInfo.buffer)
                        }
                    }
                    bufferMemoryBarriers(bufferMemoryBarriers)
                    val imageMemoryBarriers = VkImageMemoryBarrier2.allocate(resource.imageList.size.toLong())
                    resource.imageList.forEachIndexed { i, image ->
                        imageMemoryBarriers[i.toLong()].apply {
                            srcStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                            srcAccessMask = VkAccessFlags2.NONE
                            dstStageMask = VkPipelineStageFlags2.COPY
                            dstAccessMask = VkAccessFlags2.TRANSFER_WRITE
                            oldLayout = VkImageLayout.UNDEFINED
                            newLayout = VkImageLayout.TRANSFER_DST_OPTIMAL

                            ofWholeImage(image.cpu, captureData.metadata.images[i].dataType.toAspectFlags())
                        }
                    }
                    imageMemoryBarriers(imageMemoryBarriers)
                }
                cmdBuffers[0].cmdPipelineBarrier2(dependencyInfo.ptr())
            }

            (resource.imageList zip imageBufferList).forEachIndexed { imageIndex, (dstImage, srcImages) ->
                MemoryStack {
                    val imageMetadata = captureData.metadata.images[imageIndex]
                    val copyRegions = VkBufferImageCopy.allocate(srcImages.mipLevelDataOffset.size.toLong())

                    for (mip in srcImages.mipLevelDataOffset.indices) {
                        copyRegions[mip.toLong()].apply {
                            bufferOffset = srcImages.mipLevelDataOffset.getLong(mip).toULong()
                            bufferRowLength = 0u
                            bufferImageHeight = 0u

                            imageSubresource {
                                aspectMask = imageMetadata.dataType.toAspectFlags()
                                mipLevel = mip.toUInt()
                                baseArrayLayer = 0u
                                layerCount = imageMetadata.arrayLayers.toUInt()
                            }
                            imageOffset {
                                x = 0
                                y = 0
                                z = 0
                            }
                            imageExtent {
                                width = maxOf(1, imageMetadata.width shr mip).toUInt()
                                height = maxOf(1, imageMetadata.height shr mip).toUInt()
                                depth = maxOf(1, imageMetadata.depth shr mip).toUInt()
                            }
                        }
                    }

                    cmdBuffers[0].cmdCopyBufferToImage(
                        srcImages.buffer,
                        dstImage.cpu,
                        VkImageLayout.TRANSFER_DST_OPTIMAL,
                        srcImages.mipLevelDataOffset.size.toUInt(),
                        copyRegions.ptr()
                    )
                }
            }
            MemoryStack {
                val dependencyInfo = VkDependencyInfo.allocate {
                    val imageMemoryBarriers = VkImageMemoryBarrier2.allocate(resource.imageList.size.toLong())
                    resource.imageList.forEachIndexed { i, image ->
                        imageMemoryBarriers[i.toLong()].apply {
                            srcStageMask = VkPipelineStageFlags2.COPY
                            srcAccessMask = VkAccessFlags2.TRANSFER_WRITE
                            dstStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                            dstAccessMask = VkAccessFlags2.NONE
                            oldLayout = VkImageLayout.TRANSFER_DST_OPTIMAL
                            newLayout = VkImageLayout.TRANSFER_SRC_OPTIMAL

                            ofWholeImage(image.cpu, captureData.metadata.images[i].dataType.toAspectFlags())
                        }
                    }
                    imageMemoryBarriers(imageMemoryBarriers)
                }
                cmdBuffers[0].cmdPipelineBarrier2(dependencyInfo.ptr())
            }

            cmdBuffers[0].endCommandBuffer()

            val submitInfo = VkSubmitInfo.allocate {
                commandBuffers(VkCommandBuffer.arrayOf(cmdBuffers[0]))
            }

            queue.queueSubmit(
                1u,
                submitInfo.ptr(),
                inFlightFence
            )

            device.waitForFences(1u, fences.ptr(), VK_TRUE, ULong.MAX_VALUE)
            device.deviceWaitIdle()

            imageBufferList.forEach {
                device.destroyBuffer(it.buffer, null)
            }
            device.freeMemory(imageStagingBufferDeviceMemory, null)
        }
    }

    private val setupLabel = VkDebugUtilsLabelEXT.allocate(scope) {
        pLabelName = "Setup".c_str(scope)
    }

    private val replayLabel = VkDebugUtilsLabelEXT.allocate(scope) {
        pLabelName = "Replay".c_str(scope)
    }

    context(_: MemoryStack)
    fun execute(queue: VkQueue, swapchainImage: VkImage) = MemoryStack {
        device.resetCommandPool(commandPool, VkCommandPoolResetFlags.NONE)

        val beginInfo = VkCommandBufferBeginInfo.allocate {
            flags = VkCommandBufferUsageFlags.ONE_TIME_SUBMIT
        }

        MemoryStack {
            cmdBuffers[0].beginCommandBuffer(beginInfo.ptr())
            cmdBuffers[0].cmdBeginDebugUtilsLabelEXT(setupLabel.ptr())

            val dependencyInfo0 = VkDependencyInfo.allocate {
                val imageMemoryBarrier = VkImageMemoryBarrier2.allocate(1L)
                imageMemoryBarrier[0].apply {
                    srcStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                    srcAccessMask = VkAccessFlags2.NONE
                    dstStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                    dstAccessMask = VkAccessFlags2.NONE
                    oldLayout = VkImageLayout.UNDEFINED
                    newLayout = VkImageLayout.PRESENT_SRC_KHR

                    ofWholeImage(swapchainImage, VkImageAspectFlags.COLOR)

                }
                imageMemoryBarriers(imageMemoryBarrier)
            }
            cmdBuffers[0].cmdPipelineBarrier2(dependencyInfo0.ptr())

            cmdBuffers[0].cmdPipelineBarrier2(dependencyInfo1.ptr())

            resource.bufferList.forEachIndexed { i, buffer ->
                MemoryStack {
                    val bufferMetadata = captureData.metadata.buffers[i]
                    val bufferSize = bufferMetadata.size.toULong()
                    val roundDown = roundDown(bufferMetadata.size, 64L).toULong()
                    val remaining = bufferSize - roundDown
                    val regionCount: UInt
                    val regions: NArray<VkBufferCopy>
                    if (bufferSize >= 64UL && remaining > 0UL) {
                        regionCount = 2u
                        regions = VkBufferCopy.allocate(2L)
                        regions[0].apply {
                            srcOffset = 0uL
                            dstOffset = 0uL
                            size = roundDown
                        }
                        regions[1].apply {
                            srcOffset = roundDown
                            dstOffset = roundDown
                            size = remaining
                        }
                    } else {
                        regionCount = 1u
                        regions = VkBufferCopy.allocate(1L)
                        regions[0].apply {
                            srcOffset = 0uL
                            dstOffset = 0uL
                            size = bufferSize
                        }
                    }

                    cmdBuffers[0].cmdCopyBuffer(
                        buffer.cpu,
                        buffer.gpu,
                        regionCount,
                        regions.ptr()
                    )
                }
            }

            resource.imageList.forEachIndexed { imageIndex, images ->
                val imageMetadata = captureData.metadata.images[imageIndex]
                val copyRegions = VkImageCopy.allocate(imageMetadata.levelDataSizes.size.toLong())
                for (mip in imageMetadata.levelDataSizes.indices) {
                    copyRegions[mip.toLong()].apply {
                        srcSubresource {
                            aspectMask = VkImageAspectFlags.COLOR
                            mipLevel = mip.toUInt()
                            baseArrayLayer = 0u
                            layerCount = imageMetadata.arrayLayers.toUInt()
                        }
                        srcOffset {
                            x = 0
                            y = 0
                            z = 0
                        }

                        dstSubresource {
                            aspectMask = VkImageAspectFlags.COLOR
                            mipLevel = mip.toUInt()
                            baseArrayLayer = 0u
                            layerCount = imageMetadata.arrayLayers.toUInt()
                        }

                        dstOffset {
                            x = 0
                            y = 0
                            z = 0
                        }

                        extent {
                            width = maxOf(1, imageMetadata.width shr mip).toUInt()
                            height = maxOf(1, imageMetadata.height shr mip).toUInt()
                            depth = maxOf(1, imageMetadata.depth shr mip).toUInt()
                        }
                    }
                }

                cmdBuffers[0].cmdCopyImage(
                    images.cpu,
                    VkImageLayout.TRANSFER_SRC_OPTIMAL,
                    images.gpu,
                    VkImageLayout.TRANSFER_DST_OPTIMAL,
                    imageMetadata.levelDataSizes.size.toUInt(),
                    copyRegions.ptr()
                )
            }

            cmdBuffers[0].cmdPipelineBarrier2(dependencyInfo2.ptr())
            cmdBuffers[0].cmdEndDebugUtilsLabelEXT()
            cmdBuffers[0].endCommandBuffer()

            val submitInfo1 = VkSubmitInfo.allocate {
                waitSemaphores(
                    pImageAvailableSemaphore,
                    VkPipelineStageFlags.arrayOf(VkPipelineStageFlags.ALL_COMMANDS)
                )
                commandBuffers(VkCommandBuffer.arrayOf(cmdBuffers[0]))
                signalSemaphores(pCopyFinishedSemaphore)
            }
            queue.queueSubmit(
                1u,
                submitInfo1.ptr(),
                inFlightFence
            )
        }


        device.waitForFences(1u, fences.ptr(), VK_TRUE, ULong.MAX_VALUE)
        device.resetFences(1u, fences.ptr())
        device.deviceWaitIdle()

        Thread.sleep(0)
        Thread.yield()

        MemoryStack {
            cmdBuffers[1].beginCommandBuffer(beginInfo.ptr())
            cmdBuffers[1].cmdBeginDebugUtilsLabelEXT(replayLabel.ptr())
            cmdBuffers[1].cmdBindPipeline(VkPipelineBindPoint.COMPUTE, pipelineInfo.pipeline)

            val pDescriptorSets =
                VkDescriptorSet.arrayOf(*pipelineInfo.descriptorInfo.descriptorSets.toTypedArray())
            cmdBuffers[1].cmdBindDescriptorSets(
                VkPipelineBindPoint.COMPUTE,
                pipelineInfo.pipelineLayout,
                0u,
                pipelineInfo.descriptorInfo.descriptorSets.size.toUInt(),
                pDescriptorSets.ptr(),
                0u,
                nullptr()
            )

            // Replay commands
            when (val command = captureData.metadata.command) {
                is Command.DispatchIndirectCommand -> {
                    cmdBuffers[1].cmdDispatchIndirect(
                        resource.bufferList[command.bufferIndex].gpu,
                        command.offset.toULong()
                    )
                }

                is Command.DispatchCommand -> {
                    cmdBuffers[1].cmdDispatch(command.x.toUInt(), command.y.toUInt(), command.z.toUInt())
                }
            }
            cmdBuffers[1].cmdEndDebugUtilsLabelEXT()
            cmdBuffers[1].endCommandBuffer()

            val submitInfo2 = VkSubmitInfo.allocate {
                waitSemaphores(
                    pCopyFinishedSemaphore,
                    VkPipelineStageFlags.arrayOf(VkPipelineStageFlags.ALL_COMMANDS)
                )
                commandBuffers(VkCommandBuffer.arrayOf(cmdBuffers[1]))
                signalSemaphores(pRenderFinishedSemaphore)
            }
            queue.queueSubmit(
                1u,
                submitInfo2.ptr(),
                inFlightFence
            )
        }
    }

    fun destroy() {
        device.destroyDescriptorPool(descriptorPool, null)
        device.destroyFence(inFlightFence, null)

        device.destroySemaphore(imageAvailableSemaphore, null)
        device.destroySemaphore(renderFinishedSemaphore, null)

        device.destroyCommandPool(commandPool, null)

        pipelineInfo.destroy(device)
        resource.destroy()
        arena.close()
    }


    private data class DescriptorInfo(
        val samplers: List<VkSampler>,
        val descriptorSetLayouts: List<VkDescriptorSetLayout>,
        val descriptorSets: List<VkDescriptorSet>
    ) {
        fun destroy(device: VkDevice) {
            for (sampler in samplers) {
                device.destroySampler(sampler, null)
            }
            for (layout in descriptorSetLayouts) {
                device.destroyDescriptorSetLayout(layout, null)
            }
        }
    }

    private data class ComputePipelineInfo(
        val shaderModule: VkShaderModule,
        val descriptorInfo: DescriptorInfo,
        val pipelineLayout: VkPipelineLayout,
        val pipeline: VkPipeline
    ) {
        fun destroy(device: VkDevice) {
            device.destroyPipeline(pipeline, null)
            device.destroyPipelineLayout(pipelineLayout, null)
            device.destroyShaderModule(shaderModule, null)
            descriptorInfo.destroy(device)
        }
    }


    context(_: MemoryStack)
    @OptIn(UnsafeAPI::class)
    private fun makeDescriptors(): DescriptorInfo = MemoryStack {
        val samplers = captureData.metadata.samplerBindings.map {
            val samplerInfo = it.samplerInfo
            val samplerCreateInfo = VkSamplerCreateInfo.allocate {
                magFilter = VkFilter.fromNativeData(samplerInfo.minFilter.value)
                minFilter = VkFilter.fromNativeData(samplerInfo.magFilter.value)
                mipmapMode = VkSamplerMipmapMode.fromNativeData(samplerInfo.mipmapMode.value)
                addressModeU = VkSamplerAddressMode.fromNativeData(samplerInfo.addressModeU.value)
                addressModeV = VkSamplerAddressMode.fromNativeData(samplerInfo.addressModeV.value)
                addressModeW = VkSamplerAddressMode.fromNativeData(samplerInfo.addressModeW.value)
                mipLodBias = samplerInfo.mipLodBias
                anisotropyEnable = if (samplerInfo.anisotropyEnable) VK_TRUE else VK_FALSE
                maxAnisotropy = samplerInfo.maxAnisotropy
                compareEnable = if (samplerInfo.compareEnable) VK_TRUE else VK_FALSE
                compareOp = VkCompareOp.fromNativeData(samplerInfo.compareOp.value)
                minLod = samplerInfo.minLod
                maxLod = samplerInfo.maxLod
                // TODO: border color
                borderColor = VkBorderColor.INT_OPAQUE_BLACK
                unnormalizedCoordinates = if (samplerInfo.unnormalizedCoordinates) VK_TRUE else VK_FALSE
            }
            device.createSampler(samplerCreateInfo.ptr(), null).getOrThrow()
        }

        val descriptorSet0Layout = MemoryStack {
            val set0BindingCount =
                captureData.metadata.imageBindings.size + captureData.metadata.samplerBindings.size
            val layoutBindings = VkDescriptorSetLayoutBinding.allocate(set0BindingCount.toLong())

            captureData.metadata.imageBindings.forEachIndexed { i, imageBinding ->
                layoutBindings[i.toLong()].apply {
                    binding = imageBinding.binding.toUInt()
                    descriptorType = VkDescriptorType.STORAGE_IMAGE
                    descriptorCount = 1u
                    stageFlags = VkShaderStageFlags.ALL
                }
            }

            captureData.metadata.samplerBindings.forEachIndexed { i, samplerBinding ->
                val acutalIndex = captureData.metadata.imageBindings.size + i
                layoutBindings[acutalIndex.toLong()].apply {
                    binding = samplerBinding.binding.toUInt()
                    descriptorType = VkDescriptorType.COMBINED_IMAGE_SAMPLER
                    descriptorCount = 1u
                    stageFlags = VkShaderStageFlags.ALL
                    pImmutableSamplers = VkSampler.valueOf(samplers[i]).ptr()
                }
            }

            val createInfo = VkDescriptorSetLayoutCreateInfo.allocate {
                bindings(layoutBindings)
            }

            device.createDescriptorSetLayout(createInfo.ptr(), null).getOrThrow()
        }

        val descriptorSet1Layout = MemoryStack {
            val layoutBindings =
                VkDescriptorSetLayoutBinding.allocate(captureData.metadata.storageBufferBindings.size.toLong())
            captureData.metadata.storageBufferBindings.forEachIndexed { i, bufferBinding ->
                layoutBindings[i.toLong()].apply {
                    binding = bufferBinding.binding.toUInt()
                    descriptorType = VkDescriptorType.STORAGE_BUFFER
                    descriptorCount = 1u
                    stageFlags = VkShaderStageFlags.ALL
                }
            }

            val createInfo = VkDescriptorSetLayoutCreateInfo.allocate {
                bindings(layoutBindings)
            }

            device.createDescriptorSetLayout(createInfo.ptr(), null).getOrThrow()
        }

        val descriptorSet2Layout = MemoryStack {
            val layoutBindings =
                VkDescriptorSetLayoutBinding.allocate(captureData.metadata.uniformBufferBindings.size.toLong())
            captureData.metadata.uniformBufferBindings.forEachIndexed { i, bufferBinding ->
                layoutBindings[i.toLong()].apply {
                    binding = bufferBinding.binding.toUInt()
                    descriptorType = VkDescriptorType.UNIFORM_BUFFER
                    descriptorCount = 1u
                    stageFlags = VkShaderStageFlags.ALL
                }
            }

            val createInfo = VkDescriptorSetLayoutCreateInfo.allocate {
                bindings(layoutBindings)
            }

            device.createDescriptorSetLayout(createInfo.ptr(), null).getOrThrow()
        }

        val descriptorSetLayouts = arrayOf(
            descriptorSet0Layout,
            descriptorSet1Layout,
            descriptorSet2Layout
        )

        val descriptorSets = MemoryStack {
            val allocateInfo = VkDescriptorSetAllocateInfo.allocate {
                descriptorPool = this@ReplayInstance.descriptorPool
                descriptorSets(VkDescriptorSetLayout.arrayOf(*descriptorSetLayouts))
            }
            val returns = VkDescriptorSet.malloc(descriptorSetLayouts.size.toLong())
            device.allocateDescriptorSets(allocateInfo.ptr(), returns.ptr()).getOrThrow()
            List(descriptorSetLayouts.size) { VkDescriptorSet.fromNativeData(descriptorPool, returns[it.toLong()]) }
        }

        MemoryStack {
            val set0BindingCount =
                captureData.metadata.imageBindings.size + captureData.metadata.samplerBindings.size
            val writeDescs = VkWriteDescriptorSet.allocate(set0BindingCount.toLong())
            var writeIndex = 0L
            captureData.metadata.imageBindings.forEachIndexed { i, imageBinding ->
                val descriptorImageInfo = VkDescriptorImageInfo.allocate {
                    imageView = resource.storageImageViewList[i]
                    imageLayout = VkImageLayout.GENERAL
                }
                writeDescs[writeIndex++].apply {
                    dstSet = descriptorSets[0]
                    dstBinding = imageBinding.binding.toUInt()
                    dstArrayElement = 0u
                    descriptorType = VkDescriptorType.STORAGE_IMAGE
                    descriptorCount = 1u
                    pImageInfo = descriptorImageInfo.ptr()
                }
            }
            captureData.metadata.samplerBindings.forEachIndexed { i, samplerBinding ->
                val descriptorImageInfo = VkDescriptorImageInfo.allocate {
                    sampler = samplers[i]
                    imageView = resource.samplerImageViewList[i]
                    imageLayout = VkImageLayout.GENERAL
                }
                writeDescs[writeIndex++].apply {
                    dstSet = descriptorSets[0]
                    dstBinding = samplerBinding.binding.toUInt()
                    dstArrayElement = 0u
                    descriptorType = VkDescriptorType.COMBINED_IMAGE_SAMPLER
                    descriptorCount = 1u
                    pImageInfo = descriptorImageInfo.ptr()
                }
            }
            check(writeIndex == set0BindingCount.toLong())
            device.updateDescriptorSets(writeIndex.toUInt(), writeDescs.ptr(), 0u, nullptr())
        }

        MemoryStack {
            val writeCount =
                captureData.metadata.storageBufferBindings.size + captureData.metadata.uniformBufferBindings.size
            val writeDescs = VkWriteDescriptorSet.allocate(writeCount.toLong())
            captureData.metadata.storageBufferBindings.forEachIndexed { i, bufferBinding ->
                val descriptorBufferInfo = VkDescriptorBufferInfo.allocate {
                    buffer = resource.bufferList[bufferBinding.bufferIndex].gpu
                    val offsetV = bufferBinding.offset.toULong()
                    offset = offsetV
                    range = captureData.metadata.buffers[bufferBinding.bufferIndex].size.toULong() - offsetV
                }
                writeDescs[i.toLong()].apply {
                    dstSet = descriptorSets[1]
                    dstBinding = bufferBinding.binding.toUInt()
                    dstArrayElement = 0u
                    descriptorType = VkDescriptorType.STORAGE_BUFFER
                    descriptorCount = 1u
                    pBufferInfo = descriptorBufferInfo.ptr()
                }
            }
            captureData.metadata.uniformBufferBindings.forEachIndexed { i, bufferBinding ->
                val descriptorBufferInfo = VkDescriptorBufferInfo.allocate {
                    buffer = resource.bufferList[bufferBinding.bufferIndex].gpu
                    val offsetV = bufferBinding.offset.toULong()
                    offset = offsetV
                    range = captureData.metadata.buffers[bufferBinding.bufferIndex].size.toULong() - offsetV
                }
                writeDescs[captureData.metadata.storageBufferBindings.size + i.toLong()].apply {
                    dstSet = descriptorSets[2]
                    dstBinding = bufferBinding.binding.toUInt()
                    dstArrayElement = 0u
                    descriptorType = VkDescriptorType.UNIFORM_BUFFER
                    descriptorCount = 1u
                    pBufferInfo = descriptorBufferInfo.ptr()
                }
            }
            device.updateDescriptorSets(writeCount.toUInt(), writeDescs.ptr(), 0u, nullptr())
        }

        DescriptorInfo(
            samplers,
            descriptorSetLayouts.toList(),
            descriptorSets
        )
    }

    context(_: MemoryStack)
    @OptIn(UnsafeAPI::class)
    private fun makeComputePipeline(): ComputePipelineInfo = MemoryStack {
        val shaderModule = captureDir.resolve("shader.comp.spv").useMapped { spvData ->
            val createInfo = VkShaderModuleCreateInfo.allocate {
                codeSize = spvData.count
                @OptIn(UnsafeAPI::class)
                pCode = reinterpret_cast(spvData.ptr())
            }

            device.createShaderModule(createInfo.ptr(), null).getOrThrow()
        }

        val descriptors = makeDescriptors()
        val pipelineLayout = MemoryStack {
            val createInfo = VkPipelineLayoutCreateInfo.allocate {
                setLayouts(VkDescriptorSetLayout.arrayOf(*descriptors.descriptorSetLayouts.toTypedArray()))
                pushConstantRangeCount = 0u
                pPushConstantRanges = nullptr()
            }
            device.createPipelineLayout(createInfo.ptr(), null).getOrThrow()
        }

        val pipeline = MemoryStack {
            val createInfo = VkComputePipelineCreateInfo.allocate {
                val stageV = VkPipelineShaderStageCreateInfo.allocate {
                    stage = VkShaderStageFlags.COMPUTE
                    module = shaderModule
                    pName = "main".c_str()
                }
                stage = stageV.ptr()
                layout = pipelineLayout
            }

            device.createComputePipelines(
                VkPipelineCache.fromNativeData(device, 0L),
                1u,
                createInfo.ptr(),
                null
            ).getOrThrow()
        }

        ComputePipelineInfo(
            shaderModule,
            descriptors,
            pipelineLayout,
            pipeline
        )
    }
}