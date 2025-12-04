// Thanks to https://github.com/SpartanB312
package dev.luna5ama.glc2vk.capture

import kotlin.math.ceil

/**
 * Using std140 storage layout
 * Notice: please check the max caps of alignOffset of current device
 */
class UniformBlock(builder: Struct.() -> Unit) : IStruct by Struct.build(builder = builder)

interface Attribute {
    val name: String
    val type: String
    val baseAlign: Int
    val baseOffset: Int
    val alignOffset: Int
    val used: Int
    val desc: String get() = "$type $name; $baseAlign, $baseOffset, $alignOffset"
    val isDataAttribute get() = this !is StructSPadding
}

interface IStruct {
    val elements: MutableList<Attribute>
    val size: Int
    val desc: String
}

open class Struct(
    override val name: String,
    override val type: String = "Struct",
    private val lastMember: Attribute? = null
) : Attribute, IStruct {

    override val elements = mutableListOf<Attribute>()

    final override val baseAlign: Int = 16
    final override val used: Int = 0
    final override val baseOffset: Int = lastMember?.let { it.alignOffset + it.used } ?: 0
    final override val alignOffset: Int = (baseOffset / baseAlign.toFloat()).ceilToInt() * baseAlign
    val last: Attribute
        get() {
            val lastElement = elements.lastOrNull()
            return if (lastElement is Struct) lastElement.last
            else lastElement ?: this
        }
    override val size get() = last.let { it.baseOffset + it.used }
    override val desc: String
        get() {
            var str = if (lastMember == null) "" else "\n" + super.desc + "\n"
            elements.forEachIndexed { index, it ->
                str += if (index != elements.size - 1) it.desc + "\n"
                else if (lastMember == null) "" else it.desc + "\n"
            }
            return str
        }

    fun scalar32(name: String): Int {
        val attribute = Scalar32Attribute(name, last)
        elements.add(attribute)
        return attribute.alignOffset
    }

    fun scalar64(name: String): Int {
        val attribute = Scalar64Attribute(name, last)
        elements.add(attribute)
        return attribute.alignOffset
    }

    fun vec2Scalar32(name: String): Int {
        val attribute = Vec2Scalar32Attribute(name, last)
        elements.add(attribute)
        return attribute.alignOffset
    }

    fun vec3Scalar32(name: String): Int {
        val attribute = Vec3Scalar32Attribute(name, last)
        elements.add(attribute)
        return attribute.alignOffset
    }

    fun vec4Scalar32(name: String): Int {
        val attribute = Vec4Scalar32Attribute(name, last)
        elements.add(attribute)
        return attribute.alignOffset
    }

    fun vec2Scalar64(name: String): Int {
        val attribute = Vec2Scalar64Attribute(name, last)
        elements.add(attribute)
        return attribute.alignOffset
    }

    fun vec3Scalar64(name: String): Int {
        val attribute = Vec3Scalar64Attribute(name, last)
        elements.add(attribute)
        return attribute.alignOffset
    }

    fun vec4Scalar64(name: String): Int {
        val attribute = Vec4Scalar64Attribute(name, last)
        elements.add(attribute)
        return attribute.alignOffset
    }

    fun mat2Scalar32(name: String): Int {
        return struct(name, "Matrix2x2Scalar32") {
            vec2Scalar32("$name-1")
            vec2Scalar32("$name-2")
        }
    }

    fun mat3Scalar32(name: String): Int {
        return struct(name, "Matrix3x3Scalar32") {
            vec3Scalar32("$name-1")
            vec3Scalar32("$name-2")
            vec3Scalar32("$name-3")
        }
    }

    fun mat4Scalar32(name: String): Int {
        return struct(name, "Matrix4x4Scalar32") {
            vec4Scalar32("$name-1")
            vec4Scalar32("$name-2")
            vec4Scalar32("$name-3")
            vec4Scalar32("$name-4")
        }
    }

    fun mat2x3Scalar32(name: String): Int {
        return struct(name, "Matrix2x3Scalar32") {
            vec3Scalar32("$name-1")
            vec3Scalar32("$name-2")
        }
    }

    fun mat2x4Scalar32(name: String): Int {
        return struct(name, "Matrix2x4Scalar32") {
            vec4Scalar32("$name-1")
            vec4Scalar32("$name-2")
        }
    }

    fun mat3x2Scalar32(name: String): Int {
        return struct(name, "Matrix3x2Scalar32") {
            vec2Scalar32("$name-1")
            vec2Scalar32("$name-2")
            vec2Scalar32("$name-3")
        }
    }

    fun mat3x4Scalar32(name: String): Int {
        return struct(name, "Matrix3x4Scalar32") {
            vec4Scalar32("$name-1")
            vec4Scalar32("$name-2")
            vec4Scalar32("$name-3")
        }
    }

    fun mat4x2Scalar32(name: String): Int {
        return struct(name, "Matrix4x2Scalar32") {
            vec2Scalar32("$name-1")
            vec2Scalar32("$name-2")
            vec2Scalar32("$name-3")
            vec2Scalar32("$name-4")
        }
    }

    fun mat4x3Scalar32(name: String): Int {
        return struct(name, "Matrix4x3Scalar32") {
            vec3Scalar32("$name-1")
            vec3Scalar32("$name-2")
            vec3Scalar32("$name-3")
            vec3Scalar32("$name-4")
        }
    }

    fun mat2Scalar64(name: String): Int {
        return struct(name, "Matrix2x2Scalar64") {
            vec2Scalar64("$name-1")
            vec2Scalar64("$name-2")
        }
    }

    fun mat3Scalar64(name: String): Int {
        return struct(name, "Matrix3x3Scalar64") {
            vec3Scalar64("$name-1")
            vec3Scalar64("$name-2")
            vec3Scalar64("$name-3")
        }
    }

    fun mat4Scalar64(name: String): Int {
        return struct(name, "Matrix4x4Scalar64") {
            vec4Scalar64("$name-1")
            vec4Scalar64("$name-2")
            vec4Scalar64("$name-3")
            vec4Scalar64("$name-4")
        }
    }

    fun mat2x3Scalar64(name: String): Int {
        return struct(name, "Matrix2x3Scalar64") {
            vec3Scalar64("$name-1")
            vec3Scalar64("$name-2")
        }
    }

    fun mat2x4Scalar64(name: String): Int {
        return struct(name, "Matrix2x4Scalar64") {
            vec4Scalar64("$name-1")
            vec4Scalar64("$name-2")
        }
    }

    fun mat3x2Scalar64(name: String): Int {
        return struct(name, "Matrix3x2Scalar64") {
            vec2Scalar64("$name-1")
            vec2Scalar64("$name-2")
            vec2Scalar64("$name-3")
        }
    }

    fun mat3x4Scalar64(name: String): Int {
        return struct(name, "Matrix3x4Scalar64") {
            vec4Scalar64("$name-1")
            vec4Scalar64("$name-2")
            vec4Scalar64("$name-3")
        }
    }

    fun mat4x2Scalar64(name: String): Int {
        return struct(name, "Matrix4x2Scalar64") {
            vec2Scalar64("$name-1")
            vec2Scalar64("$name-2")
            vec2Scalar64("$name-3")
            vec2Scalar64("$name-4")
        }
    }

    fun mat4x3Scalar64(name: String): Int {
        return struct(name, "Matrix4x3Scalar64") {
            vec3Scalar64("$name-1")
            vec3Scalar64("$name-2")
            vec3Scalar64("$name-3")
            vec3Scalar64("$name-4")
        }
    }

    fun scalar32Array(name: String, size: Int): Int {
        return struct(name, "Scalar32Array") {
            for (index in 1..size) {
                elements.add(Scalar32ArrayMember("$name-$index", last))
            }
        }
    }

    fun scalar64Array(name: String, size: Int): Int {
        return struct(name, "Scalar64Array") {
            for (index in 1..size) {
                elements.add(Scalar32ArrayMember("$name-$index", last))
            }
        }
    }

    fun vec2Scalar32Array(name: String, size: Int): Int {
        return struct(name, "Vec2Scalar32Array") {
            for (index in 1..size) {
                elements.add(Vec2Scalar32ArrayMember("$name-$index", last))
            }
        }
    }

    fun vec3Scalar32Array(name: String, size: Int): Int {
        return struct(name, "Vec3Scalar32Array") {
            for (index in 1..size) {
                elements.add(Vec3Scalar32ArrayMember("$name-$index", last))
            }
        }
    }

    fun vec4Scalar32Array(name: String, size: Int): Int {
        return struct(name, "Vec4Scalar32Array") {
            for (index in 1..size) {
                elements.add(Vec4Scalar32ArrayMember("$name-$index", last))
            }
        }
    }

    fun vec2Scalar64Array(name: String, size: Int): Int {
        return struct(name, "Vec2Scalar64Array") {
            for (index in 1..size) {
                elements.add(Vec2Scalar32ArrayMember("$name-$index", last))
            }
        }
    }

    fun vec3Scalar64Array(name: String, size: Int): Int {
        return struct(name, "Vec3Scalar64Array") {
            for (index in 1..size) {
                elements.add(Vec3Scalar32ArrayMember("$name-$index", last))
            }
        }
    }

    fun vec4Scalar64Array(name: String, size: Int): Int {
        return struct(name, "Vec4Scalar64Array") {
            for (index in 1..size) {
                elements.add(Vec4Scalar32ArrayMember("$name-$index", last))
            }
        }
    }

    fun structArray(name: String, size: Int, builder: Struct.() -> Unit): Int {
        var startOffset = 0
        for (index in 1..size) {
            val alignOffset = struct(name, "StructArray-$index", builder)
            if (index == 1) startOffset = alignOffset
        }
        return startOffset
    }

    fun struct(name: String, type: String = "Struct", builder: Struct.() -> Unit): Int {
        val struct = build(name, type, last, builder)
        elements.add(struct)
        return struct.alignOffset
    }

    fun end() {
        elements.add(StructSPadding(name, last))
    }

    companion object {
        fun build(
            name: String = "default",
            type: String = "Struct",
            lastMember: Attribute? = null,
            builder: Struct.() -> Unit
        ): Struct {
            val struct = Struct(name, type, lastMember)
            struct.builder()
            struct.end()
            return struct
        }
    }
}

