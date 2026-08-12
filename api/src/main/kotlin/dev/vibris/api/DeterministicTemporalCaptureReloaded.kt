package dev.vibris.api

@JvmRecord
data class DeterministicTemporalCaptureReloaded(
    val context: ContextApplyResult,
    val reload: ReloadResult,
    val reloadCompletedAtUnixMs: Long,
    val resourceCatalog: ResourceCatalog,
    val compileCatalog: CompileCatalog,
) {
    init {
        require(context.successful) { "A reloaded temporal phase requires an applied context" }
        require(reload.successful) { "A reloaded temporal phase requires a successful reload" }
        require(reloadCompletedAtUnixMs > 0) { "Reload completion time must be positive" }
    }
}