package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ErrorCode
import java.util.Locale

internal object CapturePlanBuilder {
    @JvmRecord
    data class Plan(val capture: CapturePlan, val estimatedBytes: Long)

    fun addAction(
        targets: MutableList<CapturePlan.Target>,
        action: Action,
        catalog: ResourceCatalog,
    ) {
        when {
            action.hasTakeScreenshot() -> {
                val capture = action.takeScreenshot
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
                val selector = dump.resource
                targets.add(
                    target(
                        ResourceCatalog.ResourceKind.TEXTURE,
                        selector.logicalName,
                        textureFormat(dump.format),
                        dump.artifactName,
                        selector.mipLevel,
                        selector.layer,
                    ),
                )
            }
            action.hasDumpBuffer() -> {
                val dump = action.dumpBuffer
                targets.add(
                    target(
                        ResourceCatalog.ResourceKind.BUFFER,
                        dump.logicalName,
                        CapturePlan.ArtifactFormat.BIN,
                        dump.artifactName,
                        0,
                        0,
                    ),
                )
            }
        }
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    fun plan(targets: List<CapturePlan.Target>, catalog: ResourceCatalog): Plan {
        val capture = CapturePlan(targets.map { expand(it, catalog) })
        return Plan(capture, validateAndEstimate(capture, catalog))
    }

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

    fun target(
        kind: ResourceCatalog.ResourceKind,
        name: String,
        format: CapturePlan.ArtifactFormat,
        artifactName: String,
        mip: Int,
        layer: Int,
    ): CapturePlan.Target = CapturePlan.Target(kind, name, format, artifactName, mip, layer)

    fun patchedShaders(artifactName: String): CapturePlan = CapturePlan(listOf(CapturePlan.Target(
        ResourceCatalog.ResourceKind.PATCHED_SHADERS,
        "patched_shaders",
        CapturePlan.ArtifactFormat.TEXT,
        artifactName,
        0,
        0,
    )))

    fun realizePatchedShaders(plan: CapturePlan, result: dev.vibris.api.CaptureResult): CapturePlan {
        val target = plan.targets.single()
        require(target.kind == ResourceCatalog.ResourceKind.PATCHED_SHADERS && target.outputs.isEmpty()) {
            "Patched shader capture plan is invalid"
        }
        val group = result.groups.single()
        require(group.name == target.artifactName &&
            group.resource.kind == target.kind && group.resource.logicalName == target.logicalName) {
            "Patched shader capture result did not match its plan"
        }
        val outputs = group.artifacts.map { artifact ->
            CapturePlan.ArtifactOutputSpec(
                artifact.fileName,
                artifact.format,
                artifact.role,
                artifact.subresourceIndex,
            )
        }
        require(outputs.map { canonical(it.fileName) }.toSet().size == outputs.size) {
            "Patched shader artifact names are repeated"
        }
        return CapturePlan(listOf(target.copy(outputs = outputs)))
    }

    @Throws(RuntimeJobExecutor.Failure::class)
    private fun validateAndEstimate(plan: CapturePlan, catalog: ResourceCatalog): Long {
        var bytes = 0L
        val names = HashSet<String>()
        try {
            for (target in plan.targets) {
                if (!supported(target)) {
                    throw RuntimeJobExecutor.Failure(
                        ErrorCode.ERROR_CODE_CAPTURE_FAILED,
                        "Capture resource kind and format are incompatible.",
                    )
                }
                for (output in target.outputs) {
                    if (!names.add(canonical(output.fileName))) {
                        throw RuntimeJobExecutor.Failure(
                            ErrorCode.ERROR_CODE_CAPTURE_FAILED,
                            "Capture artifact names are repeated.",
                        )
                    }
                }
                val resource = catalog.resources
                    .firstOrNull { it.kind == target.kind && it.logicalName == target.logicalName }
                    ?: throw missing(target.logicalName)
                if (
                    target.mipLevel >= maxOf(1, resource.mipLevels) ||
                    target.layer >= maxOf(1, resource.layers)
                ) {
                    throw missing(target.logicalName)
                }
                if (target.format == CapturePlan.ArtifactFormat.PNG) validatePng(resource)
                for (output in target.outputs) {
                    bytes = Math.addExact(bytes, estimate(output, resource))
                }
            }
            return bytes
        } catch (_: ArithmeticException) {
            throw RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_ARTIFACT_TOO_LARGE,
                "Artifact estimate is too large.",
            )
        }
    }

    private fun missing(name: String): RuntimeJobExecutor.Failure =
        RuntimeJobExecutor.Failure(
            ErrorCode.ERROR_CODE_RESOURCE_NOT_FOUND,
            "Capture resource was not found: $name",
        )

    private fun canonical(name: String): String = name.lowercase(Locale.ROOT)

    private fun validatePng(resource: ResourceCatalog.ResourceDescriptor) {
        val packed = setOf("RGB10_A2", "RGB10_A2UI", "R11F_G11F_B10F", "RGB9_E5", "RGB5_A1", "RGBA4")
        val reason = when {
            resource.internalFormat.contains("COMPRESSED", ignoreCase = true) -> "compressed"
            resource.numericClass.equals("stencil", ignoreCase = true) -> "stencil-only"
            (resource.numericClass.equals("sint", ignoreCase = true) ||
                resource.numericClass.equals("uint", ignoreCase = true)) && resource.componentBits > 16 ->
                "32-bit integer"
            resource.internalFormat.uppercase(Locale.ROOT) in packed -> "packed"
            resource.channelLayout !in setOf("R", "RG", "RGB", "RGBA", "DEPTH", "DEPTH_STENCIL", "") ->
                "channel layout ${resource.channelLayout}"
            else -> null
        }
        if (reason != null) throw RuntimeJobExecutor.Failure(
            ErrorCode.ERROR_CODE_CAPTURE_FAILED,
            "PNG export does not support ${resource.internalFormat} ($reason); use format=bin.",
        )
    }

