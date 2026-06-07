package dev.luna5ama.glc2vk.replay

import net.echonolix.caelum.NPointer
import net.echonolix.caelum.vulkan.VK_REMAINING_ARRAY_LAYERS
import net.echonolix.caelum.vulkan.VK_REMAINING_MIP_LEVELS
import net.echonolix.caelum.vulkan.VK_WHOLE_SIZE
import net.echonolix.caelum.vulkan.flags.VkImageAspectFlags
import net.echonolix.caelum.vulkan.handles.VkBuffer
import net.echonolix.caelum.vulkan.handles.VkImage
import net.echonolix.caelum.vulkan.structs.*

@JvmName("VkImageMemoryBarrier2_ofWholeImage")
fun NPointer<VkImageMemoryBarrier2>.ofWholeImage(imageObject: VkImage, aspectFlags: VkImageAspectFlags) {
    image = imageObject
    subresourceRange.ofWholeImage(aspectFlags)
}

@JvmName("VkImageSubresourceRange_ofWholeImage")
fun NPointer<VkImageSubresourceRange>.ofWholeImage(aspectFlags: VkImageAspectFlags) {
    this.aspectMask = aspectFlags
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