package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v1.Action
import dev.vibris.protocol.v1.ErrorCode
import java.util.Locale

internal class CapturePlanBuilder(private val maxActions: Int = DEFAULT_MAX_ACTIONS) {
    init {
        require(maxActions > 0) { "maxActions must be positive" }
    }
    @Throws(RuntimeJobExecutor.Failure::class)
    fun waitFrames(job: CoreJob): Int {
        try {
            requireActionLimit(job)
            val frames = if (job.submission.hasRecipe()) recipeFrames(job) else actionFrames(job)
            if (frames > Int.MAX_VALUE) {
                throw ArithmeticException("frame count")
            }
            return frames.toInt()
        } catch (_: ArithmeticException) {
            throw RuntimeJobExecutor.Failure(
                ErrorCode.INTERNAL_ERROR,
                "Requested frame count is too large.",
            )
        }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun build(job: CoreJob, catalog: ResourceCatalog): Plan {
        try {
            val targets = ArrayList<CapturePlan.Target>()
            if (job.submission.hasActions()) {
                for (action in job.submission.actions.actionsList) {
                    addAction(targets, action, catalog)
                }
            } else if (job.submission.recipe.hasReloadAndCapture()) {
                val recipe = job.submission.recipe.reloadAndCapture
                targets.add(
                    screenshot(
                        catalog,
                        format(recipe.screenshotFormat, CapturePlan.ArtifactFormat.PNG),
                        "screenshot",
                    ),
                )
            } else if (job.submission.recipe.hasCaptureDebugBundle()) {
                addDebugBundle(targets, job, catalog)
            }
            if (targets.size > maxActions) {
                throw RuntimeJobExecutor.Failure(
                    ErrorCode.CAPTURE_FAILED,
                    "Capture target limit exceeded.",
                )
            }
            return plan(targets, catalog)
        } catch (_: IllegalArgumentException) {
            throw RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, "Capture plan is invalid.")
        }
    }

    @JvmRecord
    data class Plan(val capture: CapturePlan, val estimatedBytes: Long)

    @Throws(RuntimeJobExecutor.Failure::class)
    private fun requireActionLimit(job: CoreJob) {
        if (job.submission.hasActions() && job.submission.actions.actionsCount > maxActions) {
            throw RuntimeJobExecutor.Failure(ErrorCode.CAPTURE_FAILED, "Action limit exceeded.")
        }
    }

