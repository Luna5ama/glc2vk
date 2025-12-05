package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.common.CaptureData
import dev.luna5ama.glc2vk.common.Command
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
    val pCommandBuffer: NArray<VkCommandBufferHandle>
    val cmdBuffer: VkCommandBuffer

    val renderFinishedSemaphore: VkSemaphore
    val imageAvailableSemaphore: VkSemaphore

    val pRenderFinishedSemaphore: NArray<VkSemaphoreHandle>
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
                flags = VkCommandPoolCreateFlags.RESET_COMMAND_BUFFER
                queueFamilyIndex = graphicsQueueFamilyIndex.toUInt()
            }
            commandPool = device.createCommandPool(commandPoolCreateInfo.ptr(), null).getOrThrow()

            memoryProperties = VkPhysicalDeviceMemoryProperties2.allocate(scope)
            device.physicalDevice.getPhysicalDeviceMemoryProperties2(memoryProperties.ptr())
            memoryTypes = MemoryTypeManager(memoryProperties.memoryProperties)


            MemoryStack {
                pCommandBuffer = VkCommandBuffer.malloc(scope, 1)
                val commandBufferAllocateInfo = VkCommandBufferAllocateInfo.allocate {
                    commandPool = this@ReplayInstance.commandPool
                    level = VkCommandBufferLevel.PRIMARY
                    commandBufferCount = 1u
                }
                device.allocateCommandBuffers(commandBufferAllocateInfo.ptr(), pCommandBuffer.ptr())
                cmdBuffer = VkCommandBuffer.fromNativeData(commandPool, pCommandBuffer[0])
            }


            MemoryStack {
                val semaphoreCreateInfo = VkSemaphoreCreateInfo.allocate {}
                renderFinishedSemaphore = device.createSemaphore(semaphoreCreateInfo.ptr(), null).getOrThrow()
                imageAvailableSemaphore = device.createSemaphore(semaphoreCreateInfo.ptr(), null).getOrThrow()


                pRenderFinishedSemaphore = VkSemaphore.arrayOf(scope, renderFinishedSemaphore)
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

                        ofWholeImage(image, captureData.metadata.images[i].dataType.toAspectFlags())
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

                        ofWholeImage(image, captureData.metadata.images[i].dataType.toAspectFlags())
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
    fun init(queue: VkQueue) {
        // Initial layout transitions
        MemoryStack {
            device.waitForFences(1u, fences.ptr(), VK_TRUE, ULong.MAX_VALUE)
            device.resetFences(1u, fences.ptr())

            val dependencyInfo = VkDependencyInfo.allocate {
                val allCpuBuffers = resource.bufferList.map { it.cpu } + resource.imageBufferList.map { it.buffer }
                val bufferMemoryBarriers = VkBufferMemoryBarrier2.allocate(allCpuBuffers.size.toLong())
                allCpuBuffers.forEachIndexed { i, buffer ->
                    bufferMemoryBarriers[i.toLong()].apply {
                        srcStageMask = VkPipelineStageFlags2.HOST
                        srcAccessMask = VkAccessFlags2.HOST_WRITE
                        dstStageMask = VkPipelineStageFlags2.COPY
                        dstAccessMask = VkAccessFlags2.TRANSFER_READ

                        ofWholeBuffer(buffer)
                    }
                }
                bufferMemoryBarriers(bufferMemoryBarriers)
            }
            cmdBuffer.resetCommandBuffer(VkCommandBufferResetFlags.NONE)

            val beginInfo = VkCommandBufferBeginInfo.allocate {}
            cmdBuffer.beginCommandBuffer(beginInfo.ptr())
            cmdBuffer.cmdPipelineBarrier2(dependencyInfo.ptr())
            cmdBuffer.endCommandBuffer()

            val submitInfo = VkSubmitInfo.allocate {
                commandBuffers(pCommandBuffer)
            }

            queue.queueSubmit(
                1u,
                submitInfo.ptr(),
                inFlightFence
            )
        }
    }

    private var isFirst = true

    private val setupLabel = VkDebugUtilsLabelEXT.allocate(scope) {
        pLabelName = "Setup".c_str(scope)
    }

    private val replayLabel = VkDebugUtilsLabelEXT.allocate(scope) {
        pLabelName = "Replay".c_str(scope)
    }

    context(_: MemoryStack)
    fun execute(queue: VkQueue, swapchainImage: VkImage) {
        MemoryStack {
            cmdBuffer.resetCommandBuffer(VkCommandBufferResetFlags.NONE)

            val beginInfo = VkCommandBufferBeginInfo.allocate {}
            cmdBuffer.beginCommandBuffer(beginInfo.ptr())
            cmdBuffer.cmdBeginDebugUtilsLabelEXT(setupLabel.ptr())

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
            cmdBuffer.cmdPipelineBarrier2(dependencyInfo0.ptr())

            cmdBuffer.cmdPipelineBarrier2(dependencyInfo1.ptr())

            resource.bufferList.forEachIndexed { i, buffer ->
                val bufferMetadata = captureData.metadata.buffers[i]
                cmdBuffer.cmdCopyBuffer(
                    buffer.cpu,
                    buffer.gpu,
                    1u,
                    VkBufferCopy.allocate {
                        srcOffset = 0uL
                        dstOffset = 0uL
                        size = bufferMetadata.size.toULong()
                    }.ptr()
                )
            }
            (resource.imageList zip resource.imageBufferList).forEachIndexed { imageIndex, (dstImage, srcImages) ->
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

                    cmdBuffer.cmdCopyBufferToImage(
                        srcImages.buffer,
                        dstImage,
                        VkImageLayout.TRANSFER_DST_OPTIMAL,
                        srcImages.mipLevelDataOffset.size.toUInt(),
                        copyRegions.ptr()
                    )
                }
            }
            cmdBuffer.cmdPipelineBarrier2(dependencyInfo2.ptr())
            cmdBuffer.cmdEndDebugUtilsLabelEXT()
            cmdBuffer.cmdBeginDebugUtilsLabelEXT(replayLabel.ptr())
            cmdBuffer.cmdBindPipeline(VkPipelineBindPoint.COMPUTE, pipelineInfo.pipeline)

            val pDescriptorSets = VkDescriptorSet.arrayOf(*pipelineInfo.descriptorInfo.descriptorSets.toTypedArray())
            cmdBuffer.cmdBindDescriptorSets(
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
                    cmdBuffer.cmdDispatchIndirect(
                        resource.bufferList[command.bufferIndex].gpu,
                        command.offset.toULong()
                    )
                }

                is Command.DispatchCommand -> {
                    cmdBuffer.cmdDispatch(command.x.toUInt(), command.y.toUInt(), command.z.toUInt())
                }
            }
            cmdBuffer.cmdEndDebugUtilsLabelEXT()
            cmdBuffer.endCommandBuffer()

            val submitInfo = VkSubmitInfo.allocate {
                waitSemaphores(
                    pImageAvailableSemaphore,
                    VkPipelineStageFlags.arrayOf(VkPipelineStageFlags.ALL_COMMANDS)
                )
                commandBuffers(pCommandBuffer)
                signalSemaphores(pRenderFinishedSemaphore)
            }
            queue.queueSubmit(
                1u,
                submitInfo.ptr(),
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
            val set0BindingCount = captureData.metadata.imageBindings.size + captureData.metadata.samplerBindings.size
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
            val set0BindingCount = captureData.metadata.imageBindings.size + captureData.metadata.samplerBindings.size
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