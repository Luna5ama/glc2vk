package dev.luna5ama.glc2vk.common

import dev.luna5ama.glwrapper.enums.GLSLDataType

private const val INDENT = "\t"

private val LINE_COMMENT_REGEX = """//.*?$""".toRegex(RegexOption.MULTILINE)
private val BLOCK_COMMENT_REGEX = """/\*.*?\*/""".toRegex(RegexOption.DOT_MATCHES_ALL)

private val TOKEN_DELIMITER_REGEX = """\s+|(?=[{}()\[\];,.])|(?<=[{}()\[\];,.])""".toRegex()
private val NON_TOKEN_REGEX = """[\w_]+""".toRegex()
private val UNIFORM_REGEX =
    """^((?:layout\(.+?\))?)\s*((?:${NON_TOKEN_REGEX.pattern}\s+)*?)uniform\s+((?:${NON_TOKEN_REGEX.pattern}\s+)*?)(\S+)\s+(\S+)\s*;\s*""".toRegex(
        RegexOption.MULTILINE
    )
private val SSBO_REGEX =
    """^((?:layout\(.+?\))?)\s*((?:${NON_TOKEN_REGEX.pattern}\s+)*?)buffer\s+((?:${NON_TOKEN_REGEX.pattern}\s+)*?)(\S+)\s*\{""".toRegex(
        RegexOption.MULTILINE
    )
private val CONST_REGEX =
    """^const\s+(\S+)\s+(\S+)\s*=\s*(.+?)\s*;\s*""".toRegex(
        RegexOption.MULTILINE
    )

private fun ShaderSourceContext.removeComments() {
    modifiedSource = LINE_COMMENT_REGEX.replace(modifiedSource, "")
    modifiedSource = BLOCK_COMMENT_REGEX.replace(modifiedSource, "")
}

private inline fun String.transformLines(block: (List<String>) -> List<String>): String {
    val lines = this.lines()
    val newLines = block(lines)
    return newLines.joinToString("\n")
}

class ShaderSourceContext(val originalSource: String) {
    var modifiedSource: String = originalSource

    val uniforms = mutableMapOf<String, Uniform>()
    val ssbos = mutableMapOf<String, SSBO>()

    // set 0 = value uniforms
    // set 1 = sampler/image uniforms
    // set 2 = storage buffers
    val bindingCounters = intArrayOf(1, 0, 0)

    val tokenCounts = modifiedSource.split(TOKEN_DELIMITER_REGEX)
        .groupingBy { it }
        .eachCount()

    fun checkUsage(name: String): Boolean {
        return tokenCounts.getOrDefault(name, 0) > 1
    }

    data class Uniform(val name: String, val type: GLSLDataType, val set: Int, val binding: Int)
    data class SSBO(val name: String, val set: Int, val binding: Int)
}

private fun ShaderSourceContext.patchSSBO() {
    modifiedSource = SSBO_REGEX.replace(modifiedSource) {
        val (_, modifiers1, modifiers2, name) = it.destructured
        val set = 2
        val binding = bindingCounters[set]++
        val ssbo = ShaderSourceContext.SSBO(name, set, binding)
        ssbos[name] = ssbo
        buildString {
            append("layout(std430, set = ")
            append(set)
            append(", binding = ")
            append(binding)
            append(") ")
            append(modifiers1)
            append("buffer ")
            append(modifiers2)
            append(name)
            append(" {")
        }
    }
}
private fun ShaderSourceContext.patchUniforms() {
    modifiedSource = UNIFORM_REGEX.replace(modifiedSource) {
        val (layout, modifiers1, modifiers2, typeStr, name) = it.destructured

        if (!checkUsage(name)) {
            println("Removing unused uniform: $name")
            // Not used, remove
            return@replace ""
        }

        val type = GLSLDataType[typeStr]


        when (type) {
            is GLSLDataType.Value -> {
                // Have to put them into a uniform block
                val set = 0
                val uniform = ShaderSourceContext.Uniform(name, type, set, -1)
                uniforms[name] = uniform
                ""
            }

            is GLSLDataType.Opaque -> {
                val set = 1
                val binding = bindingCounters[set]++
                val uniform = ShaderSourceContext.Uniform(name, type, set, binding)
                uniforms[name] = uniform

                buildString {
                    if (layout.isNotEmpty()) {
                        append(layout.subSequence(0, layout.length - 1))
                        append(", ")
                    } else {
                        append("layout(")
                    }
                    append("set = ")
                    append(set)
                    append(", binding = ")
                    append(binding)
                    append(") ")

                    append(modifiers1)
                    append("uniform ")
                    append(modifiers2)
                    append(typeStr)
                    append(" ")
                    append(name)
                    append(";\n")
                }
            }
        }
    }
    val nonOpaqueUniforms = uniforms.values.filter { it.type is GLSLDataType.Value }
    if (nonOpaqueUniforms.isNotEmpty()) {
        modifiedSource = modifiedSource.transformLines { ogLines ->
            check(ogLines[0].startsWith("#version"))

            val newLines = mutableListOf<String>()
            newLines.add(ogLines[0].trim())
            newLines.add("")
            newLines.add(buildString {
                append("layout(std140, set = 0, binding = 0) uniform DefaultUniforms {\n")
                nonOpaqueUniforms.forEach {
                    append(INDENT)
                    append(it.type.codeStr)
                    append(" ")
                    append(it.name)
                    append(";\n")
                }
                append("};")
            })
            newLines.addAll(ogLines.subList(1, ogLines.size))

            newLines
        }
    }
}

private fun ShaderSourceContext.removeUnusedConsts() {
    modifiedSource = CONST_REGEX.replace(modifiedSource) {
        val (_, name, _) = it.destructured

        if (!checkUsage(name)) {
            // Not used, remove
            return@replace ""
        }

        it.value
    }
}

fun ShaderSourceContext.patchShaderForVulkan(): String {
    // Remove surrounding whitespace
    modifiedSource = modifiedSource.trim()

    // Remove comments
    removeComments()
    removeUnusedConsts()
    patchSSBO()
    patchUniforms()

    return modifiedSource.replace("\n", System.lineSeparator())
}