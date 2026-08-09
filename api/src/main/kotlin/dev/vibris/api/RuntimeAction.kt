package dev.vibris.api

sealed interface RuntimeAction {
    data object CaptureStatus : RuntimeAction
    data class CapturePass(val pass: String, val path: String?) : RuntimeAction
    data class CaptureMulti(val type: String, val path: String?) : RuntimeAction
    data object InspectShader : RuntimeAction
    data class GpuMetrics(val frames: Int) : RuntimeAction
    data object ListSsbos : RuntimeAction
    data class DumpSsbo(val index: Int) : RuntimeAction
    data object ListTextures : RuntimeAction
    data class DumpTexture(val name: String?, val id: Int?, val raw: Boolean) : RuntimeAction
    data object ListPatchedShaders : RuntimeAction
}
