// Thanks to https://github.com/SpartanB312
package dev.luna5ama.glc2vk.capture

inline fun struct(block: StructAllocator.() -> Unit): StructAllocator {
    val allocator = StructAllocator()
    allocator.block()
    return allocator
}

class StructAllocator {
    var alignment = 1; private set
    var size = 0; private set
    private var offset = 0

    private fun roundUp(value: Int, alignment: Int): Int {
        return (value + alignment - 1) / alignment * alignment
    }

    fun element(elementSize: Int, elementAlignment: Int): ElementInfo {
        alignment = maxOf(elementAlignment)
        val elementOffset = roundUp(offset, elementAlignment)
        offset = elementOffset + elementSize
        size = roundUp(offset, alignment)
        return ElementInfo(elementOffset, elementSize)
    }

    private fun dataSize(bitSize: Int): Int {
        return when (bitSize) {
            32 -> 4
            64 -> 8
            else -> error("Unsupported data size: $bitSize")
        }
    }

    fun scalar(bitSize: Int): ElementInfo {
        val dataSize =  dataSize(bitSize)
        return element(dataSize, dataSize)
    }

    private fun vectorSize(components: Int, bitSize: Int): Int {
        val multiplier = when (components) {
            2 -> 2
            3, 4 -> 4
            else -> error("Unsupported vector size: $components")
        }
        return multiplier * dataSize(bitSize)
    }

    fun vector(components: Int, bitSize: Int): ElementInfo {
        val vectorSize = vectorSize(components, bitSize)
        return element(vectorSize, vectorSize)
    }

    private fun array(elementSize: Int, count: Int): ElementInfo {
        // OpenGL specs 7.6.2.2:
        // If the member is an array of scalars or vectors, the base alignment and array
        // stride are set to match the base alignment of a single array element, according
        // to rules (1), (2), and (3), and rounded up to the base alignment of a vec4.
        val alignmentAndStride = roundUp(elementSize, vectorSize(4, 32))

        return element(alignmentAndStride * count, alignmentAndStride)
    }

    fun scalarArray(count: Int, bitSize: Int): ElementInfo {
        return array(dataSize(bitSize), count)
    }

    fun vectorArray(components: Int, bitSize: Int, count: Int): ElementInfo {
        return array(vectorSize(components, bitSize), count)
    }

    fun matrix(columns: Int, rows: Int, bitSize: Int): ElementInfo {
        // OpenGL specs 7.6.2.2:
        // If the member is a column-major matrix with C columns and R rows, the
        // matrix is stored identically to an array of C column vectors with R components each, according to rule (4).
        return vectorArray(rows, bitSize, columns)
    }

    data class ElementInfo(
        val offset: Int,
        val size: Int,
    )
}