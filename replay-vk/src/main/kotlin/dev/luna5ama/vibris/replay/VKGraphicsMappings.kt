package dev.luna5ama.vibris.replay

import dev.luna5ama.vibris.common.VertexAttribute
import net.echonolix.caelum.vulkan.enums.VkFormat
import net.echonolix.caelum.vulkan.enums.VkBlendFactor
import net.echonolix.caelum.vulkan.enums.VkBlendOp
import net.echonolix.caelum.vulkan.enums.VkCompareOp
import net.echonolix.caelum.vulkan.enums.VkFrontFace
import net.echonolix.caelum.vulkan.enums.VkIndexType
import net.echonolix.caelum.vulkan.enums.VkPolygonMode
import net.echonolix.caelum.vulkan.enums.VkPrimitiveTopology

internal fun primitiveTopology(mode: Int): VkPrimitiveTopology = when (mode) {
    0x0000 -> VkPrimitiveTopology.POINT_LIST
    0x0001 -> VkPrimitiveTopology.LINE_LIST
    0x0003 -> VkPrimitiveTopology.LINE_STRIP
    0x0004 -> VkPrimitiveTopology.TRIANGLE_LIST
    0x0005 -> VkPrimitiveTopology.TRIANGLE_STRIP
    0x0006 -> VkPrimitiveTopology.TRIANGLE_FAN
    0x000E -> VkPrimitiveTopology.PATCH_LIST
    else -> throw UnsupportedOperationException("Unsupported captured GL primitive mode 0x${mode.toString(16)}")
}

internal fun indexType(type: Int): VkIndexType = when (type) {
    0x1401 -> VkIndexType.UINT8
    0x1403 -> VkIndexType.UINT16
    0x1405 -> VkIndexType.UINT32
    else -> throw UnsupportedOperationException("Unsupported captured GL index type 0x${type.toString(16)}")
}

internal fun compareOp(function: Int): VkCompareOp = when (function) {
    0x0200 -> VkCompareOp.NEVER
    0x0201 -> VkCompareOp.LESS
    0x0202 -> VkCompareOp.EQUAL
    0x0203 -> VkCompareOp.LESS_OR_EQUAL
    0x0204 -> VkCompareOp.GREATER
    0x0205 -> VkCompareOp.NOT_EQUAL
    0x0206 -> VkCompareOp.GREATER_OR_EQUAL
    0x0207 -> VkCompareOp.ALWAYS
    else -> throw UnsupportedOperationException("Unsupported captured GL compare function 0x${function.toString(16)}")
}

internal fun polygonMode(mode: Int): VkPolygonMode = when (mode) {
    0x1B00 -> VkPolygonMode.POINT
    0x1B01 -> VkPolygonMode.LINE
    0x1B02 -> VkPolygonMode.FILL
    else -> throw UnsupportedOperationException("Unsupported captured GL polygon mode 0x${mode.toString(16)}")
}

internal fun frontFace(mode: Int): VkFrontFace = when (mode) {
    0x0900 -> VkFrontFace.CLOCKWISE
    0x0901 -> VkFrontFace.COUNTER_CLOCKWISE
    else -> throw UnsupportedOperationException("Unsupported captured GL front face 0x${mode.toString(16)}")
}

internal fun blendFactor(factor: Int): VkBlendFactor = when (factor) {
    0 -> VkBlendFactor.ZERO
    1 -> VkBlendFactor.ONE
    0x0300 -> VkBlendFactor.SRC_COLOR
    0x0301 -> VkBlendFactor.ONE_MINUS_SRC_COLOR
    0x0302 -> VkBlendFactor.SRC_ALPHA
    0x0303 -> VkBlendFactor.ONE_MINUS_SRC_ALPHA
    0x0304 -> VkBlendFactor.DST_ALPHA
    0x0305 -> VkBlendFactor.ONE_MINUS_DST_ALPHA
    0x0306 -> VkBlendFactor.DST_COLOR
    0x0307 -> VkBlendFactor.ONE_MINUS_DST_COLOR
    0x0308 -> VkBlendFactor.SRC_ALPHA_SATURATE
    0x8001 -> VkBlendFactor.CONSTANT_COLOR
    0x8002 -> VkBlendFactor.ONE_MINUS_CONSTANT_COLOR
    0x8003 -> VkBlendFactor.CONSTANT_ALPHA
    0x8004 -> VkBlendFactor.ONE_MINUS_CONSTANT_ALPHA
    0x88F9 -> VkBlendFactor.SRC1_COLOR
    0x88FA -> VkBlendFactor.ONE_MINUS_SRC1_COLOR
    0x8589 -> VkBlendFactor.SRC1_ALPHA
    0x88FB -> VkBlendFactor.ONE_MINUS_SRC1_ALPHA
    else -> throw UnsupportedOperationException("Unsupported captured GL blend factor 0x${factor.toString(16)}")
}

