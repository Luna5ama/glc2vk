package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.common.CaptureData
import dev.luna5ama.glc2vk.common.Command
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.echonolix.caelum.*
import net.echonolix.caelum.glfw.consts.GLFW_CLIENT_API
import net.echonolix.caelum.glfw.consts.GLFW_FALSE
import net.echonolix.caelum.glfw.consts.GLFW_NO_API
import net.echonolix.caelum.glfw.consts.GLFW_RESIZABLE
import net.echonolix.caelum.glfw.functions.*
import net.echonolix.caelum.vulkan.*
import net.echonolix.caelum.vulkan.enums.*
import net.echonolix.caelum.vulkan.flags.*
import net.echonolix.caelum.vulkan.handles.*
import net.echonolix.caelum.vulkan.structs.*
import java.lang.foreign.Arena
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.pathString

object Dummy

private val tempLibDir = Path(System.getProperty("java.io.tmpdir"), "dev.luna5ama.glc2vk")


private fun loadLibrary(libName: String) {
    val osName = System.getProperty("os.name").lowercase()
    val libExt = when {
        osName.contains("windows") -> "dll"
        osName.contains("mac") -> "dylib"
        else -> "so"
    }
    val libraryFileName = "$libName.$libExt"
    val libraryPath = Dummy::class.java.getResource("/$libraryFileName")
        ?: error("Library $libraryFileName not found in resources")

    val libraryFilePathStr = if (libraryPath.protocol == "file") {
        libraryPath.path
    } else {
        val dst = tempLibDir.resolve(libraryFileName)
        if (!dst.exists()) {
            libraryPath.openStream().use {
                Files.copy(it, dst)
            }
        }
        dst.pathString
    }

    System.load(libraryFilePathStr)
}

