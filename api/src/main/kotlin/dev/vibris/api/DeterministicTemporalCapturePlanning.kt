package dev.vibris.api

sealed interface DeterministicTemporalCapturePlanning {
    @JvmRecord
    data class Planned(val plan: CapturePlan) : DeterministicTemporalCapturePlanning {
        init {
            require(plan.targets.isNotEmpty()) { "A deterministic temporal capture plan must not be empty" }
        }
    }

    @JvmRecord
    data class Rejected(
        val failure: DeterministicTemporalCaptureOutcome.Failure,
    ) : DeterministicTemporalCapturePlanning
}