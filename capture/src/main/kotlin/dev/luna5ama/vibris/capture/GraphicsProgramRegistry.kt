package dev.luna5ama.vibris.capture

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class GraphicsProgramInfo(
    val passName: String,
    val programType: String?,
    val sources: Map<String, String>,
)

object GraphicsProgramRegistry {
    private val programs = ConcurrentHashMap<Int, GraphicsProgramInfo>()

    @JvmStatic
    fun register(
        program: Int,
        passName: String,
        vertex: String?,
        tessControl: String?,
        tessEvaluation: String?,
        geometry: String?,
        fragment: String?,
    ) {
        require(program > 0) { "Program ID must be positive" }
        val sources = buildMap {
            vertex?.let { put("vertex", it) }
            tessControl?.let { put("tesc", it) }
            tessEvaluation?.let { put("tese", it) }
            geometry?.let { put("geometry", it) }
            fragment?.let { put("fragment", it) }
        }
        require("vertex" in sources && "fragment" in sources) {
            "Graphics programs require vertex and fragment stages"
        }
        programs[program] = GraphicsProgramInfo(passName, classify(passName), sources)
    }

    @JvmStatic
    fun unregister(program: Int) {
        programs.remove(program)
    }

    fun find(program: Int): GraphicsProgramInfo? = programs[program]

    private fun classify(passName: String): String? {
        val normalized = passName.lowercase(Locale.ROOT)
        return listOf("prepare", "begin", "deferred", "composite").firstOrNull {
            normalized.matches(Regex("${Regex.escape(it)}([1-9][0-9]?)?(_[a-z])?"))
        }
    }
}
