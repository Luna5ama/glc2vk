package dev.luna5ama.glc2vk.replay;

public class MemorySuballocator {
    public MemorySuballocator(long baseAddress) {
        this.baseAddress = baseAddress;
    }

    private final long baseAddress;
    private long allocatedSize = 0;

    public long allocate(long byteSize, long byteAlignment) {
        byteAlignment = Math.max(byteAlignment, 8);
        long baseAddress = this.baseAddress;
        long sliceOffset = ((baseAddress + allocatedSize + byteAlignment - 1) & -byteAlignment) - baseAddress;
        allocatedSize = sliceOffset + byteSize;
        return sliceOffset;
    }

    public long getAllocatedSize() {
        return allocatedSize;
    }
}
