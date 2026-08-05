package dev.luna5ama.vibris.replay

import dev.luna5ama.vibris.common.CaptureData
import dev.luna5ama.vibris.common.Command
import dev.luna5ama.vibris.common.FramebufferAttachment
import dev.luna5ama.vibris.common.GraphicsPassInfo
import dev.luna5ama.vibris.common.PassInfo
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.echonolix.caelum.*
import net.echonolix.caelum.vulkan.*
import net.echonolix.caelum.vulkan.enums.*
import net.echonolix.caelum.vulkan.flags.*
import net.echonolix.caelum.vulkan.handles.*
import net.echonolix.caelum.vulkan.structs.*
import java.lang.foreign.Arena
import java.nio.file.Path

class VKReplayInstance(
    private val captureData: CaptureData,
    private val device: VkDevice,
    private val captureDir: Path,
    private val graphicsQueueFamilyIndex: UInt,
    shaderOverridePath: Path? = null,
    shaderPasses: Set<String> = emptySet()
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

    val resource: VKReplayResource
    private val replayCommands = captureData.metadata.commandsForReplay()
    private val replayPassCommands = replayCommands.filterIsInstance<Command.PassCommand>()
    private val replayGraphicsCommands = replayCommands.filterIsInstance<Command.GraphicsCommand>()
    private val shaderCompiler = VKReplayShaderCompiler(captureData, captureDir, shaderOverridePath, shaderPasses)
    private val pipelineInfos: List<ComputePipelineInfo>
    private val graphicsPipelineInfos: List<GraphicsPipelineInfo>

    init {
        MemoryStack {
            MemoryStack {
                val resourceCommandCount = replayPassCommands.size + replayGraphicsCommands.size
                val extra = maxOf(4u, resourceCommandCount.toUInt())
                val createInfo = VkDescriptorPoolCreateInfo.allocate {
                    maxSets = maxOf(4u, resourceCommandCount.toUInt() * 3u)
                    val poolSizes = VkDescriptorPoolSize.allocate(4L)
                    poolSizes[0L].apply {
                        type = VkDescriptorType.UNIFORM_BUFFER
                        descriptorCount = maxOf(16u, captureData.metadata.allUniformBufferBindings().size.toUInt() * extra)
                    }
                    poolSizes[1L].apply {
                        type = VkDescriptorType.STORAGE_BUFFER
                        descriptorCount = maxOf(16u, captureData.metadata.allStorageBufferBindings().size.toUInt() * extra)
                    }
                    poolSizes[2L].apply {
                        type = VkDescriptorType.STORAGE_IMAGE
                        descriptorCount = maxOf(16u, captureData.metadata.allImageBindings().size.toUInt() * extra)
                    }
                    poolSizes[3L].apply {
                        type = VkDescriptorType.COMBINED_IMAGE_SAMPLER
                        descriptorCount = maxOf(16u, captureData.metadata.allSamplerBindings().size.toUInt() * extra)
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
                    commandPool = this@VKReplayInstance.commandPool
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

            resource = VKReplayResource(captureData, device, graphicsQueueFamilyIndex, memoryTypes)

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
                pipelineInfos = replayPassCommands.map { makeComputePipeline(it) }
                graphicsPipelineInfos = replayGraphicsCommands.map { makeGraphicsPipeline(it) }
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

        val imageStagingBufferDeviceMemory = if (imageBufferList.isNotEmpty()) {
            val imageStagingBufferDeviceMemory =resource.allocateDeviceMemory(
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
        } else {
            null
        }

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

            if (imageStagingBufferDeviceMemory != null) {
                device.freeMemory(imageStagingBufferDeviceMemory, null)
            }
        }
    }

    private val copyLabel = VkDebugUtilsLabelEXT.allocate(scope) {
        pLabelName = "Copy".c_str(scope)
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
            cmdBuffers[0].cmdBeginDebugUtilsLabelEXT(copyLabel.ptr())

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
                            aspectMask = imageMetadata.dataType.toAspectFlags()
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
                            aspectMask = imageMetadata.dataType.toAspectFlags()
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

            var pipelineIndex = 0
            var graphicsPipelineIndex = 0
            replayCommands.forEach { command ->
                when (command) {
                    is Command.PushDebugLabelCommand -> {
                        val debugLabel = VkDebugUtilsLabelEXT.allocate {
                            pLabelName = command.label.c_str()
                        }
                        cmdBuffers[1].cmdBeginDebugUtilsLabelEXT(debugLabel.ptr())
                    }

                    Command.PopDebugLabelCommand -> {
                        cmdBuffers[1].cmdEndDebugUtilsLabelEXT()
                    }

                    is Command.PassCommand -> {
                        val pipelineInfo = pipelineInfos[pipelineIndex++]
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

                        when (command) {
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

                        MemoryStack {
                            val memoryBarrier = VkMemoryBarrier2.allocate(1L)
                            memoryBarrier[0].apply {
                                srcStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                                srcAccessMask = VkAccessFlags2.MEMORY_WRITE
                                dstStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                                dstAccessMask = VkAccessFlags2.MEMORY_READ + VkAccessFlags2.MEMORY_WRITE
                            }
                            val dependencyInfo = VkDependencyInfo.allocate {
                                memoryBarriers(memoryBarrier)
                            }
                            cmdBuffers[1].cmdPipelineBarrier2(dependencyInfo.ptr())
                        }
                    }

                    is Command.GraphicsCommand -> {
                        executeGraphics(command, graphicsPipelineInfos[graphicsPipelineIndex++])
                        MemoryStack {
                            val memoryBarrier = VkMemoryBarrier2.allocate(1L)
                            memoryBarrier[0].apply {
                                srcStageMask = VkPipelineStageFlags2.ALL_GRAPHICS
                                srcAccessMask = VkAccessFlags2.MEMORY_WRITE
                                dstStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                                dstAccessMask = VkAccessFlags2.MEMORY_READ + VkAccessFlags2.MEMORY_WRITE
                            }
                            val dependencyInfo = VkDependencyInfo.allocate {
                                memoryBarriers(memoryBarrier)
                            }
                            cmdBuffers[1].cmdPipelineBarrier2(dependencyInfo.ptr())
                        }
                    }
                }
            }
            cmdBuffers[1].cmdEndDebugUtilsLabelEXT()
            if (replayPassCommands.isNotEmpty()) {
                cmdBuffers[1].cmdDispatch(0u, 0u, 0u)
            }
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
        device.destroySemaphore(copyFinishedSemaphore, null)
        device.destroySemaphore(renderFinishedSemaphore, null)

        device.destroyCommandPool(commandPool, null)

        pipelineInfos.forEach { it.destroy(device) }
        graphicsPipelineInfos.forEach { it.destroy(device) }
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

    private data class GraphicsPipelineInfo(
        val shaderModules: List<VkShaderModule>,
        val descriptorInfo: DescriptorInfo,
        val pipelineLayout: VkPipelineLayout,
        val pipeline: VkPipeline,
        val vertexBufferIndices: List<Int>,
    ) {
        fun destroy(device: VkDevice) {
            device.destroyPipeline(pipeline, null)
            device.destroyPipelineLayout(pipelineLayout, null)
            shaderModules.forEach { device.destroyShaderModule(it, null) }
            descriptorInfo.destroy(device)
        }
    }

    private data class VertexBindingKey(val bufferIndex: Int, val stride: Int, val divisor: Int)


    context(_: MemoryStack)
    @OptIn(UnsafeAPI::class)
    private fun makeDescriptors(passInfo: PassInfo): DescriptorInfo = MemoryStack {
        val samplerBindings = passInfo.samplerBindings
        val imageBindings = passInfo.imageBindings
        val storageBufferBindings = passInfo.storageBufferBindings
        val uniformBufferBindings = passInfo.uniformBufferBindings

        val samplers = samplerBindings.map {
            val samplerInfo = it.samplerInfo
            val imageFormatName = captureData.metadata.images[it.imageIndex].format.name
            val integerFormat = imageFormatName.contains("_UINT") || imageFormatName.contains("_SINT")
            val samplerCreateInfo = VkSamplerCreateInfo.allocate {
                magFilter = if (integerFormat) VkFilter.NEAREST
                else VkFilter.fromNativeData(samplerInfo.magFilter.value)
                minFilter = if (integerFormat) VkFilter.NEAREST
                else VkFilter.fromNativeData(samplerInfo.minFilter.value)
                mipmapMode = if (integerFormat) VkSamplerMipmapMode.NEAREST
                else VkSamplerMipmapMode.fromNativeData(samplerInfo.mipmapMode.value)
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
                imageBindings.size + samplerBindings.size
            val layoutBindings = VkDescriptorSetLayoutBinding.allocate(set0BindingCount.toLong())

            imageBindings.forEachIndexed { i, imageBinding ->
                layoutBindings[i.toLong()].apply {
                    binding = imageBinding.binding.toUInt()
                    descriptorType = VkDescriptorType.STORAGE_IMAGE
                    descriptorCount = 1u
                    stageFlags = VkShaderStageFlags.ALL
                }
            }

            samplerBindings.forEachIndexed { i, samplerBinding ->
                val acutalIndex = imageBindings.size + i
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
                VkDescriptorSetLayoutBinding.allocate(storageBufferBindings.size.toLong())
            storageBufferBindings.forEachIndexed { i, bufferBinding ->
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
                VkDescriptorSetLayoutBinding.allocate(uniformBufferBindings.size.toLong())
            uniformBufferBindings.forEachIndexed { i, bufferBinding ->
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
                descriptorPool = this@VKReplayInstance.descriptorPool
                descriptorSets(VkDescriptorSetLayout.arrayOf(*descriptorSetLayouts))
            }
            val returns = VkDescriptorSet.malloc(descriptorSetLayouts.size.toLong())
            device.allocateDescriptorSets(allocateInfo.ptr(), returns.ptr()).getOrThrow()
            List(descriptorSetLayouts.size) { VkDescriptorSet.fromNativeData(descriptorPool, returns[it.toLong()]) }
        }

        MemoryStack {
            val set0BindingCount =
                imageBindings.size + samplerBindings.size
            val writeDescs = VkWriteDescriptorSet.allocate(set0BindingCount.toLong())
            var writeIndex = 0L
            imageBindings.forEach { imageBinding ->
                val descriptorImageInfo = VkDescriptorImageInfo.allocate {
                    imageView = resource.storageImageView(imageBinding)
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
            samplerBindings.forEachIndexed { i, samplerBinding ->
                val descriptorImageInfo = VkDescriptorImageInfo.allocate {
                    sampler = samplers[i]
                    imageView = resource.samplerImageView(samplerBinding)
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
                storageBufferBindings.size + uniformBufferBindings.size
            val writeDescs = VkWriteDescriptorSet.allocate(writeCount.toLong())
            storageBufferBindings.forEachIndexed { i, bufferBinding ->
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
            uniformBufferBindings.forEachIndexed { i, bufferBinding ->
                val descriptorBufferInfo = VkDescriptorBufferInfo.allocate {
                    buffer = resource.bufferList[bufferBinding.bufferIndex].gpu
                    val offsetV = bufferBinding.offset.toULong()
                    offset = offsetV
                    range = captureData.metadata.buffers[bufferBinding.bufferIndex].size.toULong() - offsetV
                }
                writeDescs[storageBufferBindings.size + i.toLong()].apply {
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
    private fun makeComputePipeline(command: Command.PassCommand): ComputePipelineInfo = MemoryStack {
        val shaderPath = shaderCompiler.shaderPath(command)
        val shaderModule = shaderPath.useMapped { spvData ->
            val createInfo = VkShaderModuleCreateInfo.allocate {
                codeSize = spvData.count
                @OptIn(UnsafeAPI::class)
                pCode = reinterpret_cast(spvData.ptr())
            }

            device.createShaderModule(createInfo.ptr(), null).getOrThrow()
        }

        val descriptors = makeDescriptors(command.passInfo)
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

    context(_: MemoryStack)
    @OptIn(UnsafeAPI::class)
    private fun makeGraphicsPipeline(command: Command.GraphicsCommand): GraphicsPipelineInfo = MemoryStack {
        val info = command.graphicsInfo
        val compiledProgram = shaderCompiler.graphicsProgram(command)
        val compiledShaders = compiledProgram.shaders
        require(compiledShaders.none { it.stage == "tesc" || it.stage == "tese" }) {
            "Tessellated graphics captures require patch vertex state, which is not captured yet"
        }
        val shaderModules = compiledShaders.map { compiled ->
            compiled.path.useMapped { spvData ->
                val createInfo = VkShaderModuleCreateInfo.allocate {
                    codeSize = spvData.count
                    pCode = reinterpret_cast(spvData.ptr())
                }
                device.createShaderModule(createInfo.ptr(), null).getOrThrow()
            }
        }
        val shaderStages = VkPipelineShaderStageCreateInfo.allocate(compiledShaders.size.toLong())
        compiledShaders.forEachIndexed { index, compiled ->
            shaderStages[index.toLong()].apply {
                stage = shaderStage(compiled.stage)
                module = shaderModules[index]
                pName = "main".c_str()
            }
        }

        val bindingKeys = info.vertexAttributes
            .map { VertexBindingKey(it.bufferIndex, it.stride, it.divisor) }
            .distinct()
        val vertexBindings = VkVertexInputBindingDescription.allocate(bindingKeys.size.toLong())
        bindingKeys.forEachIndexed { index, key ->
            require(key.divisor in 0..1) { "Vertex divisor ${key.divisor} requires VK_EXT_vertex_attribute_divisor" }
            vertexBindings[index.toLong()].apply {
                binding = index.toUInt()
                stride = key.stride.toUInt()
                inputRate = if (key.divisor == 0) VkVertexInputRate.VERTEX else VkVertexInputRate.INSTANCE
            }
        }
        val vertexAttributes = VkVertexInputAttributeDescription.allocate(info.vertexAttributes.size.toLong())
        info.vertexAttributes.forEachIndexed { index, attribute ->
            vertexAttributes[index.toLong()].apply {
                location = attribute.location.toUInt()
                binding = bindingKeys.indexOf(VertexBindingKey(attribute.bufferIndex, attribute.stride, attribute.divisor)).toUInt()
                format = vertexFormat(attribute)
                require(attribute.offset in 0..UInt.MAX_VALUE.toLong()) { "Vertex attribute offset is too large" }
                offset = attribute.offset.toUInt()
            }
        }
        val vertexInput = VkPipelineVertexInputStateCreateInfo.allocate {
            vertexBindingDescriptions(vertexBindings)
            vertexAttributeDescriptions(vertexAttributes)
        }
        val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.allocate {
            topology = primitiveTopology(graphicsMode(command))
            primitiveRestartEnable = VK_FALSE
        }

        val viewportValues = info.state.viewport
        require(viewportValues.size == 4) { "Captured viewport must have four values" }
        val viewports = VkViewport.allocate(1L)
        viewports[0].apply {
            x = viewportValues[0].toFloat()
            y = viewportValues[1].toFloat()
            width = viewportValues[2].toFloat()
            height = viewportValues[3].toFloat()
            minDepth = 0f
            maxDepth = 1f
        }
        val attachmentExtent = attachmentExtent(info)
        val scissors = VkRect2D.allocate(1L)
        scissors[0].apply {
            val captured = info.state.scissor
            val scissor = if (info.state.scissorEnabled && captured.size == 4) captured
            else listOf(0, 0, attachmentExtent.first, attachmentExtent.second)
            offset {
                x = scissor[0]
                y = scissor[1]
            }
            extent {
                width = scissor[2].toUInt()
                height = scissor[3].toUInt()
            }
        }
        val viewportState = VkPipelineViewportStateCreateInfo.allocate {
            viewports(viewports)
            scissors(scissors)
        }
        val rasterization = VkPipelineRasterizationStateCreateInfo.allocate {
            depthClampEnable = VK_FALSE
            rasterizerDiscardEnable = VK_FALSE
            polygonMode = polygonMode(info.state.polygonMode)
            cullMode = when {
                !info.state.cullEnabled -> VkCullModeFlags.NONE
                info.state.cullFace == 0x0404 -> VkCullModeFlags.FRONT
                info.state.cullFace == 0x0405 -> VkCullModeFlags.BACK
                info.state.cullFace == 0x0408 -> VkCullModeFlags.FRONT_AND_BACK
                else -> throw UnsupportedOperationException(
                    "Unsupported captured GL cull face 0x${info.state.cullFace.toString(16)}",
                )
            }
            frontFace = frontFace(info.state.frontFace)
            depthBiasEnable = if (info.state.polygonOffsetEnabled) VK_TRUE else VK_FALSE
            depthBiasConstantFactor = info.state.polygonOffsetUnits
            depthBiasClamp = 0f
            depthBiasSlopeFactor = info.state.polygonOffsetFactor
            lineWidth = info.state.lineWidth
        }
        val multisample = VkPipelineMultisampleStateCreateInfo.allocate {
            rasterizationSamples = VkSampleCountFlags.`1_BIT`
        }
        val depthStencil = VkPipelineDepthStencilStateCreateInfo.allocate {
            depthTestEnable = if (info.state.depthTest) VK_TRUE else VK_FALSE
            depthWriteEnable = if (info.state.depthWrite) VK_TRUE else VK_FALSE
            depthCompareOp = compareOp(info.state.depthFunction)
            depthBoundsTestEnable = VK_FALSE
            stencilTestEnable = VK_FALSE
        }
        val blendAttachments = VkPipelineColorBlendAttachmentState.allocate(info.state.blends.size.toLong())
        info.state.blends.forEachIndexed { index, blend ->
            blendAttachments[index.toLong()].apply {
                blendEnable = if (blend.enabled) VK_TRUE else VK_FALSE
                srcColorBlendFactor = blendFactor(blend.sourceRgb)
                dstColorBlendFactor = blendFactor(blend.destinationRgb)
                colorBlendOp = blendOp(blend.equationRgb)
                srcAlphaBlendFactor = blendFactor(blend.sourceAlpha)
                dstAlphaBlendFactor = blendFactor(blend.destinationAlpha)
                alphaBlendOp = blendOp(blend.equationAlpha)
                colorWriteMask = colorMask(blend.colorMask)
            }
        }
        val colorBlend = VkPipelineColorBlendStateCreateInfo.allocate {
            logicOpEnable = VK_FALSE
            attachments(blendAttachments)
            blendConstants = NFloat.arrayOf(*info.state.blendColor.toTypedArray()).ptr()
        }

        val descriptors = makeDescriptors(compiledProgram.resources)
        val pipelineLayout = VkPipelineLayoutCreateInfo.allocate {
            setLayouts(VkDescriptorSetLayout.arrayOf(*descriptors.descriptorSetLayouts.toTypedArray()))
        }.let { device.createPipelineLayout(it.ptr(), null).getOrThrow() }

        val colorFormats = colorAttachments(info).map { attachment ->
            val metadata = captureData.metadata.images[attachment.imageIndex]
            VkFormat.fromNativeData(metadata.format.value)
        }
        val depthAttachment = depthAttachment(info)
        val stencilAttachment = stencilAttachment(info)
        val rendering = VkPipelineRenderingCreateInfo.allocate {
            colorAttachments(VkFormat.arrayOf(*colorFormats.toTypedArray()))
            depthAttachmentFormat = depthAttachment?.let {
                VkFormat.fromNativeData(captureData.metadata.images[it.imageIndex].format.value)
            } ?: VkFormat.UNDEFINED
            stencilAttachmentFormat = stencilAttachment?.let {
                VkFormat.fromNativeData(captureData.metadata.images[it.imageIndex].format.value)
            } ?: VkFormat.UNDEFINED
        }
        val pipelineCreateInfo = VkGraphicsPipelineCreateInfo.allocate {
            pNext = rendering.ptr()
            stages(shaderStages)
            pVertexInputState = vertexInput.ptr()
            pInputAssemblyState = inputAssembly.ptr()
            pViewportState = viewportState.ptr()
            pRasterizationState = rasterization.ptr()
            pMultisampleState = multisample.ptr()
            pDepthStencilState = depthStencil.ptr()
            pColorBlendState = colorBlend.ptr()
            layout = pipelineLayout
        }
        val pipeline = device.createGraphicsPipelines(
            VkPipelineCache.fromNativeData(device, 0L),
            1u,
            pipelineCreateInfo.ptr(),
            null,
        ).getOrThrow()

        GraphicsPipelineInfo(
            shaderModules,
            descriptors,
            pipelineLayout,
            pipeline,
            bindingKeys.map(VertexBindingKey::bufferIndex),
        )
    }

    context(_: MemoryStack)
    private fun executeGraphics(command: Command.GraphicsCommand, pipelineInfo: GraphicsPipelineInfo) = MemoryStack {
        val info = command.graphicsInfo
        val colorAttachments = colorAttachments(info)
        val colorInfos = VkRenderingAttachmentInfo.allocate(colorAttachments.size.toLong())
        colorAttachments.forEachIndexed { index, attachment ->
            colorInfos[index.toLong()].apply {
                imageView = resource.attachmentImageView(attachment)
                imageLayout = VkImageLayout.GENERAL
                loadOp = VkAttachmentLoadOp.LOAD
                storeOp = VkAttachmentStoreOp.STORE
            }
        }
        fun renderingAttachment(attachment: FramebufferAttachment?): NValue<VkRenderingAttachmentInfo>? =
            attachment?.let {
                VkRenderingAttachmentInfo.allocate {
                    imageView = resource.attachmentImageView(it)
                    imageLayout = VkImageLayout.GENERAL
                    loadOp = VkAttachmentLoadOp.LOAD
                    storeOp = VkAttachmentStoreOp.STORE
                }
            }
        val depthInfo = renderingAttachment(depthAttachment(info))
        val stencilInfo = renderingAttachment(stencilAttachment(info))
        val extent = attachmentExtent(info)
        val renderingInfo = VkRenderingInfo.allocate {
            renderArea {
                offset {
                    x = 0
                    y = 0
                }
                extent {
                    width = extent.first.toUInt()
                    height = extent.second.toUInt()
                }
            }
            layerCount = 1u
            colorAttachments(colorInfos)
            depthInfo?.let { pDepthAttachment = it.ptr() }
            stencilInfo?.let { pStencilAttachment = it.ptr() }
        }

        cmdBuffers[1].cmdBeginRendering(renderingInfo.ptr())
        cmdBuffers[1].cmdBindPipeline(VkPipelineBindPoint.GRAPHICS, pipelineInfo.pipeline)
        val descriptorSets = VkDescriptorSet.arrayOf(*pipelineInfo.descriptorInfo.descriptorSets.toTypedArray())
        cmdBuffers[1].cmdBindDescriptorSets(
            VkPipelineBindPoint.GRAPHICS,
            pipelineInfo.pipelineLayout,
            0u,
            pipelineInfo.descriptorInfo.descriptorSets.size.toUInt(),
            descriptorSets.ptr(),
            0u,
            nullptr(),
        )
        if (pipelineInfo.vertexBufferIndices.isNotEmpty()) {
            val buffers = VkBuffer.arrayOf(*pipelineInfo.vertexBufferIndices.map { resource.bufferList[it].gpu }.toTypedArray())
            val offsets = NUInt64.arrayOf(*LongArray(pipelineInfo.vertexBufferIndices.size) { 0L }.map(Long::toULong).toTypedArray())
            cmdBuffers[1].cmdBindVertexBuffers(0u, pipelineInfo.vertexBufferIndices.size.toUInt(), buffers.ptr(), offsets.ptr())
        }

        when (command) {
            is Command.DrawArraysCommand -> cmdBuffers[1].cmdDraw(
                command.count.toUInt(),
                command.instanceCount.toUInt(),
                command.first.toUInt(),
                0u,
            )
            is Command.DrawElementsCommand -> {
                val type = indexType(command.indexType)
                val elementSize = indexElementSize(command.indexType)
                require(command.indexOffset % elementSize == 0L) { "Index offset is not aligned to its element size" }
                cmdBuffers[1].cmdBindIndexBuffer(resource.bufferList[command.indexBufferIndex].gpu, 0uL, type)
                cmdBuffers[1].cmdDrawIndexed(
                    command.count.toUInt(),
                    command.instanceCount.toUInt(),
                    (command.indexOffset / elementSize).toUInt(),
                    command.baseVertex,
                    0u,
                )
            }
            is Command.MultiDrawElementsCommand -> {
                val type = indexType(command.indexType)
                val elementSize = indexElementSize(command.indexType)
                cmdBuffers[1].cmdBindIndexBuffer(resource.bufferList[command.indexBufferIndex].gpu, 0uL, type)
                command.counts.indices.forEach { index ->
                    val offset = command.indexOffsets[index]
                    require(offset % elementSize == 0L) { "Index offset is not aligned to its element size" }
                    cmdBuffers[1].cmdDrawIndexed(
                        command.counts[index].toUInt(),
                        1u,
                        (offset / elementSize).toUInt(),
                        command.baseVertices[index],
                        0u,
                    )
                }
            }
        }
        cmdBuffers[1].cmdEndRendering()
    }

    private fun colorAttachments(info: GraphicsPassInfo): List<FramebufferAttachment> = info.drawBuffers.map { drawBuffer ->
        info.framebufferAttachments.singleOrNull { it.attachment == drawBuffer }
            ?: error("Captured draw buffer 0x${drawBuffer.toString(16)} has no framebuffer attachment")
    }

    private fun depthAttachment(info: GraphicsPassInfo): FramebufferAttachment? =
        info.framebufferAttachments.firstOrNull { it.attachment == 0x8D00 || it.attachment == 0x821A }

    private fun stencilAttachment(info: GraphicsPassInfo): FramebufferAttachment? =
        info.framebufferAttachments.firstOrNull { it.attachment == 0x8D20 || it.attachment == 0x821A }

    private fun attachmentExtent(info: GraphicsPassInfo): Pair<Int, Int> {
        val attachments = info.framebufferAttachments
        require(attachments.isNotEmpty()) { "Captured graphics command has no framebuffer attachments" }
        val widths = attachments.map {
            maxOf(1, captureData.metadata.images[it.imageIndex].width shr it.level)
        }
        val heights = attachments.map {
            maxOf(1, captureData.metadata.images[it.imageIndex].height shr it.level)
        }
        return widths.min() to heights.min()
    }

    private fun shaderStage(stage: String): VkShaderStageFlags = when (stage) {
        "vertex" -> VkShaderStageFlags.VERTEX
        "tesc" -> VkShaderStageFlags.TESSELLATION_CONTROL
        "tese" -> VkShaderStageFlags.TESSELLATION_EVALUATION
        "geometry" -> VkShaderStageFlags.GEOMETRY
        "fragment" -> VkShaderStageFlags.FRAGMENT
        else -> error("Unsupported graphics shader stage $stage")
    }

    private fun colorMask(mask: Int): VkColorComponentFlags {
        var flags = VkColorComponentFlags.NONE
        if (mask and 1 != 0) flags += VkColorComponentFlags.R
        if (mask and 2 != 0) flags += VkColorComponentFlags.G
        if (mask and 4 != 0) flags += VkColorComponentFlags.B
        if (mask and 8 != 0) flags += VkColorComponentFlags.A
        return flags
    }

    private fun indexElementSize(type: Int): Long = when (type) {
        0x1401 -> 1L
        0x1403 -> 2L
        0x1405 -> 4L
        else -> throw UnsupportedOperationException("Unsupported captured GL index type 0x${type.toString(16)}")
    }

    private fun graphicsMode(command: Command.GraphicsCommand): Int = when (command) {
        is Command.DrawArraysCommand -> command.mode
        is Command.DrawElementsCommand -> command.mode
        is Command.MultiDrawElementsCommand -> command.mode
    }
}
