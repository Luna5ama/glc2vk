package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v1.CaptureTarget
import dev.vibris.protocol.v1.CaptureTargetKind
import dev.vibris.protocol.v1.ErrorCode
import java.util.Locale

internal class CaptureProgramBuilder {
    @Throws(RuntimeJobExecutor.Failure::class)
    fun actions(job: CoreJob, catalog: ResourceCatalog): ActionProgram {
        if (job.submission.actions.actionsCount > MAX_ACTIONS) {
            throw invalid("Action limit exceeded.")
        }
        val steps = ArrayList<ActionStep>()
        val group = ArrayList<CapturePlan.Target>()
        val artifactNames = HashSet<String>()
        var estimatedBytes = 0L
        for (action in job.submission.actions.actionsList) {
            when {
                action.hasResetTemporalState() -> {
                    estimatedBytes = flush(group, steps, catalog, artifactNames, estimatedBytes)
                    steps.add(ActionStep.reset())
                }
                action.hasWaitFrames() -> {
                    estimatedBytes = flush(group, steps, catalog, artifactNames, estimatedBytes)
                    steps.add(ActionStep.waitFrames(action.waitFrames.frameCount))
                }
                action.hasCaptureScreenshot() || action.hasDumpTexture() || action.hasDumpBuffer() -> {
                    CapturePlanBuilder.addAction(group, action, catalog)
                }
                else -> throw invalid("Action is not supported.")
            }
        }
        estimatedBytes = flush(group, steps, catalog, artifactNames, estimatedBytes)
        return ActionProgram(java.util.List.copyOf(steps), estimatedBytes)
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun ab(job: CoreJob, catalog: ResourceCatalog): AbProgram {
        val recipe = job.submission.recipe.abCompare
        if (recipe.capturesCount == 0 || recipe.capturesCount > MAX_ACTIONS) {
            throw invalid("A/B capture count is invalid.")
        }
        val baseline = ArrayList<CapturePlan.Target>()
        val candidate = ArrayList<CapturePlan.Target>()
        for (index in 0 until recipe.capturesCount) {
            val capture = recipe.getCaptures(index)
            baseline.add(abTarget(catalog, capture, "a-$index"))
            candidate.add(abTarget(catalog, capture, "b-$index"))
        }
        val a = CapturePlanBuilder.plan(baseline, catalog)
        val b = CapturePlanBuilder.plan(candidate, catalog)
        val estimate = try {
            Math.addExact(a.estimatedBytes, b.estimatedBytes)
        } catch (_: ArithmeticException) {
            throw RuntimeJobExecutor.Failure(
                ErrorCode.ARTIFACT_JOB_TOO_LARGE,
                "A/B estimate is too large.",
            )
        }
        return AbProgram(a.capture, b.capture, estimate)
    }

    enum class ActionType {
        RESET,
        WAIT,
        CAPTURE,
    }

    @JvmRecord
    data class ActionStep(
        val type: ActionType,
        val frames: Int,
        val capture: CapturePlan?,
    ) {
        companion object {
            @JvmStatic
            fun reset(): ActionStep = ActionStep(ActionType.RESET, 0, null)

            @JvmStatic
            fun waitFrames(frames: Int): ActionStep = ActionStep(ActionType.WAIT, frames, null)

            @JvmStatic
            fun capture(capture: CapturePlan): ActionStep = ActionStep(ActionType.CAPTURE, 0, capture)
        }
    }

    @JvmRecord
    data class ActionProgram(val steps: List<ActionStep>, val estimatedBytes: Long)

    @JvmRecord
    data class AbProgram(
        val baseline: CapturePlan,
        val candidate: CapturePlan,
        val estimatedBytes: Long,
    )

    companion object {
        private const val MAX_ACTIONS = 64

        @Throws(RuntimeJobExecutor.Failure::class)
        private fun flush(
            group: MutableList<CapturePlan.Target>,
            steps: MutableList<ActionStep>,
            catalog: ResourceCatalog,
            artifactNames: MutableSet<String>,
            estimatedBytes: Long,
        ): Long {
            if (group.isEmpty()) {
                return estimatedBytes
            }
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

        @Throws(RuntimeJobExecutor.Failure::class)
        private fun requireUnique(names: MutableSet<String>, name: String) {
            if (!names.add(name.lowercase(Locale.ROOT))) {
                throw invalid("Capture artifact names are repeated.")
            }
        }

        @Throws(RuntimeJobExecutor.Failure::class)
        private fun abTarget(
            catalog: ResourceCatalog,
            capture: CaptureTarget,
            artifactName: String,
        ): CapturePlan.Target {
            val fallback = when (capture.kind) {
                CaptureTargetKind.CAPTURE_TARGET_KIND_SCREENSHOT -> CapturePlan.ArtifactFormat.PNG
                CaptureTargetKind.CAPTURE_TARGET_KIND_TEXTURE -> CapturePlan.ArtifactFormat.RAW
                CaptureTargetKind.CAPTURE_TARGET_KIND_BUFFER -> CapturePlan.ArtifactFormat.BIN
                else -> throw invalid("A/B capture kind is invalid.")
            }
            val format = CapturePlanBuilder.format(capture.format, fallback)
            return when (capture.kind) {
                CaptureTargetKind.CAPTURE_TARGET_KIND_SCREENSHOT ->
                    CapturePlanBuilder.screenshot(catalog, format, artifactName)
                CaptureTargetKind.CAPTURE_TARGET_KIND_TEXTURE ->
                    CapturePlanBuilder.target(
                        ResourceCatalog.ResourceKind.TEXTURE,
                        capture.name,
                        format,
                        artifactName,
                        0,
                        0,
                    )
                CaptureTargetKind.CAPTURE_TARGET_KIND_BUFFER ->
                    CapturePlanBuilder.target(
                        ResourceCatalog.ResourceKind.BUFFER,
                        capture.name,
                        format,
                        artifactName,
                        0,
                        0,
                    )
                else -> throw invalid("A/B capture kind is invalid.")
            }
        }

        private fun invalid(message: String): RuntimeJobExecutor.Failure =
            RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, message)
    }
}