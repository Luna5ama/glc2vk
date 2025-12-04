package dev.luna5ama.glc2vk.replay

import net.echonolix.caelum.NPointer
import net.echonolix.caelum.vulkan.VK_REMAINING_ARRAY_LAYERS
import net.echonolix.caelum.vulkan.VK_REMAINING_MIP_LEVELS
import net.echonolix.caelum.vulkan.VK_WHOLE_SIZE
import net.echonolix.caelum.vulkan.flags.VkImageAspectFlags
import net.echonolix.caelum.vulkan.handles.VkBuffer
import net.echonolix.caelum.vulkan.handles.VkImage
import net.echonolix.caelum.vulkan.structs.VkBufferMemoryBarrier2
import net.echonolix.caelum.vulkan.structs.VkImageMemoryBarrier2
import net.echonolix.caelum.vulkan.structs.VkImageSubresourceRange
import net.echonolix.caelum.vulkan.structs.aspectMask
import net.echonolix.caelum.vulkan.structs.baseArrayLayer
import net.echonolix.caelum.vulkan.structs.baseMipLevel
import net.echonolix.caelum.vulkan.structs.buffer
import net.echonolix.caelum.vulkan.structs.image
import net.echonolix.caelum.vulkan.structs.layerCount
import net.echonolix.caelum.vulkan.structs.levelCount
import net.echonolix.caelum.vulkan.structs.offset
import net.echonolix.caelum.vulkan.structs.size
import net.echonolix.caelum.vulkan.structs.subresourceRange

@JvmName("VkImageMemoryBarrier2_ofWholeImage")
fun NPointer<VkImageMemoryBarrier2>.ofWholeImage(imageObject: VkImage) {
    image = imageObject
    subresourceRange.ofWholeImage()
}

@JvmName("VkImageSubresourceRange_ofWholeImage")
fun NPointer<VkImageSubresourceRange>.ofWholeImage() {
    aspectMask = VkImageAspectFlags.COLOR
    baseMipLevel = 0u
    levelCount = VK_REMAINING_MIP_LEVELS
    baseArrayLayer = 0u
    layerCount = VK_REMAINING_ARRAY_LAYERS
}

fun NPointer<VkBufferMemoryBarrier2>.ofWholeBuffer(bufferObject: VkBuffer) {
    buffer = bufferObject
    offset = 0u
    size = VK_WHOLE_SIZE
}