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
        if (job.submission.hasResultArtifacts()) {
            val options = job.submission.resultArtifacts
            if ((!options.json && !options.csv) ||
                options.kind !in setOf("profile", "profile_matrix") ||
                options.convertedUnitsList.any { it != "us" && it != "ms" } ||
                options.convertedUnitsList.distinct().size != options.convertedUnitsCount ||
                options.attempt == 0 ||
                options.previousAttemptsCount > 5 ||
                options.previousAttemptsList.any {
                    it.attempt == 0 || it.attempt >= options.attempt || it.status.isBlank()
                } ||
                options.previousAttemptsList.map { it.attempt }.distinct().size != options.previousAttemptsCount
            ) {
                throw invalid("Result artifact options are invalid.")
            }
        }
        val steps = ArrayList<ActionStep>()
        val group = ArrayList<CapturePlan.Target>()
        val artifactNames = HashSet<String>()
        var estimatedBytes = 0L
        var groupActionIndex = -1
        var captureCount = 0
        var comparisons = 0
        fun flushGroup() {
            estimatedBytes = flush(
                group,
                steps,
                catalog,
                artifactNames,
                estimatedBytes,
                groupActionIndex,
            )
            groupActionIndex = -1
        }
        for ((actionIndex, action) in job.submission.actions.actionsList.withIndex()) {
            when {
                action.hasLoadShader() -> {
                    flushGroup()
                    val load = action.loadShader
                    if (
                        load.sourceUuid.isBlank() || load.sourceId.isBlank() || load.configId.isBlank() ||
                        load.caseId.isBlank()
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
                action.hasTakeScreenshot() || action.hasDumpTextureV2() || action.hasDumpBuffer() -> {
                    val afterFrames = if (action.hasTakeScreenshot()) action.takeScreenshot.afterFrames else 0
                    if (afterFrames < 0) throw invalid("Screenshot frame delay is too large.")
                    if (afterFrames > 0) {
                        flushGroup()
                        steps.add(ActionStep.waitFrames(actionIndex, afterFrames))
                    }
                    if (group.isEmpty()) groupActionIndex = actionIndex
                    CapturePlanBuilder.addAction(group, action, catalog)
                }
                action.hasGetPatchedShaders() -> {
                    flushGroup()
                    val capture = CapturePlanBuilder.patchedShaders(action.getPatchedShaders.artifactName)
                    requireUnique(artifactNames, capture.targets.single().artifactName)
                    steps.add(ActionStep.patchedShaders(actionIndex, capture))
                }
                action.hasCompareCaptures() -> {
                    flushGroup()
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
                            actionIndex,
                            Comparison(
                                compare.baselineCaptureIndex,
                                compare.candidateCaptureIndex,
                                compare.baselineLabel,
                                compare.candidateLabel,
                            ),
                        ),
                    )
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
        val runtimeAction: dev.vibris.protocol.v1.Action?,
        val loadShader: dev.vibris.protocol.v1.LoadShader?,
    ) {
        companion object {
            fun load(actionIndex: Int, load: dev.vibris.protocol.v1.LoadShader) =
                ActionStep(ActionType.LOAD, null, 0, null, null, actionIndex, null, load)
            fun activate(actionIndex: Int, uuid: String) =
                ActionStep(ActionType.ACTIVATE, uuid, 0, null, null, actionIndex, null, null)
            fun reset(actionIndex: Int) =
                ActionStep(ActionType.RESET, null, 0, null, null, actionIndex, null, null)
            fun waitFrames(actionIndex: Int, frames: Int) =
                ActionStep(ActionType.WAIT, null, frames, null, null, actionIndex, null, null)
            fun capture(actionIndex: Int, capture: CapturePlan) =
                ActionStep(ActionType.CAPTURE, null, 0, capture, null, actionIndex, null, null)
            fun patchedShaders(actionIndex: Int, capture: CapturePlan) =
                ActionStep(ActionType.PATCHED_SHADERS, null, 0, capture, null, actionIndex, null, null)
            fun compare(actionIndex: Int, comparison: Comparison) =
                ActionStep(ActionType.COMPARE, null, 0, null, comparison, actionIndex, null, null)
            fun runtime(actionIndex: Int, action: dev.vibris.protocol.v1.Action) =
                ActionStep(ActionType.RUNTIME, null, 0, null, null, actionIndex, action, null)
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
            actionIndex: Int,
        ): Long {
            if (group.isEmpty()) return estimatedBytes
            val planned = CapturePlanBuilder.plan(java.util.List.copyOf(group), catalog)
            for (target in planned.capture.targets) {
                target.outputs.forEach { requireUnique(artifactNames, it.fileName) }
            }
            group.clear()
            steps.add(ActionStep.capture(actionIndex, planned.capture))
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
