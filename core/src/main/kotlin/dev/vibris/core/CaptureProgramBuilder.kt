package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.VisualThresholds
import java.util.Locale

internal class CaptureProgramBuilder(private val maxActions: Int = DEFAULT_MAX_ACTIONS) {
    private val expandedActionLimit = maxActions.toLong() * 2 + 8

    init {
        require(maxActions > 0) { "maxActions must be positive" }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun actions(job: CoreJob, catalog: ResourceCatalog): ActionProgram {
        if (!job.submission.hasActionSequence()) throw invalid("Only action-sequence jobs are executable.")
        if (job.submission.actionSequence.actionsCount.toLong() > expandedActionLimit) {
            throw invalid("Action limit exceeded.")
        }
        if (job.submission.hasResultArtifacts()) {
            val options = job.submission.resultArtifacts
            if ((!options.writeJson && !options.writeCsv) ||
                options.convertedUnitsList.any { it != "us" && it != "ms" } ||
                options.convertedUnitsList.distinct().size != options.convertedUnitsCount
            ) {
                throw invalid("Result artifact options are invalid.")
            }
        }
        val steps = ArrayList<ActionStep>()
        val group = ArrayList<PendingCapture>()
        val artifactNames = HashSet<String>()
        var estimatedBytes = 0L
        var comparisons = 0
        fun flushGroup() {
            estimatedBytes = flush(
                group,
                steps,
                catalog,
                artifactNames,
                estimatedBytes,
            )
        }
        for ((actionIndex, action) in job.submission.actionSequence.actionsList.withIndex()) {
            when {
                action.hasLoadShader() -> {
                    flushGroup()
                    val load = action.loadShader
                    if (
                        load.sourceUuid.isBlank() || load.sourceId.isBlank() || load.configId.isBlank()
                    ) {
                        throw invalid("Shader load references are incomplete.")
                    }
                    steps.add(ActionStep.load(actionIndex, load))
                }
                action.hasActivateSource() -> {
                    flushGroup()
                    val uuid = action.activateSource.sourceUuid
                    if (uuid.isBlank()) throw invalid("Source UUID is missing.")
                    steps.add(ActionStep.activate(actionIndex, uuid))
                }
                action.hasResetTemporalState() -> {
                    flushGroup()
                    steps.add(ActionStep.reset(actionIndex))
                }
                action.hasWaitFrames() -> {
                    flushGroup()
                    if (action.waitFrames.frameCount <= 0) throw invalid("Frame count must be positive.")
                    steps.add(ActionStep.waitFrames(actionIndex, action.waitFrames.frameCount))
                }
                action.hasTakeScreenshot() || action.hasDumpTexture() || action.hasDumpBuffer() -> {
                    val afterFrames = if (action.hasTakeScreenshot()) action.takeScreenshot.afterFrames else 0
                    if (afterFrames < 0) throw invalid("Screenshot frame delay is too large.")
                    if (afterFrames > 0) flushGroup()
                    val targets = ArrayList<CapturePlan.Target>(1)
                    CapturePlanBuilder.addAction(targets, action, catalog)
                    if (targets.size != 1) throw invalid("Capture action did not resolve to exactly one target.")
                    group.add(PendingCapture(actionIndex, targets.single(), afterFrames))
                    if (afterFrames > 0) flushGroup()
                }
                action.hasGetPatchedShaders() -> {
                    flushGroup()
                    val capture = CapturePlanBuilder.patchedShaders(action.getPatchedShaders.artifactName)
                    requireUnique(artifactNames, capture.targets.single().artifactName)
                    steps.add(ActionStep.patchedShaders(actionIndex, capture))
                }
                action.hasCompareCaptures() -> {
                    flushGroup()
                    val compare = action.compareCaptures
                    val captures = steps.asSequence()
                        .filter { it.type == ActionType.CAPTURE }
                        .flatMap { step ->
                            step.captureActions.asSequence().map { capture ->
                                capture.actionIndex to CapturePlan(listOf(step.capture!!.targets[capture.targetIndex]))
                            }
                        }
                        .toMap()
                    val baselineCapture = captures[compare.baselineActionIndex]
                    val candidateCapture = captures[compare.candidateActionIndex]
                    if (
                        comparisons++ != 0 || baselineCapture == null || candidateCapture == null ||
                        compare.baselineActionIndex == compare.candidateActionIndex ||
                        compare.baselineLabel.isBlank() || compare.candidateLabel.isBlank() ||
                        (compare.hasThresholds() && !validThresholds(compare.thresholds))
                    ) {
                        throw invalid("Capture comparison is invalid.")
                    }
                    steps.add(
                        ActionStep.compare(
                            actionIndex,
                            Comparison(
                                baselineCapture,
                                candidateCapture,
                                compare.baselineLabel,
                                compare.candidateLabel,
                                if (compare.hasThresholds()) compare.thresholds else null,
                            ),
                        ),
                    )
                }
                action.hasInspectShader() -> {
                    flushGroup()
                    steps.add(ActionStep.inspect(actionIndex))
                }
                RuntimeActionProtocol.isRuntime(action) -> {
                    flushGroup()
                    steps.add(ActionStep.runtime(actionIndex, action))
                }
                else -> throw invalid("Action is not supported.")
            }
        }
        flushGroup()
        val firstActivation = steps.indexOfFirst { it.type == ActionType.ACTIVATE || it.type == ActionType.LOAD }
        if (firstActivation > 0) {
            throw invalid("Source activation must be the first action.")
        }
        return ActionProgram(java.util.List.copyOf(steps), estimatedBytes)
    }

    enum class ActionType {
        LOAD,
        ACTIVATE,
        RESET,
        WAIT,
        CAPTURE,
        PATCHED_SHADERS,
        COMPARE,
        INSPECT,
        RUNTIME,
    }

    @JvmRecord
    data class ActionStep(
        val type: ActionType,
        val sourceUuid: String?,
        val frames: Int,
        val capture: CapturePlan?,
        val comparison: Comparison?,
        val actionIndex: Int,
        val runtimeAction: dev.vibris.protocol.v2.Action?,
        val loadShader: dev.vibris.protocol.v2.LoadShader?,
        val captureActions: List<CaptureAction>,
    ) {
        companion object {
            fun load(actionIndex: Int, load: dev.vibris.protocol.v2.LoadShader) =
                ActionStep(ActionType.LOAD, null, 0, null, null, actionIndex, null, load, emptyList())
            fun activate(actionIndex: Int, uuid: String) =
                ActionStep(ActionType.ACTIVATE, uuid, 0, null, null, actionIndex, null, null, emptyList())
            fun reset(actionIndex: Int) =
                ActionStep(ActionType.RESET, null, 0, null, null, actionIndex, null, null, emptyList())
            fun waitFrames(actionIndex: Int, frames: Int) =
                ActionStep(ActionType.WAIT, null, frames, null, null, actionIndex, null, null, emptyList())
            fun capture(capture: CapturePlan, actions: List<CaptureAction>) =
                ActionStep(ActionType.CAPTURE, null, 0, capture, null, actions.first().actionIndex, null, null, actions)
            fun patchedShaders(actionIndex: Int, capture: CapturePlan) =
                ActionStep(ActionType.PATCHED_SHADERS, null, 0, capture, null, actionIndex, null, null, emptyList())
            fun compare(actionIndex: Int, comparison: Comparison) =
                ActionStep(ActionType.COMPARE, null, 0, null, comparison, actionIndex, null, null, emptyList())
            fun inspect(actionIndex: Int) =
                ActionStep(ActionType.INSPECT, null, 0, null, null, actionIndex, null, null, emptyList())
            fun runtime(actionIndex: Int, action: dev.vibris.protocol.v2.Action) =
                ActionStep(ActionType.RUNTIME, null, 0, null, null, actionIndex, action, null, emptyList())
        }
    }

    @JvmRecord
    data class CaptureAction(
        val actionIndex: Int,
        val targetIndex: Int,
        val beforeFrames: Int,
    )

    @JvmRecord
    data class Comparison(
        val baselinePlan: CapturePlan,
        val candidatePlan: CapturePlan,
        val baselineLabel: String,
        val candidateLabel: String,
        val thresholds: VisualThresholds?,
    )

    @JvmRecord
    data class ActionProgram(val steps: List<ActionStep>, val estimatedBytes: Long)

    private data class PendingCapture(
        val actionIndex: Int,
        val target: CapturePlan.Target,
        val beforeFrames: Int,
    )

    companion object {
        private const val DEFAULT_MAX_ACTIONS = 64
        private fun validThresholds(value: VisualThresholds): Boolean =
            value.pixelErrorThreshold in 0.0..1.0 &&
                (!value.hasMaxMeanAbsoluteError() || value.maxMeanAbsoluteError in 0.0..1.0) &&
                (!value.hasMaxRootMeanSquareError() || value.maxRootMeanSquareError in 0.0..1.0) &&
                (!value.hasMaxP95AbsoluteError() || value.maxP95AbsoluteError in 0.0..1.0) &&
                (!value.hasMaxAbsoluteError() || value.maxAbsoluteError in 0.0..1.0) &&
                (!value.hasMaxThresholdPixelRatio() || value.maxThresholdPixelRatio in 0.0..1.0) &&
                (!value.hasMinSsim() || value.minSsim in -1.0..1.0)

        @Throws(RuntimeJobExecutor.Failure::class)
        private fun flush(
            group: MutableList<PendingCapture>,
            steps: MutableList<ActionStep>,
            catalog: ResourceCatalog,
            artifactNames: MutableSet<String>,
            estimatedBytes: Long,
        ): Long {
            if (group.isEmpty()) return estimatedBytes
            val pending = java.util.List.copyOf(group)
            val planned = CapturePlanBuilder.plan(pending.map(PendingCapture::target), catalog)
            for (target in planned.capture.targets) {
                target.outputs.forEach { requireUnique(artifactNames, it.fileName) }
            }
            group.clear()
            steps.add(
                ActionStep.capture(
                    planned.capture,
                    pending.mapIndexed { index, capture ->
                        CaptureAction(capture.actionIndex, index, capture.beforeFrames)
                    },
                ),
            )
            return try {
                Math.addExact(estimatedBytes, planned.estimatedBytes)
            } catch (_: ArithmeticException) {
                throw RuntimeJobExecutor.Failure(
                    ErrorCode.ERROR_CODE_ARTIFACT_TOO_LARGE,
                    "Artifact estimate is too large.",
                )
            }
        }

        private fun requireUnique(names: MutableSet<String>, name: String) {
            if (!names.add(name.lowercase(Locale.ROOT))) throw invalid("Capture artifact names are repeated.")
        }

        private fun invalid(message: String) = RuntimeJobExecutor.Failure(ErrorCode.ERROR_CODE_CAPTURE_FAILED, message)
    }
}
