package dev.luna5ama.vibris.replay

import dev.luna5ama.glwrapper.base.GL_COMPILE_STATUS
import dev.luna5ama.glwrapper.base.GL_COMPUTE_SHADER
import dev.luna5ama.glwrapper.base.GL_FRAGMENT_SHADER
import dev.luna5ama.glwrapper.base.GL_GEOMETRY_SHADER
import dev.luna5ama.glwrapper.base.GL_TESS_CONTROL_SHADER
import dev.luna5ama.glwrapper.base.GL_TESS_EVALUATION_SHADER
import dev.luna5ama.glwrapper.base.GL_VERTEX_SHADER
import dev.luna5ama.vibris.common.ResolvedShaderSource
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

fun loadOpenGLGraphicsProgram(sources: List<Pair<String, ResolvedShaderSource>>): Int {
    require(sources.any { it.first == "vertex" } && sources.any { it.first == "fragment" })
    val shaders = sources.map { (stage, resolved) ->
        val shader = glCreateShader(stage.toGlShaderType())
        glShaderSource(shader, resolved.source)
        glCompileShader(shader)
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            val log = glGetShaderInfoLog(shader, glGetShaderi(shader, GL_INFO_LOG_LENGTH))
            glDeleteShader(shader)
            val dumpPath = dumpFailedShaderSource(resolved.source)
            error("Failed to compile OpenGL replay $stage shader ${resolved.path}\n$log\nSource dumped to $dumpPath")
        }
        shader
    }
    val program = glCreateProgram()
    shaders.forEach { glAttachShader(program, it) }
    glLinkProgram(program)
    if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
        val log = glGetProgramInfoLog(program, glGetProgrami(program, GL_INFO_LOG_LENGTH))
        shaders.forEach {
            glDetachShader(program, it)
            glDeleteShader(it)
        }
        glDeleteProgram(program)
        error("Failed to link OpenGL replay graphics program\n$log")
    }
    shaders.forEach {
        glDetachShader(program, it)
        glDeleteShader(it)
    }
    return program
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

private fun String.toGlShaderType(): Int = when (this) {
    "vertex" -> GL_VERTEX_SHADER
    "tesc" -> GL_TESS_CONTROL_SHADER
    "tese" -> GL_TESS_EVALUATION_SHADER
    "geometry" -> GL_GEOMETRY_SHADER
    "fragment" -> GL_FRAGMENT_SHADER
    else -> error("Unsupported graphics shader stage: $this")
}
