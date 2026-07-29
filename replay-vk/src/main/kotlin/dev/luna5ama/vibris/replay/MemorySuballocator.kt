package dev.luna5ama.vibris.replay

class MemorySuballocator(private val baseAddress: Long) {
    var allocatedSize = 0L
        private set

    fun allocate(byteSize: Long, requestedAlignment: Long): Long {
        val alignment = maxOf(requestedAlignment, 8L)
        val sliceOffset = ((baseAddress + allocatedSize + alignment - 1L) and -alignment) - baseAddress
        allocatedSize = sliceOffset + byteSize
        return sliceOffset
    }
}