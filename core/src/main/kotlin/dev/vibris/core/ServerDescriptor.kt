package dev.vibris.core

import dev.vibris.api.RuntimeStatus
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v1.ArtifactFormat
import dev.vibris.protocol.v1.Capability
import dev.vibris.protocol.v1.JobActionKind
import dev.vibris.protocol.v1.ResourceCatalog
import dev.vibris.protocol.v1.ResourceCatalogEntry
import dev.vibris.protocol.v1.ResourceKind
import dev.vibris.protocol.v1.RuntimeState
import dev.vibris.protocol.v1.ServerHello
import dev.vibris.protocol.v1.ServerLimits
import dev.vibris.protocol.v1.ServerState
import dev.vibris.protocol.v1.ServerStatus
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class ServerDescriptor @JvmOverloads constructor(
    pending: Path,
    private val artifacts: ArtifactManager,
    private val runtime: VibrisRuntimeAdapter,
    maxSourceBytes: Long = ServerConfiguration.DEFAULT_MAX_SOURCE_BYTES,
    maxSourceFiles: Int = ServerConfiguration.DEFAULT_MAX_SOURCE_FILES,
) {
    private val baseStatus = ServerStatus.newBuilder()
        .setPendingShadersRoot(pending.toString())
        .setArtifactRoot(artifacts.root().toString())
        .setArtifactQuotaCapBytes(artifacts.quotaBytes())
        .addAllSupportedJobActions(
            listOf(
                JobActionKind.JOB_ACTION_KIND_RESET_TEMPORAL_STATE,
                JobActionKind.JOB_ACTION_KIND_WAIT_FRAMES,
                JobActionKind.JOB_ACTION_KIND_CAPTURE_SCREENSHOT,
                JobActionKind.JOB_ACTION_KIND_CAPTURE_TEXTURE,
                JobActionKind.JOB_ACTION_KIND_CAPTURE_BUFFER,
                JobActionKind.JOB_ACTION_KIND_ACTIVATE_SOURCE,
                JobActionKind.JOB_ACTION_KIND_COMPARE_CAPTURES,
                JobActionKind.JOB_ACTION_KIND_GET_CAPTURE_STATUS,
                JobActionKind.JOB_ACTION_KIND_RELOAD_SHADER,
                JobActionKind.JOB_ACTION_KIND_CAPTURE_PASS,
                JobActionKind.JOB_ACTION_KIND_CAPTURE_MULTI,
                JobActionKind.JOB_ACTION_KIND_GET_SHADER_STATUS,
                JobActionKind.JOB_ACTION_KIND_GET_SHADER_ERRORS,
                JobActionKind.JOB_ACTION_KIND_SCHEDULE_SCREENSHOT,
                JobActionKind.JOB_ACTION_KIND_GET_SCREENSHOT_RESULT,
                JobActionKind.JOB_ACTION_KIND_GET_GPU_METRICS,
                JobActionKind.JOB_ACTION_KIND_LIST_SSBOS,
                JobActionKind.JOB_ACTION_KIND_DUMP_SSBO,
                JobActionKind.JOB_ACTION_KIND_LIST_TEXTURES,
                JobActionKind.JOB_ACTION_KIND_DUMP_TEXTURE,
                JobActionKind.JOB_ACTION_KIND_LIST_PATCHED_SHADERS,
            ),
        )
        .addAllSupportedFormats(
            listOf(
                ArtifactFormat.ARTIFACT_FORMAT_PNG,
                ArtifactFormat.ARTIFACT_FORMAT_RAW,
                ArtifactFormat.ARTIFACT_FORMAT_BIN,
            ),
        )
        .build()

    private val baseHello = ServerHello.newBuilder()
        .setProtocolVersion(ProtocolMessages.V1)
        .setServerVersion("vibris-core")
        .addCapabilities(Capability.CAPABILITY_CONTROL_STREAM)
        .addCapabilities(Capability.CAPABILITY_RESUME)
        .addCapabilities(Capability.CAPABILITY_PREPARED_SOURCES)
        .addCapabilities(Capability.CAPABILITY_ACTION_SEQUENCE)
        .addCapabilities(Capability.CAPABILITY_ARTIFACT_METADATA)
        .setLimits(
            ServerLimits.newBuilder()
                .setMaxSourceBytes(maxSourceBytes)
                .setMaxSourceFiles(maxSourceFiles),
        )
        .addAllSupportedJobActions(baseStatus.supportedJobActionsList)
        .addAllSupportedFormats(baseStatus.supportedFormatsList)
        .setPendingShadersRoot(pending.toString())
        .setArtifactRoot(artifacts.root().toString())
        .build()

    fun status(engine: VibrisCoreEngine): ServerStatus {
        val current = runtimeStatus()
        val ready = engine.ready() && current.ready
        val activeSource = engine.activeSourceUuid()
        return baseStatus.toBuilder()
            .setState(
                if (ready) {
                    if (engine.queueLength() == 0) {
                        ServerState.SERVER_STATE_READY
                    } else {
                        ServerState.SERVER_STATE_BUSY
                    }
                } else {
                    ServerState.SERVER_STATE_FAILED
                },
            )
            .setRuntimeReady(ready)
            .setRuntimeState(
                if (ready) RuntimeState.RUNTIME_STATE_READY else RuntimeState.RUNTIME_STATE_FAILED,
            )
            .setCurrentSaveId(current.currentSaveId)
            .setCurrentDimensionId(current.currentDimensionId)
            .setActiveSourceUuid(if (activeSource.isBlank()) current.activeSourceUuid else activeSource)
            .setQueueLength(engine.queueLength())
            .setResourceCatalog(resourceCatalog())
            .setArtifactQuotaUsedBytes(artifacts.usedBytes())
            .build()
    }

    fun hello(engine: VibrisCoreEngine): ServerHello {
        val current = status(engine)
        return baseHello.toBuilder()
            .setReady(current.runtimeReady)
            .setStatus(current)
            .build()
    }

    private fun runtimeStatus(): RuntimeStatus =
        try {
            runtime.getStatus().toCompletableFuture().get(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            unavailable()
        } catch (_: Exception) {
            unavailable()
        }

    private fun resourceCatalog(): ResourceCatalog {
        val catalog = ResourceCatalog.newBuilder()
        for (resource in runtime.getResourceCatalog().resources) {
            val entry = ResourceCatalogEntry.newBuilder()
                .setLogicalName(resource.logicalName)
                .setKind(
                    when (resource.kind) {
                        dev.vibris.api.ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER ->
                            ResourceKind.RESOURCE_KIND_FINAL_FRAMEBUFFER
                        dev.vibris.api.ResourceCatalog.ResourceKind.TEXTURE ->
                            ResourceKind.RESOURCE_KIND_TEXTURE
                        dev.vibris.api.ResourceCatalog.ResourceKind.BUFFER ->
                            ResourceKind.RESOURCE_KIND_BUFFER
                    },
                )
                .setInternalFormat(resource.internalFormat)
                .build()
            if (resource.kind == dev.vibris.api.ResourceCatalog.ResourceKind.BUFFER) {
                catalog.addBuffers(entry)
            } else {
                catalog.addTextures(entry)
            }
        }
        return catalog.build()
    }

    private fun unavailable(): RuntimeStatus = RuntimeStatus(false, "", "", "")
}
