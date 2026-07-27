package dev.luna5ama.vibris.replay

import dev.luna5ama.glwrapper.base.GL_COMPILE_STATUS
import dev.luna5ama.glwrapper.base.GL_COMPUTE_SHADER
import dev.luna5ama.glwrapper.base.GL_INFO_LOG_LENGTH
import dev.luna5ama.glwrapper.base.GL_LINK_STATUS
import dev.luna5ama.glwrapper.base.glAttachShader
import dev.luna5ama.glwrapper.base.glCompileShader
import dev.luna5ama.glwrapper.base.glCreateProgram
import dev.luna5ama.glwrapper.base.glCreateShader
import dev.luna5ama.glwrapper.base.glDeleteProgram
import dev.luna5ama.glwrapper.base.glDeleteShader
import dev.luna5ama.glwrapper.base.glDetachShader
import dev.luna5ama.glwrapper.base.glGetProgramInfoLog
import dev.luna5ama.glwrapper.base.glGetProgrami
import dev.luna5ama.glwrapper.base.glGetShaderInfoLog
import dev.luna5ama.glwrapper.base.glGetShaderi
import dev.luna5ama.glwrapper.base.glLinkProgram
import dev.luna5ama.glwrapper.base.glShaderSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val leadingSetRegex = Regex("""\bset\s*=\s*\d+\s*,\s*""")
private val trailingSetRegex = Regex("""\s*,\s*set\s*=\s*\d+""")

fun loadOpenGLComputeProgram(shaderPath: Path): Int {
    val source = shaderPath.readText().normalizeVulkanGlslForOpenGL()
    return compileComputeProgram(source, shaderPath)
}

fun loadOpenGLComputeProgram(source: String, sourcePath: Path): Int {
    return compileComputeProgram(source.normalizeVulkanGlslForOpenGL(), sourcePath)
}

fun String.normalizeVulkanGlslForOpenGL(): String {
    val lines = lineSequence().map { line ->
            if ("layout(" !in line || "set" !in line) {
                line
            } else {
                line.replace(leadingSetRegex, "")
                    .replace(trailingSetRegex, "")
            }
        }
        .toMutableList()

    val versionIndex = lines.indexOfFirst { it.trimStart().startsWith("#version") }
    if (versionIndex >= 0) {
        val extensionLines = lines.filter { it.trimStart().startsWith("#extension") }
        if (extensionLines.isNotEmpty()) {
            lines.removeAll(extensionLines.toSet())
            lines.addAll(versionIndex + 1, extensionLines)
        }
    }

    return lines
        .joinToString(System.lineSeparator())
}

private fun compileComputeProgram(source: String, sourcePath: Path): Int {
    val shader = glCreateShader(GL_COMPUTE_SHADER)
    glShaderSource(shader, source)
    glCompileShader(shader)

    if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
        val log = glGetShaderInfoLog(shader, glGetShaderi(shader, GL_INFO_LOG_LENGTH))
        glDeleteShader(shader)
        val dumpPath = dumpFailedShaderSource(source)
        error("Failed to compile OpenGL replay compute shader $sourcePath\n$log\nNormalized source dumped to $dumpPath")
    }

    val program = glCreateProgram()
    glAttachShader(program, shader)
    glLinkProgram(program)

    if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
        val log = glGetProgramInfoLog(program, glGetProgrami(program, GL_INFO_LOG_LENGTH))
        glDetachShader(program, shader)
        glDeleteShader(shader)
        glDeleteProgram(program)
        val dumpPath = dumpFailedShaderSource(source)
        error("Failed to link OpenGL replay compute shader $sourcePath\n$log\nNormalized source dumped to $dumpPath")
    }

    glDetachShader(program, shader)
    glDeleteShader(shader)
    return program
}

private fun dumpFailedShaderSource(source: String): Path {
    val path = Files.createTempFile("vibris-replay-gl-", ".comp.glsl")
    path.writeText(source)
    return path
}
