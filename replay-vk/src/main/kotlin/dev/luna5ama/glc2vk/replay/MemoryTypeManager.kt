package dev.luna5ama.glc2vk.replay

import net.echonolix.caelum.NPointer
import net.echonolix.caelum.UnsafeAPI
import net.echonolix.caelum.vulkan.flags.VkMemoryPropertyFlags
import net.echonolix.caelum.vulkan.structs.VkPhysicalDeviceMemoryProperties
import net.echonolix.caelum.vulkan.structs.get
import net.echonolix.caelum.vulkan.structs.memoryTypeCount
import net.echonolix.caelum.vulkan.structs.memoryTypes
import net.echonolix.caelum.vulkan.structs.propertyFlags

@OptIn(UnsafeAPI::class)
class MemoryTypeManager(private val memoryProperties: NPointer<VkPhysicalDeviceMemoryProperties>) {
    init {
        println("Memory Types:")
        for (i in 0..<memoryProperties.memoryTypeCount.toLong()) {
            val type = memoryProperties.memoryTypes[i]
            println("  Type $i: ${type.propertyFlags}")
        }
    }

    val useBarMemory = System.getProperty("glc2vk.useBarMemory").toBoolean()

    val device = findType(
        VkMemoryPropertyFlags.DEVICE_LOCAL,
        VkMemoryPropertyFlags(-1) // Exclude all flags
    ).findType(
        VkMemoryPropertyFlags.DEVICE_LOCAL,
        VkMemoryPropertyFlags.HOST_CACHED
    )

    val staging = findType(
        VkMemoryPropertyFlags.HOST_VISIBLE +
                VkMemoryPropertyFlags.HOST_COHERENT,
        VkMemoryPropertyFlags.DEVICE_LOCAL
    ).findType(
        VkMemoryPropertyFlags.HOST_VISIBLE +
                VkMemoryPropertyFlags.HOST_COHERENT +
                VkMemoryPropertyFlags.HOST_CACHED,
        VkMemoryPropertyFlags.DEVICE_LOCAL
    ).findType(
        VkMemoryPropertyFlags.DEVICE_LOCAL +
                VkMemoryPropertyFlags.HOST_VISIBLE +
                VkMemoryPropertyFlags.HOST_COHERENT,
        VkMemoryPropertyFlags.NONE
    ).findType(
        VkMemoryPropertyFlags.DEVICE_LOCAL +
                VkMemoryPropertyFlags.HOST_VISIBLE +
                VkMemoryPropertyFlags.HOST_COHERENT +
                VkMemoryPropertyFlags.HOST_CACHED,
        VkMemoryPropertyFlags.NONE
    )

    val bar = findType(
        VkMemoryPropertyFlags.HOST_VISIBLE +
                VkMemoryPropertyFlags.HOST_COHERENT +
                VkMemoryPropertyFlags.DEVICE_LOCAL,
        VkMemoryPropertyFlags.HOST_CACHED
    ).findType(
        VkMemoryPropertyFlags.HOST_VISIBLE +
                VkMemoryPropertyFlags.HOST_COHERENT +
                VkMemoryPropertyFlags.HOST_CACHED +
                VkMemoryPropertyFlags.DEVICE_LOCAL,
        VkMemoryPropertyFlags.NONE
    ).findType(
        VkMemoryPropertyFlags.DEVICE_LOCAL +
                VkMemoryPropertyFlags.HOST_VISIBLE +
                VkMemoryPropertyFlags.HOST_COHERENT,
        VkMemoryPropertyFlags.NONE
    ).findType(
        VkMemoryPropertyFlags.DEVICE_LOCAL +
                VkMemoryPropertyFlags.HOST_VISIBLE +
                VkMemoryPropertyFlags.HOST_COHERENT +
                VkMemoryPropertyFlags.HOST_CACHED,
        VkMemoryPropertyFlags.NONE
    )

    val stagingFast = if (useBarMemory) bar else staging

    fun findType(
        inclusive: VkMemoryPropertyFlags,
        exclusive: VkMemoryPropertyFlags
    ): UInt {
        return UInt.MAX_VALUE.findType(inclusive, exclusive)
    }

    @OptIn(UnsafeAPI::class)
    fun UInt.findType(
        inclusive: VkMemoryPropertyFlags,
        exclusive: VkMemoryPropertyFlags
    ): UInt {
        if (this != UInt.MAX_VALUE) return this
        var result = UInt.MAX_VALUE
        val inclusiveBits = inclusive.value
        val exclusiveBits = exclusive.value
        for (i in 0..<memoryProperties.memoryTypeCount.toLong()) {
            val bits = memoryProperties.memoryTypes[i].propertyFlags.value
            if (bits and inclusiveBits != inclusiveBits) continue
            if (bits and exclusiveBits != 0) continue
            result = i.toUInt()
            break
        }
        return result
    }
}