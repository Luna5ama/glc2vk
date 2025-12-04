package dev.luna5ama.glc2vk.common

import dev.luna5ama.glwrapper.enums.ShaderStage
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.Test

class PatchTest {
    @Test
    fun test_composite99_a() {
        val src = readSrc("/begin2_c.csh")
        val patched = ShaderSourceContext(src).patchShaderForVulkan()
        checkPatched(patched, ShaderStage.ComputeShader)
    }

    @Test
    fun test_begin2_c() {
        val src = readSrc("/begin2_c.csh")
        val patched = ShaderSourceContext(src).patchShaderForVulkan()
        checkPatched(patched, ShaderStage.ComputeShader)
    }
}