fun main(args: Array<String>) {
    check(args.size == 1) { "Expected 1 argument: <path to capture>" }
    val capturePath = Path(args[0])
    check(capturePath.exists()) { "Capture file does not exist: $capturePath" }
    val captureData = CaptureData.load(capturePath)

    loadLibrary("glfw3")

    MemoryStack {
        // region Init GLFW
        val window = run {
            glfwInit()
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE)
            val width = 800
            val height = 600
            glfwCreateWindow(width, height, "Vulkan".c_str(), nullptr(), nullptr())
        }
        // endregion

        val useValidationLayer = true

        val layers = if (useValidationLayer) {
            setOf("VK_LAYER_KHRONOS_validation")
        } else {
            emptySet()
        }
        val extensions = buildSet {
            val count = NUInt32.malloc()
            val buffer = glfwGetRequiredInstanceExtensions(count.ptr())
            repeat(count.value.toInt()) {
                add(buffer[it].string)
            }
        } + setOf(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)
        println(extensions)

        val appInfo = VkApplicationInfo.allocate {
            pApplicationName = "Hello Vulkan".c_str()
            applicationVersion = VkApiVersion(0u, 1u, 0u, 0u).value
            pEngineName = "VK Test".c_str()
            engineVersion = VkApiVersion(0u, 1u, 0u, 0u).value
            apiVersion = VK_API_VERSION_1_4.value
        }

        val debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.allocate()
        populateDebugMessengerCreateInfo(debugCreateInfo)

        val createInfo = VkInstanceCreateInfo.allocate {
            pApplicationInfo = appInfo.ptr()
            enabledExtensions(extensions.c_strs())
            enabledLayers(layers.c_strs())
            pNext = if (useValidationLayer) {
                debugCreateInfo.ptr()
            } else {
                nullptr()
            }
        }

        val instance = Vk.createInstance(createInfo.ptr(), null).getOrThrow()
        val debugUtilsMessenger = if (useValidationLayer) {
            instance.createDebugUtilsMessengerEXT(debugCreateInfo.ptr(), null).getOrThrow()
        } else {
            null
        }

        val physicalDevice = choosePhysicalDevice(instance)

        val physicalDeviceProperties = VkPhysicalDeviceProperties.allocate()
        val physicalDeviceFeatures = VkPhysicalDeviceFeatures2.allocate()
        val synchronization2Features = VkPhysicalDeviceSynchronization2Features.allocate()
        physicalDeviceFeatures.pNext = synchronization2Features.ptr()
        physicalDevice.getPhysicalDeviceProperties(physicalDeviceProperties.ptr())
        physicalDevice.getPhysicalDeviceFeatures2(physicalDeviceFeatures.ptr())

        println("Using physical device ${physicalDeviceProperties.deviceName.string}")

        val surface = glfwCreateWindowSurface(instance, window, null).getOrThrow()
        val graphicsQueueFamilyIndex = physicalDevice.chooseGraphicsQueue(surface)

        println("Queue Family: $graphicsQueueFamilyIndex")

        val queuePriority = NFloat.arrayOf(1.0f)
        val queueCreateInfos = VkDeviceQueueCreateInfo.allocate(1)
        queueCreateInfos[0].apply {
            queueFamilyIndex = graphicsQueueFamilyIndex.toUInt()
            queues(queuePriority)
        }
        val deviceExtensions = setOf(
            VK_KHR_SWAPCHAIN_EXTENSION_NAME
        )
        val deviceCreateInfo = VkDeviceCreateInfo.allocate {
            queueCreateInfoes(queueCreateInfos)

            pNext = physicalDeviceFeatures.ptr()

            enabledExtensions(deviceExtensions.c_strs())
            enabledLayers(layers.c_strs())
        }
        val device = physicalDevice.createDevice(deviceCreateInfo.ptr(), null).getOrThrow()
        val graphicsQueueV = VkQueue.malloc()
        device.getDeviceQueue(graphicsQueueFamilyIndex.toUInt(), 0u, graphicsQueueV.ptr())
        val graphicsQueue = VkQueue.fromNativeData(device, graphicsQueueV.value)

        val swapchainSupport = physicalDevice.querySwapchainSupport(surface)
//        println("Supported swapchain Formats:")
//        swapchainSupport.formats.forEach {
//            println("${it.format}\t${it.colorSpace}")
//        }
//        println()
//        println("Supported swapchain Present Modes:")
//        swapchainSupport.presentModes.forEach {
//            println(it)
//        }
//        println()

        val surfaceFormat = chooseSwapchainFormat(swapchainSupport.formats)!!
        println("Using swapchain format: ${surfaceFormat.format} ${surfaceFormat.colorSpace}")
        val presentMode = choosePresentMode(swapchainSupport.presentModes)
        println("Using swapchain present mode: $presentMode")
        val swapchainExtent = chooseSwapchainExtent(window, swapchainSupport.capabilities)
        println("Using swapchain extent: ${swapchainExtent.width}x${swapchainExtent.height}")

        val swapchainCreateInfo = VkSwapchainCreateInfoKHR.allocate {
            this.surface = surface
            minImageCount = 2u
            imageFormat = surfaceFormat.format
            imageColorSpace = surfaceFormat.colorSpace
            imageExtent = swapchainExtent
            imageArrayLayers = 1u
            imageUsage = VkImageUsageFlags.COLOR_ATTACHMENT

            imageSharingMode = VkSharingMode.EXCLUSIVE

            preTransform = swapchainSupport.capabilities.currentTransform
            compositeAlpha = VkCompositeAlphaFlagsKHR.OPAQUE_KHR
            this.presentMode = presentMode
            clipped = 1u
        }
        val swapchain = device.createSwapchainKHR(swapchainCreateInfo.ptr(), null).getOrThrow()

        val swapchainImageCount = NUInt32.malloc()
        device.getSwapchainImagesKHR(swapchain, swapchainImageCount.ptr(), null)
        println("Swapchain image count: ${swapchainImageCount.value}")
        val swapchainImages = buildList {
            val buffer = VkImage.malloc(swapchainImageCount.value.toLong())
            device.getSwapchainImagesKHR(swapchain, swapchainImageCount.ptr(), buffer.ptr())
            repeat(swapchainImageCount.value.toInt()) {
                add(VkImage.fromNativeData(device, buffer[it]))
            }
        }
        val swapchainImageFormat = surfaceFormat.format
        val swapchainImageViews = buildList {
            repeat(swapchainImageCount.value.toInt()) {
                val createInfo = VkImageViewCreateInfo.allocate {
                    image = swapchainImages[it]
                    viewType = VkImageViewType.`2D`
                    format = swapchainImageFormat
                    components.r = VkComponentSwizzle.IDENTITY
                    components.g = VkComponentSwizzle.IDENTITY
                    components.b = VkComponentSwizzle.IDENTITY
                    components.a = VkComponentSwizzle.IDENTITY
                    subresourceRange.aspectMask = VkImageAspectFlags.COLOR
                    subresourceRange.baseMipLevel = 0u
                    subresourceRange.levelCount = 1u
                    subresourceRange.baseArrayLayer = 0u
                    subresourceRange.layerCount = 1u
                }
                add(device.createImageView(createInfo.ptr(), null).getOrThrow())
            }
        }

        val commandPoolCreateInfo = VkCommandPoolCreateInfo.allocate {
            flags = VkCommandPoolCreateFlags.RESET_COMMAND_BUFFER
            queueFamilyIndex = graphicsQueueFamilyIndex.toUInt()
        }
        val commandPool = device.createCommandPool(commandPoolCreateInfo.ptr(), null).getOrThrow()

        val memoryProperties = VkPhysicalDeviceMemoryProperties2.allocate()
        physicalDevice.getPhysicalDeviceMemoryProperties2(memoryProperties.ptr())
        val memoryTypes = MemoryTypeManager(memoryProperties.memoryProperties)

        val queueFamiliesIndicesNArray = NUInt32.arrayOf(graphicsQueueFamilyIndex.toUInt())


        data class DoubleData<T>(val cpu: T, val gpu: T)
        data class MutableDoubleData<T>(var cpu: T, var gpu: T)

        val bufferSuballocateOffsets = DoubleData(LongArrayList(), LongArrayList())
        val bufferSubAllocator = DoubleData(MemorySuballocator(0L), MemorySuballocator(0L))
        val bufferMemoryTypeBits = MutableDoubleData(VkMemoryPropertyFlags.NONE, VkMemoryPropertyFlags.NONE)

        val bufferList = captureData.metadata.buffers.map {
            MemoryStack {
                val gpuBuffer = MemoryStack {
                    val usages = VkBufferUsageFlags2.STORAGE_TEXEL_BUFFER +
                            VkBufferUsageFlags2.UNIFORM_TEXEL_BUFFER +
                            VkBufferUsageFlags2.STORAGE_BUFFER +
                            VkBufferUsageFlags2.UNIFORM_BUFFER +
                            VkBufferUsageFlags2.TRANSFER_DST +
                            VkBufferUsageFlags2.SHADER_DEVICE_ADDRESS

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

        val bufferDeviceMemory = if (bufferList.isNotEmpty()) {
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

        val imageMemoryTypeBits = MutableDoubleData(VkMemoryPropertyFlags.NONE, VkMemoryPropertyFlags.NONE)
        val imageSuballocateOffsets = DoubleData(LongArrayList(), LongArrayList())
        val imageSubAllocator = DoubleData(MemorySuballocator(0L), MemorySuballocator(0L))

        val imageList = captureData.metadata.images.map {
            MemoryStack {
                val imageType = when (it.type) {
                    dev.luna5ama.glc2vk.common.VkImageViewType.`1D` -> VkImageType.`1D`
                    dev.luna5ama.glc2vk.common.VkImageViewType.`2D` -> VkImageType.`2D`
                    dev.luna5ama.glc2vk.common.VkImageViewType.`3D` -> VkImageType.`3D`
                    dev.luna5ama.glc2vk.common.VkImageViewType.`1D_ARRAY` -> VkImageType.`1D`
                    dev.luna5ama.glc2vk.common.VkImageViewType.`2D_ARRAY` -> VkImageType.`2D`
                    dev.luna5ama.glc2vk.common.VkImageViewType.CUBE -> VkImageType.`2D`
                    dev.luna5ama.glc2vk.common.VkImageViewType.CUBE_ARRAY -> VkImageType.`2D`
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

        val imageDeviceMemory = if (imageList.isNotEmpty()) {
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

        val pCommandBuffer = VkCommandBuffer.malloc(1)
        val commandBufferAllocateInfo = VkCommandBufferAllocateInfo.allocate {
            this.commandPool = commandPool
            level = VkCommandBufferLevel.PRIMARY
            commandBufferCount = 1u
        }
        val fenceCreateInfo = VkFenceCreateInfo.allocate {
            flags = VkFenceCreateFlags.SIGNALED
        }
        device.allocateCommandBuffers(commandBufferAllocateInfo.ptr(), pCommandBuffer.ptr())
        val commandBuffer = VkCommandBuffer.fromNativeData(commandPool, pCommandBuffer[0])

        val semaphoreCreateInfo = VkSemaphoreCreateInfo.allocate {}
        val renderFinishedSemaphore = device.createSemaphore(semaphoreCreateInfo.ptr(), null).getOrThrow()
        val pRenderFinishedSemaphore = VkSemaphore.arrayOf(renderFinishedSemaphore)

        val imageAvailableSemaphore = device.createSemaphore(semaphoreCreateInfo.ptr(), null).getOrThrow()
        val pImageAvailableSemaphore = VkSemaphore.arrayOf(imageAvailableSemaphore)

        val inFlightFence = device.createFence(fenceCreateInfo.ptr(), null).getOrThrow()

        val fences = VkFence.arrayOf(inFlightFence)

        val initializeSemaphore = device.createSemaphore(semaphoreCreateInfo.ptr(), null).getOrThrow()
        val pInitializeSemaphore = VkSemaphore.arrayOf(initializeSemaphore)

        // Initial layout transitions
        MemoryStack {
            device.waitForFences(1u, fences.ptr(), VK_TRUE, ULong.MAX_VALUE)
            device.resetFences(1u, fences.ptr())

            val dependencyInfo = VkDependencyInfo.allocate {
                val imageMemoryBarriers = VkImageMemoryBarrier2.allocate(imageList.size.toLong())
                imageList.forEachIndexed { i, image ->
                    imageMemoryBarriers[i.toLong()].apply {
                        srcStageMask = VkPipelineStageFlags2.HOST
                        srcAccessMask = VkAccessFlags2.HOST_WRITE
                        dstStageMask = VkPipelineStageFlags2.COPY
                        dstAccessMask = VkAccessFlags2.TRANSFER_READ
                        oldLayout = VkImageLayout.PREINITIALIZED
                        newLayout = VkImageLayout.TRANSFER_SRC_OPTIMAL

                        ofWholeImage(image.cpu)
                    }
                }
                imageMemoryBarriers(imageMemoryBarriers)
                val bufferMemoryBarriers = VkBufferMemoryBarrier2.allocate(bufferList.size.toLong())
                bufferList.forEachIndexed { i, buffer ->
                    bufferMemoryBarriers[i.toLong()].apply {
                        srcStageMask = VkPipelineStageFlags2.HOST
                        srcAccessMask = VkAccessFlags2.HOST_WRITE
                        dstStageMask = VkPipelineStageFlags2.COPY
                        dstAccessMask = VkAccessFlags2.TRANSFER_READ

                        ofWholeBuffer(buffer.cpu)
                    }
                }
                bufferMemoryBarriers(bufferMemoryBarriers)
            }
            commandBuffer.resetCommandBuffer(VkCommandBufferResetFlags.NONE)

            val beginInfo = VkCommandBufferBeginInfo.allocate {}
            commandBuffer.beginCommandBuffer(beginInfo.ptr())
            commandBuffer.cmdPipelineBarrier2(dependencyInfo.ptr())
            commandBuffer.endCommandBuffer()

            val submitInfo = VkSubmitInfo.allocate {
                commandBuffers(pCommandBuffer)
                signalSemaphores(pInitializeSemaphore)
            }

            graphicsQueue.queueSubmit(
                1u,
                submitInfo.ptr(),
                inFlightFence
            )
        }

        val dependencyInfo1 = VkDependencyInfo.allocate {
            val bufferMemoryBarriers = VkBufferMemoryBarrier2.allocate(bufferList.size.toLong())
            bufferList.forEachIndexed { i, buffer ->
                bufferMemoryBarriers[i.toLong()].apply {
                    srcStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                    srcAccessMask = VkAccessFlags2.NONE
                    dstStageMask = VkPipelineStageFlags2.COPY
                    dstAccessMask = VkAccessFlags2.TRANSFER_WRITE

                    ofWholeBuffer(buffer.gpu)
                }
            }
            bufferMemoryBarriers(bufferMemoryBarriers)
            val imageMemoryBarriers = VkImageMemoryBarrier2.allocate(imageList.size.toLong())
            imageList.forEachIndexed { i, image ->
                imageMemoryBarriers[i.toLong()].apply {
                    srcStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                    srcAccessMask = VkAccessFlags2.NONE
                    dstStageMask = VkPipelineStageFlags2.COPY
                    dstAccessMask = VkAccessFlags2.TRANSFER_WRITE
                    oldLayout = VkImageLayout.UNDEFINED
                    newLayout = VkImageLayout.TRANSFER_DST_OPTIMAL

                    ofWholeImage(image.gpu)
                }
            }
        }

        val dependencyInfo2 = VkDependencyInfo.allocate {
            val bufferMemoryBarriers = VkBufferMemoryBarrier2.allocate(captureData.metadata.buffers.size.toLong())
            bufferList.forEachIndexed { i, buffer ->
                bufferMemoryBarriers[i.toLong()].apply {
                    srcStageMask = VkPipelineStageFlags2.COPY
                    srcAccessMask = VkAccessFlags2.TRANSFER_READ
                    dstStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                    dstAccessMask = VkAccessFlags2.MEMORY_READ

                    ofWholeBuffer(buffer.gpu)
                }
            }
            bufferMemoryBarriers(bufferMemoryBarriers)
            val imageMemoryBarriers = VkImageMemoryBarrier2.allocate(captureData.metadata.images.size.toLong())
            imageList.forEachIndexed { i, image ->
                imageMemoryBarriers[i.toLong()].apply {
                    srcStageMask = VkPipelineStageFlags2.COPY
                    srcAccessMask = VkAccessFlags2.TRANSFER_READ
                    dstStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                    dstAccessMask = VkAccessFlags2.MEMORY_READ
                    oldLayout = VkImageLayout.TRANSFER_DST_OPTIMAL
                    newLayout = VkImageLayout.GENERAL

                    ofWholeImage(image.gpu)
                }
            }
            imageMemoryBarriers(imageMemoryBarriers)
        }

        device.deviceWaitIdle()

        var isFirst = true

        while (glfwWindowShouldClose(window) == GLFW_FALSE) {
            glfwPollEvents()
            MemoryStack {
                device.waitForFences(1u, fences.ptr(), VK_TRUE, ULong.MAX_VALUE)
                device.resetFences(1u, fences.ptr())

                val pImageIndex = NUInt32.malloc(1)
                device.acquireNextImageKHR(
                    swapchain,
                    ULong.MAX_VALUE,
                    imageAvailableSemaphore,
                    VkFence.fromNativeData(device, 0L),
                    pImageIndex.ptr()
                )

                commandBuffer.resetCommandBuffer(VkCommandBufferResetFlags.NONE)

                val beginInfo = VkCommandBufferBeginInfo.allocate {}
                commandBuffer.beginCommandBuffer(beginInfo.ptr())

                val dependencyInfo0 = VkDependencyInfo.allocate {
                    val imageMemoryBarrier = VkImageMemoryBarrier2.allocate(1L)
                    imageMemoryBarrier[0].apply {
                        srcStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                        srcAccessMask = VkAccessFlags2.NONE
                        dstStageMask = VkPipelineStageFlags2.ALL_COMMANDS
                        dstAccessMask = VkAccessFlags2.NONE
                        oldLayout = VkImageLayout.UNDEFINED
                        newLayout = VkImageLayout.PRESENT_SRC_KHR

                        ofWholeImage(swapchainImages[pImageIndex[0].toInt()])
                    }
                    imageMemoryBarriers(imageMemoryBarrier)
                }
                commandBuffer.cmdPipelineBarrier2(dependencyInfo0.ptr())

                commandBuffer.cmdPipelineBarrier2(dependencyInfo1.ptr())

                bufferList.forEachIndexed { i, buffer ->
                    val bufferMetadata = captureData.metadata.buffers[i]
                    commandBuffer.cmdCopyBuffer(
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
                imageList.forEachIndexed { i, image ->
                    val imageMetadata = captureData.metadata.images[i]
                    commandBuffer.cmdCopyImage(
                        image.cpu,
                        VkImageLayout.TRANSFER_SRC_OPTIMAL,
                        image.gpu,
                        VkImageLayout.TRANSFER_DST_OPTIMAL,
                        1u,
                        VkImageCopy.allocate {
                            srcSubresource {
                               aspectMask = VkImageAspectFlags.COLOR
                                mipLevel = 0u
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
                                mipLevel = 0u
                                baseArrayLayer = 0u
                                layerCount = imageMetadata.arrayLayers.toUInt()
                            }

                            dstOffset {
                                x = 0
                                y = 0
                                z = 0
                            }

                            extent {
                                width = imageMetadata.width.toUInt()
                                height = imageMetadata.height.toUInt()
                                depth = imageMetadata.depth.toUInt()
                            }
                        }.ptr()
                    )
                }
                commandBuffer.cmdPipelineBarrier2(dependencyInfo2.ptr())
                // Replay commands
                when (val command = captureData.metadata.command) {
                    is Command.DispatchIndirectCommand -> {
                        commandBuffer.cmdDispatchIndirect(bufferList[command.bufferIndex].gpu, command.offset.toULong())
                    }

                    is Command.DispatchCommand -> {
                        // TODO
                    }
                }
                commandBuffer.endCommandBuffer()

                val submitInfo = VkSubmitInfo.allocate {
                    waitSemaphores(
                        pImageAvailableSemaphore,
                        VkPipelineStageFlags.arrayOf(VkPipelineStageFlags.COLOR_ATTACHMENT_OUTPUT)
                    )
                    commandBuffers(pCommandBuffer)
                    signalSemaphores(pRenderFinishedSemaphore)
                }
                graphicsQueue.queueSubmit(
                    1u,
                    submitInfo.ptr(),
                    inFlightFence
                )

                val waitSemaphores = if (isFirst) {
                    VkSemaphore.arrayOf(initializeSemaphore, renderFinishedSemaphore)
                } else {
                    pRenderFinishedSemaphore
                }

                val presentInfo = VkPresentInfoKHR.allocate {
                    waitSemaphores(waitSemaphores)

                    val dummy = VkResult.malloc(1)
                    swapchains(VkSwapchainKHR.arrayOf(swapchain), pImageIndex, dummy)
                }
                graphicsQueue.queuePresentKHR(presentInfo.ptr())
            }
            device.deviceWaitIdle()
            isFirst = false
        }

        device.deviceWaitIdle()

        device.destroyFence(inFlightFence, null)
        device.destroySemaphore(initializeSemaphore, null)
        device.destroySemaphore(imageAvailableSemaphore, null)
        device.destroySemaphore(renderFinishedSemaphore, null)

        bufferList.forEach {
            device.destroyBuffer(it.cpu, null)
            device.destroyBuffer(it.gpu, null)
        }
        if (bufferDeviceMemory != null) {
            device.freeMemory(bufferDeviceMemory.gpu, null)
            device.freeMemory(bufferDeviceMemory.cpu, null)
        }
        imageList.forEach {
            device.destroyImage(it.cpu, null)
            device.destroyImage(it.gpu, null)
        }
        if (imageDeviceMemory != null) {
            device.freeMemory(imageDeviceMemory.gpu, null)
            device.freeMemory(imageDeviceMemory.cpu, null)
        }
        device.destroyCommandPool(commandPool, null)
        for (imageView in swapchainImageViews) {
            device.destroyImageView(imageView, null)
        }
        device.destroySwapchainKHR(swapchain, null)
        device.destroyDevice(null)
        instance.destroySurfaceKHR(surface, null)
        if (debugUtilsMessenger != null) {
            instance.destroyDebugUtilsMessengerEXT(debugUtilsMessenger, null)
        }
        instance.destroyInstance(null)

        glfwTerminate()
    }
}

context(_: MemoryStack)
@OptIn(UnsafeAPI::class)
private fun makePipeline(
    vkTestShaderModule: VkShaderModule,
    swapchainExtent: NPointer<VkExtent2D>,
    device: VkDevice,
    swapchainImageFormat: VkFormat
): Triple<VkPipelineLayout, VkRenderPass, VkPipeline> {
    MemoryStack {
        val shaderStages = VkPipelineShaderStageCreateInfo.allocate(2)
        shaderStages[0].apply {
            stage = VkShaderStageFlags.VERTEX
            module = vkTestShaderModule
            pName = "vertexMain".c_str()
        }
        shaderStages[1].apply {
            stage = VkShaderStageFlags.FRAGMENT
            module = vkTestShaderModule
            pName = "fragmentMain".c_str()
        }

        val vertexInputStateCreateInfo = VkPipelineVertexInputStateCreateInfo.allocate {
            vertexBindingDescriptionCount = 0u
            pVertexBindingDescriptions = nullptr()
            vertexAttributeDescriptionCount = 0u
            pVertexAttributeDescriptions = nullptr()
        }

        val inputAssemblyStateCreateInfo = VkPipelineInputAssemblyStateCreateInfo.allocate {
            topology = VkPrimitiveTopology.TRIANGLE_LIST
            primitiveRestartEnable = 0u
        }

        val viewport = VkViewport.allocate()
        viewport.x = 0.0f
        viewport.y = 0.0f
        viewport.width = swapchainExtent.width.toFloat()
        viewport.height = swapchainExtent.height.toFloat()
        viewport.minDepth = 0.0f
        viewport.maxDepth = 1.0f

        val scissor = VkRect2D.allocate()
        scissor.offset.x = 0
        scissor.offset.y = 0
        scissor.extent = swapchainExtent

        val viewportStateCreateInfo = VkPipelineViewportStateCreateInfo.allocate {
            viewportCount = 1u
            pViewports = viewport.ptr()
            scissorCount = 1u
            pScissors = scissor.ptr()
        }

        val rasterizationStateCreateInfo = VkPipelineRasterizationStateCreateInfo.allocate {
            depthClampEnable = VK_FALSE
            rasterizerDiscardEnable = VK_FALSE
            polygonMode = VkPolygonMode.FILL
            lineWidth = 1f
            cullMode = VkCullModeFlags.NONE
            frontFace = VkFrontFace.CLOCKWISE
            depthBiasEnable = VK_FALSE
            depthBiasConstantFactor = 0.0f // Optional
            depthBiasClamp = 0.0f // Optional
            depthBiasSlopeFactor = 0.0f // Optional
        }

        val multisampleStateCreateInfo = VkPipelineMultisampleStateCreateInfo.allocate {
            sampleShadingEnable = VK_FALSE
            rasterizationSamples = VkSampleCountFlags.`1_BIT`
            minSampleShading = 1.0f // Optional
            pSampleMask = nullptr() // Optional
            alphaToCoverageEnable = VK_FALSE // Optional
            alphaToOneEnable = VK_FALSE // Optional
        }

        val colorBlendAttachmentState = VkPipelineColorBlendAttachmentState.allocate {
            colorWriteMask =
                VkColorComponentFlags.R + VkColorComponentFlags.G + VkColorComponentFlags.B + VkColorComponentFlags.A
            blendEnable = VK_FALSE
            srcColorBlendFactor = VkBlendFactor.ONE // Optional
            dstColorBlendFactor = VkBlendFactor.ZERO // Optional
            colorBlendOp = VkBlendOp.ADD // Optional
            srcAlphaBlendFactor = VkBlendFactor.ONE // Optional
            dstAlphaBlendFactor = VkBlendFactor.ZERO // Optional
            alphaBlendOp = VkBlendOp.ADD // Optional
        }

        val colorBlendState = VkPipelineColorBlendStateCreateInfo.allocate {
            logicOpEnable = VK_FALSE
            logicOp = VkLogicOp.COPY // Optional
            attachmentCount = 1u
            pAttachments = colorBlendAttachmentState.ptr()
            blendConstants[0] = 0.0f // Optional
            blendConstants[1] = 0.0f // Optional
            blendConstants[2] = 0.0f // Optional
            blendConstants[3] = 0.0f // Optional
        }

        val pipelineLayoutCreateInfo = VkPipelineLayoutCreateInfo.allocate {
            setLayoutCount = 0u // Optional
            pSetLayouts = nullptr() // Optional
            pushConstantRangeCount = 0u // Optional
            pPushConstantRanges = nullptr() // Optional
        }

        val pipelineLayout = device.createPipelineLayout(pipelineLayoutCreateInfo.ptr(), null).getOrThrow()

        val colorAttachment = VkAttachmentDescription.allocate {
            format = swapchainImageFormat
            samples = VkSampleCountFlags.`1_BIT`
            loadOp = VkAttachmentLoadOp.CLEAR
            storeOp = VkAttachmentStoreOp.STORE
            stencilLoadOp = VkAttachmentLoadOp.DONT_CARE
            stencilStoreOp = VkAttachmentStoreOp.DONT_CARE
            initialLayout = VkImageLayout.UNDEFINED
            finalLayout = VkImageLayout.PRESENT_SRC_KHR
        }

        val colorAttachmentRef = VkAttachmentReference.allocate {
            attachment = 0u
            layout = VkImageLayout.COLOR_ATTACHMENT_OPTIMAL
        }

        val subpass = VkSubpassDescription.allocate {
            pipelineBindPoint = VkPipelineBindPoint.GRAPHICS
            colorAttachmentCount = 1u
            pColorAttachments = colorAttachmentRef.ptr()
        }

        val subpassDependency = VkSubpassDependency.allocate {
            srcSubpass = VK_SUBPASS_EXTERNAL
            dstSubpass = 0u
            srcStageMask = VkPipelineStageFlags.COLOR_ATTACHMENT_OUTPUT
            srcAccessMask = VkAccessFlags.NONE
            dstStageMask = VkPipelineStageFlags.COLOR_ATTACHMENT_OUTPUT
            dstAccessMask = VkAccessFlags.COLOR_ATTACHMENT_WRITE
        }

        val renderPassCreateInfo = VkRenderPassCreateInfo.allocate {
            attachmentCount = 1u
            pAttachments = colorAttachment.ptr()
            subpassCount = 1u
            pSubpasses = subpass.ptr()
            dependencyCount = 1u
            pDependencies = subpassDependency.ptr()
        }

        val renderPass = device.createRenderPass(renderPassCreateInfo.ptr(), null).getOrThrow()

        val pipelineCreateInfo = VkGraphicsPipelineCreateInfo.allocate {
            stageCount = 2u
            pStages = shaderStages.ptr()
            pVertexInputState = vertexInputStateCreateInfo.ptr()
            pInputAssemblyState = inputAssemblyStateCreateInfo.ptr()
            pViewportState = viewportStateCreateInfo.ptr()
            pRasterizationState = rasterizationStateCreateInfo.ptr()
            pMultisampleState = multisampleStateCreateInfo.ptr()
            pDepthStencilState = nullptr()
            pColorBlendState = colorBlendState.ptr()
            pDynamicState = nullptr()
            layout = pipelineLayout
            this.renderPass = renderPass
            this.subpass = 0u
        }


        val pipeline = device.createGraphicsPipelines(
            VkPipelineCache.fromNativeData(device, 0L), 1u, pipelineCreateInfo.ptr(), null
        ).getOrThrow()
        return Triple(pipelineLayout, renderPass, pipeline)
    }
}

