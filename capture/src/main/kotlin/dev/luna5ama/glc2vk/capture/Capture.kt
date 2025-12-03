package dev.luna5ama.glc2vk.capture

import dev.luna5ama.glwrapper.ShaderProgramResourceManager
import dev.luna5ama.glwrapper.base.GL_CURRENT_PROGRAM
import dev.luna5ama.glwrapper.base.glDispatchCompute
import dev.luna5ama.glwrapper.base.*

private fun captureShaderProgramResources(resourceManager: ShaderProgramResourceManager) {
    resourceManager
    println()
}

@Suppress("LocalVariableName")
fun captureGlDispatchCompute(
    num_groups_x: Int,
    num_groups_y: Int,
    num_groups_z: Int
) {
    val currProgram = glGetInteger(GL_CURRENT_PROGRAM)
    val resourceManager = ShaderProgramResourceManager(currProgram)

    captureShaderProgramResources(resourceManager)

    glDispatchCompute(num_groups_x, num_groups_y, num_groups_z)
}

@Suppress("LocalVariableName")
fun captureGlDispatchComputeIndirect(indirect: Long) {
    val currProgram = glGetInteger(GL_CURRENT_PROGRAM)
    val resourceManager = ShaderProgramResourceManager(currProgram)

    captureShaderProgramResources(resourceManager)

    glDispatchComputeIndirect(indirect)
}