    private fun estimate(
        output: CapturePlan.ArtifactOutputSpec,
        resource: ResourceCatalog.ResourceDescriptor,
    ): Long = when (output.format) {
        CapturePlan.ArtifactFormat.BIN -> resource.byteSize
        CapturePlan.ArtifactFormat.TEXT -> resource.byteSize
        CapturePlan.ArtifactFormat.JSON -> 4_096L
        CapturePlan.ArtifactFormat.PNG -> {
            val channels = when (resource.channelLayout) {
                "R", "DEPTH", "DEPTH_STENCIL" -> 1
                "RG" -> 2
                "RGB" -> 3
                "RGBA" -> 4
                else -> maxOf(1, resource.channelCount)
            }
            val bits = if (resource.numericClass.equals("float", true) ||
                resource.numericClass.startsWith("depth", true)) 16 else maxOf(8, resource.componentBits)
            val rows = Math.multiplyExact(resource.height.toLong(), 1L +
                Math.multiplyExact(resource.width.toLong(), channels.toLong() * ((bits + 7) / 8)))
            Math.addExact(rows, 1_024L)
        }
        CapturePlan.ArtifactFormat.EXR -> resource.byteSize
    }

    private fun supported(target: CapturePlan.Target): Boolean =
        when (target.kind) {
            ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER ->
                target.format == CapturePlan.ArtifactFormat.PNG
            ResourceCatalog.ResourceKind.TEXTURE ->
                target.format == CapturePlan.ArtifactFormat.PNG ||
                    target.format == CapturePlan.ArtifactFormat.BIN
            ResourceCatalog.ResourceKind.BUFFER ->
                target.format == CapturePlan.ArtifactFormat.BIN
            ResourceCatalog.ResourceKind.PATCHED_SHADERS ->
                target.format == CapturePlan.ArtifactFormat.TEXT
        }

    fun format(
        format: dev.vibris.protocol.v2.ArtifactFormat,
        fallback: CapturePlan.ArtifactFormat,
    ): CapturePlan.ArtifactFormat =
        when (format) {
            dev.vibris.protocol.v2.ArtifactFormat.ARTIFACT_FORMAT_PNG -> CapturePlan.ArtifactFormat.PNG
            dev.vibris.protocol.v2.ArtifactFormat.ARTIFACT_FORMAT_EXR -> CapturePlan.ArtifactFormat.EXR
            dev.vibris.protocol.v2.ArtifactFormat.ARTIFACT_FORMAT_BIN -> CapturePlan.ArtifactFormat.BIN
            dev.vibris.protocol.v2.ArtifactFormat.ARTIFACT_FORMAT_TEXT -> CapturePlan.ArtifactFormat.TEXT
            else -> fallback
        }

    private fun textureFormat(format: dev.vibris.protocol.v2.ArtifactFormat): CapturePlan.ArtifactFormat =
        when (format) {
            dev.vibris.protocol.v2.ArtifactFormat.ARTIFACT_FORMAT_PNG -> CapturePlan.ArtifactFormat.PNG
            dev.vibris.protocol.v2.ArtifactFormat.ARTIFACT_FORMAT_BIN -> CapturePlan.ArtifactFormat.BIN
            else -> throw RuntimeJobExecutor.Failure(
                ErrorCode.ERROR_CODE_CAPTURE_FAILED,
                "dump_texture format must be png or bin.",
            )
        }

    private fun expand(target: CapturePlan.Target, catalog: ResourceCatalog): CapturePlan.Target {
        val resource = catalog.resources
            .firstOrNull { it.kind == target.kind && it.logicalName == target.logicalName }
            ?: throw missing(target.logicalName)
        val outputs = ArrayList<CapturePlan.ArtifactOutputSpec>()
        if (target.kind == ResourceCatalog.ResourceKind.TEXTURE &&
            target.format == CapturePlan.ArtifactFormat.PNG && resource.depth > 1
        ) {
            val digits = maxOf(4, (resource.depth - 1).toString().length)
            repeat(resource.depth) { layer ->
                outputs.add(
                    CapturePlan.ArtifactOutputSpec(
                        "${target.artifactName}.layer${layer.toString().padStart(digits, '0')}.png",
                        CapturePlan.ArtifactFormat.PNG,
                        CapturePlan.ArtifactRole.SUBRESOURCE,
                        layer,
                    ),
                )
            }
        } else {
            val extension = target.format.name.lowercase(Locale.ROOT)
            val fileName = if (target.artifactName.lowercase(Locale.ROOT).endsWith(".$extension")) {
                target.artifactName
            } else {
                "${target.artifactName}.$extension"
            }
            outputs.add(
                CapturePlan.ArtifactOutputSpec(
                    fileName,
                    target.format,
                    CapturePlan.ArtifactRole.PRIMARY,
                    null,
                ),
            )
        }
        if (target.kind != ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER) {
            outputs.add(
                CapturePlan.ArtifactOutputSpec(
                    target.metadataFileName(),
                    CapturePlan.ArtifactFormat.JSON,
                    CapturePlan.ArtifactRole.METADATA,
                    null,
                ),
            )
        }
        return target.copy(outputs = outputs)
    }
}
