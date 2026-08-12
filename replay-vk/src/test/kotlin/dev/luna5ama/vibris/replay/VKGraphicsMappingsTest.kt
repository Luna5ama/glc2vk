package dev.luna5ama.vibris.replay

import dev.luna5ama.vibris.common.VertexAttribute
import net.echonolix.caelum.vulkan.enums.VkFormat
import net.echonolix.caelum.vulkan.enums.VkIndexType
import net.echonolix.caelum.vulkan.enums.VkPrimitiveTopology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VKGraphicsMappingsTest {
    @Test
    fun mapsFormatsUsedByIrisTerrainAndFullscreenDraws() {
        assertEquals(VkFormat.R32G32_UINT, vertexFormat(attribute(size = 2, type = 0x1405, integer = true)))
        assertEquals(
            VkFormat.R8G8B8A8_UNORM,
            vertexFormat(attribute(size = 4, type = 0x1401, normalized = true)),
        )
        assertEquals(VkFormat.R16G16_UINT, vertexFormat(attribute(size = 2, type = 0x1403, integer = true)))
        assertEquals(VkFormat.R8G8B8A8_SSCALED, vertexFormat(attribute(size = 4, type = 0x1400)))
        assertEquals(VkFormat.R32G32B32_SFLOAT, vertexFormat(attribute(size = 3, type = 0x1406)))
        assertEquals(VkFormat.R32G32_SFLOAT, vertexFormat(attribute(size = 2, type = 0x1406)))
    }

    @Test
    fun mapsCapturedDrawAndIndexModesWithoutSilentFallbacks() {
        assertEquals(VkPrimitiveTopology.TRIANGLE_LIST, primitiveTopology(0x0004))
        assertEquals(VkIndexType.UINT16, indexType(0x1403))
        assertEquals(VkIndexType.UINT32, indexType(0x1405))
        assertFailsWith<UnsupportedOperationException> { primitiveTopology(0x0002) }
        assertFailsWith<UnsupportedOperationException> { indexType(0x1406) }
    }

    @Test
    fun upgradesGraphicsSourceForVulkanAndPreservesLineDirectiveOrdering() {
        val prepared = prepareVulkanGraphicsSource(
            """
                #version 330 core
                layout(set = 2, binding = 0) uniform Example { mat4 matrix; };
                void main() { gl_Position = matrix * vec4(0.0, 0.0, 0.0, 1.0); }
            """.trimIndent(),
            "vertex",
        )

        assertTrue(prepared.startsWith("#version 450 core"))
        assertTrue(prepared.contains("void vibris_original_main()"))
        assertTrue(prepared.contains("gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;"))
    }

    @Test
    fun synthesizesMissingIntermediateStageOutputsForVulkanInterfaces() {
        val linked = completeGraphicsStageInterfaces(
            listOf(
                "geometry" to "#version 450 core\nvoid main() {}",
                "fragment" to "#version 450 core\nlayout(location = 3) flat in uint materialId;\nvoid main() {}",
            ),
        )

        assertTrue(linked[0].second.contains("layout(location = 3) flat out uint vibris_missing_output_3;"))
    }

    @Test
    fun preservesIrisBindingsForAttributesOptimizedOutOfOpenGlReflection() {
        val attributes = listOf(
            attribute(location = 0, name = "a_Position", size = 2, type = 0x1405, integer = true),
            attribute(location = 1, name = "a_Color", size = 4, type = 0x1401, normalized = true),
            attribute(location = 2, name = "a_TexCoord", size = 2, type = 0x1403, integer = true),
            attribute(location = 14, name = "at_tangent", size = 4, type = 0x1400),
        )
        val prepared = prepareVulkanGraphicsSource(
            """
                #version 330 core
                in vec4 a_Color;
                in uvec2 a_TexCoord;
                void main() { gl_Position = a_Color + vec4(a_TexCoord, 0.0, 0.0); }
            """.trimIndent(),
            "vertex",
            locations = GraphicsLocationAllocator(attributes),
        )

        assertTrue(prepared.contains("layout(location = 1) in vec4 a_Color;"))
        assertTrue(prepared.contains("layout(location = 2) in uvec2 a_TexCoord;"))
    }

    @Test
    fun dropsUnusedVertexInputsThatOpenGlDoesNotRequireFromTheVao() {
        val prepared = prepareVulkanGraphicsSource(
            """
                #version 330 core
                in vec4 at_tangent;
                void main() { gl_Position = vec4(0.0); }
            """.trimIndent(),
            "vertex",
            locations = GraphicsLocationAllocator(emptyList()),
        )

        assertFalse(prepared.contains("at_tangent"))
    }

    @Test
    fun preservesMinecraftFullscreenUvBindingWhenItIsLinkOptimized() {
        val prepared = prepareVulkanGraphicsSource(
            """
                #version 330 core
                in vec3 Position;
                in vec2 UV0;
                void main() { gl_Position = vec4(Position, 1.0) + vec4(UV0, 0.0, 0.0); }
            """.trimIndent(),
            "vertex",
            locations = GraphicsLocationAllocator(
                listOf(
                    attribute(location = 0, name = "Position", size = 3, type = 0x1406),
                    attribute(location = 1, name = "UV0", size = 2, type = 0x1406),
                ),
            ),
        )

        assertTrue(prepared.contains("layout(location = 1) in vec2 UV0;"))
    }

    private fun attribute(
        location: Int = 0,
        name: String? = "attribute",
        size: Int,
        type: Int,
        normalized: Boolean = false,
        integer: Boolean = false,
    ) = VertexAttribute(
        location = location,
        name = name,
        bufferIndex = 0,
        size = size,
        type = type,
        normalized = normalized,
        integer = integer,
        long = false,
        stride = 16,
        offset = 0,
        divisor = 0,
    )
}