internal fun blendOp(equation: Int): VkBlendOp = when (equation) {
    0x8006 -> VkBlendOp.ADD
    0x8007 -> VkBlendOp.MIN
    0x8008 -> VkBlendOp.MAX
    0x800A -> VkBlendOp.SUBTRACT
    0x800B -> VkBlendOp.REVERSE_SUBTRACT
    else -> throw UnsupportedOperationException("Unsupported captured GL blend equation 0x${equation.toString(16)}")
}

internal fun vertexFormat(attribute: VertexAttribute): VkFormat {
    require(attribute.size in 1..4) { "Unsupported vertex attribute size ${attribute.size}" }
    val index = attribute.size - 1
    return when (attribute.type) {
        0x1400 -> signedFormat(index, attribute.normalized, attribute.integer, 8)
        0x1401 -> unsignedFormat(index, attribute.normalized, attribute.integer, 8)
        0x1402 -> signedFormat(index, attribute.normalized, attribute.integer, 16)
        0x1403 -> unsignedFormat(index, attribute.normalized, attribute.integer, 16)
        0x1404 -> signedFormat(index, attribute.normalized, attribute.integer, 32)
        0x1405 -> unsignedFormat(index, attribute.normalized, attribute.integer, 32)
        0x1406 -> floatingFormat(index, 32, attribute)
        0x140A -> floatingFormat(index, 64, attribute)
        0x140B -> floatingFormat(index, 16, attribute)
        else -> throw UnsupportedOperationException(
            "Unsupported captured GL vertex attribute type 0x${attribute.type.toString(16)}",
        )
    }
}

private fun signedFormat(index: Int, normalized: Boolean, integer: Boolean, bits: Int): VkFormat {
    val kind = if (integer) "SINT" else if (normalized) "SNORM" else "SSCALED"
    return scalarVectorFormat(bits, index, kind)
}

private fun unsignedFormat(index: Int, normalized: Boolean, integer: Boolean, bits: Int): VkFormat {
    val kind = if (integer) "UINT" else if (normalized) "UNORM" else "USCALED"
    return scalarVectorFormat(bits, index, kind)
}

private fun floatingFormat(index: Int, bits: Int, attribute: VertexAttribute): VkFormat {
    require(!attribute.integer && !attribute.normalized) {
        "Floating-point vertex attribute cannot be integer or normalized"
    }
    return scalarVectorFormat(bits, index, "SFLOAT")
}

private fun scalarVectorFormat(bits: Int, index: Int, kind: String): VkFormat {
    val components = listOf("R", "R${bits}G", "R${bits}G${bits}B", "R${bits}G${bits}B${bits}A")[index]
    val name = "$components${bits}_$kind"
    return runCatching { VkFormat.valueOf(name) }.getOrElse {
        throw UnsupportedOperationException("Unsupported Vulkan vertex format $name", it)
    }
}

internal fun prepareVulkanGraphicsSource(
    source: String,
    stage: String,
    transformClipSpace: Boolean = stage == "vertex",
    locations: GraphicsLocationAllocator = GraphicsLocationAllocator(emptyList()),
): String {
    val version = Regex("(?m)^\\s*#version\\s+\\d+(?:\\s+\\w+)?")
    var prepared = version.replaceFirst(source, "#version 450 core")
    check(prepared.startsWith("#version 450 core")) { "Graphics shader has no #version directive" }
    if (stage == "vertex") {
        prepared = prepared
            .replace(Regex("\\bgl_VertexID\\b"), "gl_VertexIndex")
            .replace(Regex("\\bgl_InstanceID\\b"), "gl_InstanceIndex")
    }
    prepared = locations.patch(prepared, stage)
    if (!transformClipSpace) return prepared

    if (stage == "geometry") {
        prepared = Regex("\\bEmitVertex\\s*\\(\\s*\\)").replace(prepared, "vibrisEmitVertex()")
        prepared = prepared.replaceFirst("\n", "\nvoid vibrisEmitVertex();\n")
        return prepared + """

            void vibrisEmitVertex() {
                gl_Position.y = -gl_Position.y;
                gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
                EmitVertex();
            }
        """.trimIndent()
    }

    val main = Regex("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)")
    check(main.containsMatchIn(prepared)) { "Graphics shader has no main entry point" }
    prepared = main.replaceFirst(prepared, "void vibris_original_main()")
    return prepared + """

        void main() {
            vibris_original_main();
            gl_Position.y = -gl_Position.y;
            gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
        }
    """.trimIndent()
}

