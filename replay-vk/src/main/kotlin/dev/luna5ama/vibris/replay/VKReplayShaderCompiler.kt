package dev.luna5ama.vibris.replay

import dev.luna5ama.vibris.capture.ShaderSourceContext
import dev.luna5ama.vibris.capture.ShaderBindingAllocator
import dev.luna5ama.vibris.capture.validateCapturedBindings
import dev.luna5ama.vibris.common.CaptureData
import dev.luna5ama.vibris.common.Command
import dev.luna5ama.vibris.common.PassInfo
import dev.luna5ama.vibris.common.ShaderSourceResolver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.writeText

data class CompiledGraphicsShader(val stage: String, val path: Path)
data class CompiledGraphicsProgram(val shaders: List<CompiledGraphicsShader>, val resources: PassInfo)

class VKReplayShaderCompiler(
    private val captureData: CaptureData,
    private val captureDir: Path,
    shaderOverridePath: Path?,
    shaderPasses: Set<String> = emptySet()
) {
    private val resolver = ShaderSourceResolver(captureDir, shaderOverridePath, shaderPasses)
    private val tempDir = Files.createTempDirectory("vibris-replay-vk-shaders-")
    private val compiledShaders = mutableMapOf<Int, Path>()
    private val shaderInfos = mutableMapOf<Int, dev.luna5ama.vibris.capture.ShaderInfo>()

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
            compileShader(shaderIndex, "compute", shaderInfo.patchedSource)
        }
    }

    fun graphicsProgram(command: Command.GraphicsCommand): CompiledGraphicsProgram {
        val allocator = ShaderBindingAllocator()
        val infos = command.graphicsInfo.shaderIndices.map { shaderIndex ->
            val metadata = captureData.metadata.shaderMetadata(shaderIndex)
            val resolved = resolver.resolve(captureData.metadata, shaderIndex)
            shaderIndex to ShaderSourceContext(resolved.source, allocator)
                .setIdentity(metadata.passName, metadata.programType, metadata.sourcePath, metadata.stage)
                .also { it.patchShaderForVulkan() }
                .toShaderInfo()
                .also {
                    if (resolved.isOverride) it.validateCapturedBindings(command.graphicsInfo.resources)
                }
        }
        val stages = infos.map { it.second.stage }
        val clipStage = when {
            "geometry" in stages -> "geometry"
            "tese" in stages -> "tese"
            else -> "vertex"
        }
        val locations = GraphicsLocationAllocator(command.graphicsInfo.vertexAttributes)
        val preparedSources = infos.map { (_, shaderInfo) ->
            shaderInfo.stage to prepareVulkanGraphicsSource(
                shaderInfo.patchedSource,
                shaderInfo.stage,
                transformClipSpace = shaderInfo.stage == clipStage,
                locations = locations,
            )
        }
        val completedSources = completeGraphicsStageInterfaces(preparedSources)
        val shaders = infos.zip(completedSources).map { (indexedInfo, prepared) ->
            val (shaderIndex, shaderInfo) = indexedInfo
            val source = prepared.second
            CompiledGraphicsShader(shaderInfo.stage, compileShader(shaderIndex, shaderInfo.stage, source))
        }
        val resources = command.graphicsInfo.resources
        val capturedNames = resources.samplerBindings.mapTo(mutableSetOf()) { it.name }
        val fallbackSamplers = captureData.metadata.allSamplerBindings().associateBy { it.name }
        val missingSamplers = infos.asSequence()
            .flatMap { it.second.uniforms.values.asSequence() }
            .filter { it.name in fallbackSamplers && it.name !in capturedNames }
            .distinctBy { it.name }
            .mapNotNull { uniform ->
                fallbackSamplers[uniform.name]?.copy(set = uniform.set, binding = uniform.binding)
            }
            .toList()
        return CompiledGraphicsProgram(
            shaders,
            resources.copy(samplerBindings = resources.samplerBindings + missingSamplers),
        )
    }

    private fun compileShader(shaderIndex: Int, stage: String, source: String): Path {
        val extension = stageExtension(stage)
        val vkGlslPath = tempDir.resolve("shader_$shaderIndex.$extension.vk.glsl")
        val spvPath = tempDir.resolve("shader_$shaderIndex.$extension.spv")
        vkGlslPath.writeText(source)

        val exitCode = ProcessBuilder()
            .command(
                "glslang",
                "-DGLSLANG=1",
                "-gVS",
                "-Os",
                "-S",
                extension,
                "--auto-map-locations",
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

private fun stageExtension(stage: String): String = when (stage) {
    "vertex" -> "vert"
    "tesc" -> "tesc"
    "tese" -> "tese"
    "geometry" -> "geom"
    "fragment" -> "frag"
    else -> "comp"
}
