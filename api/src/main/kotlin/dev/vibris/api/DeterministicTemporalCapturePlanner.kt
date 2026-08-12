package dev.vibris.api

fun interface DeterministicTemporalCapturePlanner {
    fun plan(
        resourceCatalog: ResourceCatalog,
        compileCatalog: CompileCatalog,
    ): DeterministicTemporalCapturePlanning
}