package dev.vibris.api

@JvmRecord
data class RuntimeStatus(
    val ready: Boolean,
    val currentSaveId: String,
    val currentDimensionId: String,
    val activeSourceUuid: String,
)