    companion object {
        private const val DEFAULT_MAX_ACTIONS = 64

        private fun addDebugBundle(
            targets: MutableList<CapturePlan.Target>,
            job: CoreJob,
            catalog: ResourceCatalog,
        ) {
            val recipe = job.submission.recipe.captureDebugBundle
            if (recipe.screenshot) {
                targets.add(screenshot(catalog, CapturePlan.ArtifactFormat.PNG, "screenshot"))
            }
            recipe.texturesList.forEach { name ->
                targets.add(
                    target(
                        ResourceCatalog.ResourceKind.TEXTURE,
                        name,
                        CapturePlan.ArtifactFormat.RAW,
                        name,
                        0,
                        0,
                    ),
                )
            }
            recipe.buffersList.forEach { name ->
                targets.add(
                    target(
                        ResourceCatalog.ResourceKind.BUFFER,
                        name,
                        CapturePlan.ArtifactFormat.BIN,
                        name,
                        0,
                        0,
                    ),
                )
            }
        }

        @JvmStatic
        fun addAction(
            targets: MutableList<CapturePlan.Target>,
            action: Action,
            catalog: ResourceCatalog,
        ) {
            when {
                action.hasCaptureScreenshot() -> {
                    val capture = action.captureScreenshot
                    targets.add(
                        screenshot(
                            catalog,
                            format(capture.format, CapturePlan.ArtifactFormat.PNG),
                            capture.artifactName.ifBlank { "screenshot" },
                        ),
                    )
                }
                action.hasDumpTexture() -> {
                    val dump = action.dumpTexture
                    targets.add(
                        target(
                            ResourceCatalog.ResourceKind.TEXTURE,
                            dump.logicalName,
                            format(dump.format, CapturePlan.ArtifactFormat.RAW),
                            dump.artifactName,
                            dump.mipLevel,
                            dump.layer,
                        ),
                    )
                }
                action.hasDumpBuffer() -> {
                    val dump = action.dumpBuffer
                    targets.add(
                        target(
                            ResourceCatalog.ResourceKind.BUFFER,
                            dump.logicalName,
                            format(dump.format, CapturePlan.ArtifactFormat.BIN),
                            dump.artifactName,
                            0,
                            0,
                        ),
                    )
                }
            }
        }

        @JvmStatic
        @Throws(RuntimeJobExecutor.Failure::class)
        fun plan(targets: List<CapturePlan.Target>, catalog: ResourceCatalog): Plan {
            val capture = CapturePlan(targets)
            return Plan(capture, validateAndEstimate(capture, catalog))
        }

        @JvmStatic
        fun screenshot(
            catalog: ResourceCatalog,
            format: CapturePlan.ArtifactFormat,
            artifactName: String,
        ): CapturePlan.Target {
            val name = catalog.resources
                .firstOrNull { it.kind == ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER }
                ?.logicalName
                ?: "final_framebuffer"
            return target(
                ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER,
                name,
                format,
                artifactName,
                0,
                0,
            )
        }

        @JvmStatic
        fun target(
            kind: ResourceCatalog.ResourceKind,
            name: String,
            format: CapturePlan.ArtifactFormat,
            artifactName: String,
            mip: Int,
            layer: Int,
        ): CapturePlan.Target = CapturePlan.Target(kind, name, format, artifactName, mip, layer)

        @Throws(RuntimeJobExecutor.Failure::class)
        private fun validateAndEstimate(plan: CapturePlan, catalog: ResourceCatalog): Long {
            var bytes = 0L
            val names = HashSet<String>()
            try {
                for (target in plan.targets) {
                    if (!supported(target)) {
                        throw RuntimeJobExecutor.Failure(
                            ErrorCode.CAPTURE_FAILED,
                            "Capture resource kind and format are incompatible.",
                        )
                    }
                    if (
                        !names.add(canonical(target.fileName())) ||
                        !names.add(canonical(target.metadataFileName()))
                    ) {
                        throw RuntimeJobExecutor.Failure(
                            ErrorCode.CAPTURE_FAILED,
                            "Capture artifact names are repeated.",
                        )
                    }
                    val resource = find(catalog, target)
                    if (
                        target.mipLevel >= maxOf(1, resource.mipLevels) ||
                        target.layer >= maxOf(1, resource.layers)
                    ) {
                        throw missing(target.logicalName)
                    }
                    bytes = Math.addExact(bytes, resource.byteSize)
                }
                return bytes
            } catch (_: ArithmeticException) {
                throw RuntimeJobExecutor.Failure(
                    ErrorCode.ARTIFACT_JOB_TOO_LARGE,
                    "Artifact estimate is too large.",
                )
            }
        }

        @Throws(RuntimeJobExecutor.Failure::class)
        private fun find(
            catalog: ResourceCatalog,
            target: CapturePlan.Target,
        ): ResourceCatalog.ResourceDescriptor =
            catalog.resources
                .firstOrNull { it.kind == target.kind && it.logicalName == target.logicalName }
                ?: throw missing(target.logicalName)

        private fun missing(name: String): RuntimeJobExecutor.Failure =
            RuntimeJobExecutor.Failure(
                ErrorCode.CAPTURE_RESOURCE_NOT_FOUND,
                "Capture resource was not found: $name",
            )

        private fun actionFrames(job: CoreJob): Long {
            var frames = 0L
            for (action in job.submission.actions.actionsList) {
                if (action.hasWaitFrames()) {
                    frames = Math.addExact(frames, action.waitFrames.frameCount.toLong())
                }
            }
            return frames
        }

        private fun canonical(name: String): String = name.lowercase(Locale.ROOT)

        private fun supported(target: CapturePlan.Target): Boolean =
            when (target.kind) {
                ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER ->
                    target.format == CapturePlan.ArtifactFormat.PNG
                ResourceCatalog.ResourceKind.TEXTURE ->
                    target.format == CapturePlan.ArtifactFormat.PNG ||
                        target.format == CapturePlan.ArtifactFormat.RAW
                ResourceCatalog.ResourceKind.BUFFER ->
                    target.format == CapturePlan.ArtifactFormat.BIN
            }

        private fun recipeFrames(job: CoreJob): Long {
            val recipe = job.submission.recipe
            return when {
                recipe.hasReloadAndCapture() -> recipe.reloadAndCapture.warmupFrames.toLong()
                recipe.hasCaptureDebugBundle() -> recipe.captureDebugBundle.warmupFrames.toLong()
                recipe.hasAbCompare() -> recipe.abCompare.warmupFrames.toLong()
                else -> 0L
            }
        }

        @JvmStatic
        fun format(
            format: dev.vibris.protocol.v1.ArtifactFormat,
            fallback: CapturePlan.ArtifactFormat,
        ): CapturePlan.ArtifactFormat =
            when (format) {
                dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_PNG ->
                    CapturePlan.ArtifactFormat.PNG
                dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_EXR ->
                    CapturePlan.ArtifactFormat.EXR
                dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_RAW ->
                    CapturePlan.ArtifactFormat.RAW
                dev.vibris.protocol.v1.ArtifactFormat.ARTIFACT_FORMAT_BIN ->
                    CapturePlan.ArtifactFormat.BIN
                else -> fallback
            }
    }
}