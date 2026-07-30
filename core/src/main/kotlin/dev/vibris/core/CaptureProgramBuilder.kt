package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v1.ErrorCode
import java.util.Locale

internal class CaptureProgramBuilder(private val maxActions: Int = DEFAULT_MAX_ACTIONS) {
    private val expandedActionLimit = maxActions.toLong() * 2 + 8

    init {
        require(maxActions > 0) { "maxActions must be positive" }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun actions(job: CoreJob, catalog: ResourceCatalog): ActionProgram {
        if (job.submission.actions.actionsCount.toLong() > expandedActionLimit) throw invalid("Action limit exceeded.")
        val steps = ArrayList<ActionStep>()
        val group = ArrayList<CapturePlan.Target>()
        val artifactNames = HashSet<String>()
        var estimatedBytes = 0L
        var captureCount = 0
        var comparisons = 0
        for (action in job.submission.actions.actionsList) {
            when {
                action.hasActivateSource() -> {
                    estimatedBytes = flush(group, steps, catalog, artifactNames, estimatedBytes)
                    val uuid = action.activateSource.sourceUuid
                    if (uuid.isBlank()) throw invalid("Source UUID is missing.")
                    steps.add(ActionStep.activate(uuid))
                }
                action.hasResetTemporalState() -> {
                    estimatedBytes = flush(group, steps, catalog, artifactNames, estimatedBytes)
                    steps.add(ActionStep.reset())
                }
                action.hasWaitFrames() -> {
                    estimatedBytes = flush(group, steps, catalog, artifactNames, estimatedBytes)
                    if (action.waitFrames.frameCount <= 0) throw invalid("Frame count must be positive.")
                    steps.add(ActionStep.waitFrames(action.waitFrames.frameCount))
                }
                action.hasCaptureScreenshot() || action.hasDumpTexture() || action.hasDumpBuffer() ->
                    CapturePlanBuilder.addAction(group, action, catalog)
                action.hasCompareCaptures() -> {
                    estimatedBytes = flush(group, steps, catalog, artifactNames, estimatedBytes)
                    captureCount = steps.count { it.type == ActionType.CAPTURE }
                    val compare = action.compareCaptures
                    if (
                        comparisons++ != 0 || compare.baselineCaptureIndex >= captureCount ||
                        compare.candidateCaptureIndex >= captureCount ||
                        compare.baselineCaptureIndex == compare.candidateCaptureIndex ||
                        compare.baselineLabel.isBlank() || compare.candidateLabel.isBlank()
                    ) {
                        throw invalid("Capture comparison is invalid.")
                    }
                    steps.add(
                        ActionStep.compare(
                            Comparison(
                                compare.baselineCaptureIndex,
                                compare.candidateCaptureIndex,
                                compare.baselineLabel,
                                compare.candidateLabel,
                            ),
                        ),
                    )
                }
                else -> throw invalid("Action is not supported.")
            }
        }
        estimatedBytes = flush(group, steps, catalog, artifactNames, estimatedBytes)
        if (steps.firstOrNull()?.type != ActionType.ACTIVATE) {
            throw invalid("Action sequence must start by activating a prepared source.")
        }
        return ActionProgram(java.util.List.copyOf(steps), estimatedBytes)
    }

    enum class ActionType {
        ACTIVATE,
        RESET,
        WAIT,
        CAPTURE,
        COMPARE,
    }

    @JvmRecord
    data class ActionStep(
        val type: ActionType,
        val sourceUuid: String?,
        val frames: Int,
        val capture: CapturePlan?,
        val comparison: Comparison?,
    ) {
        companion object {
            fun activate(uuid: String) = ActionStep(ActionType.ACTIVATE, uuid, 0, null, null)
            fun reset() = ActionStep(ActionType.RESET, null, 0, null, null)
            fun waitFrames(frames: Int) = ActionStep(ActionType.WAIT, null, frames, null, null)
            fun capture(capture: CapturePlan) = ActionStep(ActionType.CAPTURE, null, 0, capture, null)
            fun compare(comparison: Comparison) = ActionStep(ActionType.COMPARE, null, 0, null, comparison)
        }
    }

    @JvmRecord
    data class Comparison(
        val baselineCaptureIndex: Int,
        val candidateCaptureIndex: Int,
        val baselineLabel: String,
        val candidateLabel: String,
    )

    @JvmRecord
    data class ActionProgram(val steps: List<ActionStep>, val estimatedBytes: Long)

    companion object {
        private const val DEFAULT_MAX_ACTIONS = 64

        @Throws(RuntimeJobExecutor.Failure::class)
        private fun flush(
            group: MutableList<CapturePlan.Target>,
            steps: MutableList<ActionStep>,
            catalog: ResourceCatalog,
            artifactNames: MutableSet<String>,
            estimatedBytes: Long,
        ): Long {
            if (group.isEmpty()) return estimatedBytes
            val planned = CapturePlanBuilder.plan(java.util.List.copyOf(group), catalog)
            for (target in group) {
                requireUnique(artifactNames, target.fileName())
                if (
                    target.format == CapturePlan.ArtifactFormat.RAW ||
                    target.format == CapturePlan.ArtifactFormat.BIN
                ) {
                    requireUnique(artifactNames, target.metadataFileName())
                }
            }
            group.clear()
            steps.add(ActionStep.capture(planned.capture))
            return try {
                Math.addExact(estimatedBytes, planned.estimatedBytes)
            } catch (_: ArithmeticException) {
                throw RuntimeJobExecutor.Failure(
                    ErrorCode.ARTIFACT_JOB_TOO_LARGE,
                    "Artifact estimate is too large.",
                )
            }
        }

        private fun requireUnique(names: MutableSet<String>, name: String) {
            if (!names.add(name.lowercase(Locale.ROOT))) throw invalid("Capture artifact names are repeated.")
        }

        private fun invalid(message: String) = RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, message)
    }
}