internal class GraphicsLocationAllocator(attributes: List<VertexAttribute>) {
    private val capturedLocations = attributes.mapTo(mutableSetOf(), VertexAttribute::location)
    private val namedAttributes = attributes.mapNotNull { attribute ->
        attribute.name?.let { it to attribute.location }
    }.toMap()
    private val usedVertexLocations = attributes.mapTo(mutableSetOf(), VertexAttribute::location)
    private var nextVertexLocation = 0
    private val varyings = mutableMapOf<String, Int>()
    private var nextVarying = 0
    private var nextFragmentOutput = 0

    fun patch(source: String, stage: String): String = INTERFACE_DECLARATION.replace(source) { match ->
        val layout = match.groups[2]?.value.orEmpty()
        val storage = match.groups[4]!!.value
        val name = match.groups[6]!!.value
        if (name.startsWith("gl_") || LOCATION.find(layout) != null) return@replace match.value
        if (stage == "vertex" && storage == "in" && identifierUseCount(source, name) == 1) {
            return@replace ""
        }

        val location = when {
            stage == "vertex" && storage == "in" -> namedAttributes[name]
                ?: IRIS_ATTRIBUTE_LOCATIONS[name]?.takeIf(capturedLocations::contains)
                ?: nextUnusedVertexLocation()
            stage == "fragment" && storage == "out" -> nextFragmentOutput++
            else -> varyings.getOrPut(name) { nextVarying++ }
        } ?: return@replace match.value

        val insertion = if (layout.isEmpty()) {
            "layout(location = $location) "
        } else {
            layout.replaceFirst("layout(", "layout(location = $location, ")
        }
        val indentation = match.groups[1]!!.value
        val declaration = match.value.removePrefix(indentation).removePrefix(layout)
        indentation + insertion + declaration
    }

    private fun nextUnusedVertexLocation(): Int {
        while (nextVertexLocation in usedVertexLocations) nextVertexLocation++
        return nextVertexLocation.also {
            usedVertexLocations += it
            nextVertexLocation++
        }
    }

    private fun identifierUseCount(source: String, name: String): Int =
        Regex("\\b${Regex.escape(name)}\\b").findAll(source).count()

    private companion object {
        val IRIS_ATTRIBUTE_LOCATIONS = mapOf(
            "Position" to 0,
            "UV0" to 1,
            "a_Position" to 0,
            "a_Color" to 1,
            "a_TexCoord" to 2,
            "a_LightAndData" to 3,
            "iris_Normal" to 10,
            "iris_Entity" to 11,
            "mc_Entity" to 11,
            "mc_midTexCoord" to 12,
            "at_tangent" to 13,
            "at_midBlock" to 14,
        )
        val LOCATION = Regex("\\blocation\\s*=")
        val INTERFACE_DECLARATION = Regex(
            "(?m)^(\\s*)(layout\\s*\\([^)]*\\)\\s*)?" +
                "((?:(?:flat|smooth|noperspective|centroid|sample|patch|invariant|precise)\\s+)*)" +
                "(in|out)\\s+([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)(\\s*\\[[^;]*])?\\s*;",
        )
    }
}

internal fun completeGraphicsStageInterfaces(stages: List<Pair<String, String>>): List<Pair<String, String>> {
    val completed = stages.toMutableList()
    for (index in 0 until completed.lastIndex) {
        val outputs = INTERFACE_WITH_LOCATION.findAll(completed[index].second)
            .filter { it.groupValues[3] == "out" }
            .associateBy { it.groupValues[1].toInt() }
        val missing = INTERFACE_WITH_LOCATION.findAll(completed[index + 1].second)
            .filter { it.groupValues[3] == "in" }
            .filter { it.groupValues[1].toInt() !in outputs }
            .toList()
        if (missing.isEmpty()) continue

        val declarations = missing.joinToString(separator = "\n", prefix = "\n") { match ->
            val location = match.groupValues[1]
            val qualifiers = match.groupValues[2]
            val type = match.groupValues[4]
            "layout(location = $location) ${qualifiers}out $type vibris_missing_output_$location;"
        }
        completed[index] = completed[index].first to (completed[index].second + declarations)
    }
    return completed
}

private val INTERFACE_WITH_LOCATION = Regex(
    "(?m)^\\s*layout\\s*\\(\\s*location\\s*=\\s*(\\d+)[^)]*\\)\\s*" +
        "((?:(?:flat|smooth|noperspective|centroid|sample|patch|invariant|precise)\\s+)*)" +
        "(in|out)\\s+([A-Za-z_]\\w*)\\s+[A-Za-z_]\\w*(?:\\s*\\[[^;]*])?\\s*;",
)
