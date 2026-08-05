package dev.luna5ama.vibris.capture

import dev.luna5ama.glwrapper.enums.GLSLDataType
import dev.luna5ama.glwrapper.enums.ImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphicsCaptureTest {
    @Test
    fun mergesVertexAndFragmentResourcesBeforeProgramCapture() {
        val vertexUniform = ShaderInfo.Uniform("modelViewMatrix", GLSLDataType.Mat4, 0, 0)
        val fragmentSampler = ShaderInfo.Uniform("colortex0", GLSLDataType["sampler2D"], 0, 1)
        val vertex = shaderInfo(
            stage = "vertex",
            uniforms = mapOf(vertexUniform.name to vertexUniform),
        )
        val fragment = shaderInfo(
            stage = "fragment",
            uniforms = mapOf(fragmentSampler.name to fragmentSampler),
        )

        val merged = listOf(vertex, fragment).mergeGraphicsResources()

        assertEquals(mapOf(vertexUniform.name to vertexUniform, fragmentSampler.name to fragmentSampler), merged.uniforms)
        assertEquals("vertex", merged.stage)
    }

    private fun shaderInfo(stage: String, uniforms: Map<String, ShaderInfo.Uniform>) = ShaderInfo(
        patchedSource = "void main() {}",
        uniforms = uniforms,
        ssbos = emptyMap(),
        ubos = emptyMap(),
        imageTypeOverrides = emptyMap<String, ImageFormat>(),
        stage = stage,
    )
}
