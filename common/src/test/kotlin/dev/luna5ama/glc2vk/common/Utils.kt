package dev.luna5ama.glc2vk.common

import dev.luna5ama.glwrapper.enums.ShaderStage
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

fun checkPatched(src: String, stage: ShaderStage) {
    val proc = ProcessBuilder()
        .command("I:\\VulkanSDK\\1.4.321.1\\Bin\\glslc.exe", "-x", "glsl", "--target-env=vulkan1.4", "-fshader-stage=${stage.shortName}", "-")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .start()

    proc.outputStream.writer().use { writer ->
        writer.write(src)
    }

    assertTrue(proc.waitFor(5L, TimeUnit.SECONDS), "glslc timed out")
    assertEquals(0, proc.exitValue())
}

fun readSrc(path: String): String {
    return ShaderStage::class.java.getResourceAsStream(path).reader().readText()
}

fun main() {
//    val src = readSrc("/begin2_c.csh")
    val src = Path("I:\\code\\gltest\\run\\resolveShaderSrc\\Draw.comp").readText()
    val patched = ShaderSourceContext(src).patchShaderForVulkan()
    Path("Draw.comp").writeText(patched)
    checkPatched(patched, ShaderStage.ComputeShader)
}