private fun Float.ceilToInt(): Int {
    return ceil(this).toInt()
}

open class BasicAttribute(
    final override val name: String,
    lastMember: Attribute,
    final override val type: String,
    final override val baseAlign: Int,
    final override val used: Int
) : Attribute {
    final override val baseOffset: Int = lastMember.let { it.alignOffset + it.used }
    final override val alignOffset: Int = (baseOffset / baseAlign.toFloat()).ceilToInt() * baseAlign
}

class Scalar32Attribute(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Scalar32", 4, 4)
class Scalar64Attribute(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Scalar64", 8, 8)
class Scalar32ArrayMember(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Scalar32Array", 16, 4)

class Vec2Scalar32Attribute(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Vec2Scalar32", 8, 8)
class Vec3Scalar32Attribute(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Vec3Scalar32", 16, 12)
class Vec4Scalar32Attribute(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Vec4Scalar32", 16, 16)

class Vec2Scalar64Attribute(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Vec2Scalar64", 16, 16)
class Vec3Scalar64Attribute(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Vec3Scalar64", 32, 24)
class Vec4Scalar64Attribute(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Vec4Scalar64", 32, 32)

class StructSPadding(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Padding", 16, 0)

class Vec2Scalar32ArrayMember(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Vec2Scalar32Array", 16, 8)
class Vec3Scalar32ArrayMember(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Vec3Scalar32Array", 16, 12)
class Vec4Scalar32ArrayMember(name: String, lastMember: Attribute) : BasicAttribute(name, lastMember, "Vec4Scalar32Array", 16, 16)