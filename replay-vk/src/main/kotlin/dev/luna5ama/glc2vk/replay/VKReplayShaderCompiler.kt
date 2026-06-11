package dev.luna5ama.glc2vk.replay

import dev.luna5ama.glc2vk.capture.ShaderSourceContext
import dev.luna5ama.glc2vk.capture.validateCapturedBindings
import dev.luna5ama.glc2vk.common.CaptureData
import dev.luna5ama.glc2vk.common.Command
import dev.luna5ama.glc2vk.common.ShaderSourceResolver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.writeText

class VKReplayShaderCompiler(
    private val captureData: CaptureData,
    private val captureDir: Path,
    shaderOverridePath: Path?,
    shaderPasses: Set<String> = emptySet()
) {
    private val resolver = ShaderSourceResolver(captureDir, shaderOverridePath, shaderPasses)
    private val tempDir = Files.createTempDirectory("glc2vk-replay-vk-shaders-")
    private val compiledShaders = mutableMapOf<Int, Path>()
    private val shaderInfos = mutableMapOf<Int, dev.luna5ama.glc2vk.capture.ShaderInfo>()

    fun shaderPath(command: Command.PassCommand): Path {
        val shaderIndex = command.passInfo.shaderIndex
        val capturedSpv = captureDir.resolve("shader_$shaderIndex.comp.spv").takeIf { it.exists() }
            ?: captureDir.resolve("shader.comp.spv").takeIf { it.exists() }

        val shaderInfo = shaderInfos.getOrPut(shaderIndex) {
            val shaderMetadata = captureData.metadata.shaderMetadata(shaderIndex)
            val resolved = resolver.resolve(captureData.metadata, shaderIndex)
            if (!resolved.isOverride && capturedSpv != null) {
                return capturedSpv
            }
            ShaderSourceContext(resolved.source)
                .setIdentity(
                    passName = shaderMetadata.passName,
                    programType = shaderMetadata.programType,
                    sourcePath = shaderMetadata.sourcePath,
                    stage = shaderMetadata.stage
                ).also {
                    it.patchShaderForVulkan()
                }.toShaderInfo()
        }
        shaderInfo.validateCapturedBindings(command.passInfo)

        return compiledShaders.getOrPut(shaderIndex) {
            compileShader(shaderIndex, shaderInfo.patchedSource)
        }
    }

    private fun compileShader(shaderIndex: Int, source: String): Path {
        val vkGlslPath = tempDir.resolve("shader_$shaderIndex.comp.vk.glsl")
        val spvPath = tempDir.resolve("shader_$shaderIndex.comp.spv")
        vkGlslPath.writeText(source)

        val exitCode = ProcessBuilder()
            .command(
                "glslang",
                "-DGLSLANG=1",
                "-gVS",
                "-S",
                "comp",
                "--target-env",
                "vulkan1.3",
                "-o",
                spvPath.absolutePathString(),
                vkGlslPath.absolutePathString()
            )
            .inheritIO()
            .start()
            .waitFor()
        check(exitCode == 0) { "glslang failed with exit code $exitCode for $vkGlslPath" }
        return spvPath
    }
}
