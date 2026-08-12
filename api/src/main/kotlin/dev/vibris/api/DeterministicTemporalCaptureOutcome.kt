package dev.vibris.api

sealed interface DeterministicTemporalCaptureOutcome {
    enum class FailureKind {
        CANCELLED,
        RESOURCE_NOT_FOUND,
        ARTIFACT_TOO_LARGE,
        ARTIFACT_QUOTA_EXCEEDED,
        INVALID_CAPTURE,
        MISSED_TARGET,
        OPERATION_FAILED,
        CLEANUP_FAILED,
    }

    @JvmRecord
    data class Failure(
        val kind: FailureKind,
        val message: String,
    ) {
        init {
            require(message.isNotBlank()) { "Failure message must not be blank" }
        }
    }

    @JvmRecord
    data class ContextRejected(
        val context: ContextApplyResult,
        val failure: Failure,
    ) : DeterministicTemporalCaptureOutcome {
        init {
            require(!context.successful) { "A rejected context must be unsuccessful" }
        }
    }

    @JvmRecord
    data class ReloadRejected(
        val context: ContextApplyResult,
        val reload: ReloadResult,
        val failure: Failure,
    ) : DeterministicTemporalCaptureOutcome {
        init {
            require(context.successful) { "A reload rejection requires an applied context" }
            require(!reload.successful) { "A rejected reload must be unsuccessful" }
        }
    }

    @JvmRecord
    data class PlanningRejected(
        val reloaded: DeterministicTemporalCaptureReloaded,
        val failure: Failure,
    ) : DeterministicTemporalCaptureOutcome

    @JvmRecord
    data class ResetRejected(
        val reloaded: DeterministicTemporalCaptureReloaded,
        val plan: CapturePlan,
        val reset: TemporalResetResult,
        val failure: Failure,
    ) : DeterministicTemporalCaptureOutcome {
        init {
            require(plan.targets.isNotEmpty()) { "A deterministic temporal capture plan must not be empty" }
            require(!reset.successful) { "A rejected temporal reset must be unsuccessful" }
        }
    }

    @JvmRecord
    data class WarmupRejected(
        val reloaded: DeterministicTemporalCaptureReloaded,
        val plan: CapturePlan,
        val reset: TemporalResetResult,
        val resetCompletedAtUnixMs: Long,
        val warmupFrames: Int,
        val anchorFrame: Long,
        val completedFrames: Int,
        val currentFrame: Long,
        val failure: Failure,
    ) : DeterministicTemporalCaptureOutcome {
        init {
            require(plan.targets.isNotEmpty()) { "A deterministic temporal capture plan must not be empty" }
            require(reset.successful) { "A warmup rejection requires a successful temporal reset" }
            require(resetCompletedAtUnixMs > 0) { "Temporal reset completion time must be positive" }
            require(warmupFrames > 0) { "A warmup rejection requires a positive warmup frame count" }
            require(anchorFrame >= 0) { "Anchor frame must not be negative" }
            require(completedFrames in 0 until warmupFrames) {
                "Completed frames must describe an incomplete warmup"
            }
            require(currentFrame == Math.addExact(anchorFrame, completedFrames.toLong())) {
                "Current frame must equal the anchor plus completed warmup frames"
            }
        }
    }

    @JvmRecord
    data class CaptureRejected(
        val reloaded: DeterministicTemporalCaptureReloaded,
        val plan: CapturePlan,
        val reset: TemporalResetResult,
        val resetCompletedAtUnixMs: Long,
        val warmupFrames: Int,
        val anchorFrame: Long,
        val warmupEndFrame: Long,
        val targetFrame: Long,
        val terminalFrame: Long,
        val failure: Failure,
    ) : DeterministicTemporalCaptureOutcome {
        init {
            require(plan.targets.isNotEmpty()) { "A deterministic temporal capture plan must not be empty" }
            require(reset.successful) { "A capture rejection requires a successful temporal reset" }
            require(resetCompletedAtUnixMs > 0) { "Temporal reset completion time must be positive" }
            require(warmupFrames >= 0) { "Warmup frame count must not be negative" }
            require(anchorFrame >= 0) { "Anchor frame must not be negative" }
            require(warmupEndFrame == Math.addExact(anchorFrame, warmupFrames.toLong())) {
                "Warmup end frame must equal the anchor plus the requested warmup"
            }
            require(targetFrame == Math.addExact(warmupEndFrame, 1L)) {
                "Capture target must immediately follow the warmup end frame"
            }
            require(terminalFrame >= warmupEndFrame) {
                "Capture terminal frame must not precede the warmup end frame"
            }
        }
    }

    @JvmRecord
    data class Captured(
        val reloaded: DeterministicTemporalCaptureReloaded,
        val plan: CapturePlan,
        val reset: TemporalResetResult,
        val resetCompletedAtUnixMs: Long,
        val warmupFrames: Int,
        val anchorFrame: Long,
        val warmupEndFrame: Long,
        val capture: CaptureResult,
    ) : DeterministicTemporalCaptureOutcome {
        init {
            require(plan.targets.isNotEmpty()) { "A deterministic temporal capture plan must not be empty" }
            require(reset.successful) { "A deterministic capture requires a successful temporal reset" }
            require(resetCompletedAtUnixMs > 0) { "Temporal reset completion time must be positive" }
            require(warmupFrames >= 0) { "Warmup frame count must not be negative" }
            require(anchorFrame >= 0) { "Anchor frame must not be negative" }
            require(warmupEndFrame == Math.addExact(anchorFrame, warmupFrames.toLong())) {
                "Warmup end frame must equal the anchor plus the requested warmup"
            }
            require(capture.frameId == Math.addExact(warmupEndFrame, 1L)) {
                "Capture frame must immediately follow the warmup end frame"
            }
            require(capture.groups.size == plan.targets.size) {
                "Capture groups must correspond one-to-one with planned targets"
            }
            require(plan.targets.zip(capture.groups).all { (target, group) ->
                group.name == target.artifactName &&
                    group.resource.kind == target.resource.kind &&
                    group.resource.logicalName == target.resource.logicalName
            }) {
                "Capture groups must match planned artifact and resource identities"
            }
            require(capture.groups.all { group -> group.resource.frameId == capture.frameId }) {
                "Capture group resources must match the capture frame"
            }
            require(plan.targets.zip(capture.groups).all { (target, group) ->
                target.outputs.size == group.artifacts.size &&
                    target.outputs.zip(group.artifacts).all { (output, artifact) ->
                        artifact.fileName == output.fileName &&
                            artifact.format == output.format &&
                            artifact.role == output.role &&
                            artifact.subresourceIndex == output.subresourceIndex
                    }
            }) {
                "Captured artifacts must exactly match the planned outputs"
            }
        }
    }
}