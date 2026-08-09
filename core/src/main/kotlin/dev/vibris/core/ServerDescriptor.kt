package dev.vibris.core

import dev.vibris.api.RuntimeStatus
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v1.ArtifactFormat
import dev.vibris.protocol.v1.Capability
import dev.vibris.protocol.v1.JobActionKind
import dev.vibris.protocol.v1.JobStage
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
    @Volatile
    private var lastReadyStatus: RuntimeStatus? = null

    private val baseStatus = ServerStatus.newBuilder()
        .setPendingShadersRoot(pending.toString())
        .setArtifactRoot(artifacts.root().toString())
        .setArtifactQuotaCapBytes(artifacts.quotaBytes())
        .addAllSupportedJobActions(
            listOf(
                JobActionKind.JOB_ACTION_KIND_WAIT_FRAMES,
                JobActionKind.JOB_ACTION_KIND_TAKE_SCREENSHOT,
                JobActionKind.JOB_ACTION_KIND_DUMP_TEXTURE_V2,
                JobActionKind.JOB_ACTION_KIND_DUMP_BUFFER,
                JobActionKind.JOB_ACTION_KIND_ACTIVATE_SOURCE,
                JobActionKind.JOB_ACTION_KIND_COMPARE_CAPTURES,
                JobActionKind.JOB_ACTION_KIND_GET_CAPTURE_STATUS,
                JobActionKind.JOB_ACTION_KIND_CAPTURE_PASS,
                JobActionKind.JOB_ACTION_KIND_CAPTURE_MULTI,
                JobActionKind.JOB_ACTION_KIND_INSPECT_SHADER,
                JobActionKind.JOB_ACTION_KIND_GET_GPU_METRICS,
                JobActionKind.JOB_ACTION_KIND_LIST_TEXTURES_V2,
                JobActionKind.JOB_ACTION_KIND_LIST_BUFFERS,
                JobActionKind.JOB_ACTION_KIND_GET_PATCHED_SHADERS,
                JobActionKind.JOB_ACTION_KIND_LOAD_SHADER,
            ),
        )
        .addAllSupportedFormats(
            listOf(
                ArtifactFormat.ARTIFACT_FORMAT_PNG,
                ArtifactFormat.ARTIFACT_FORMAT_BIN,
                ArtifactFormat.ARTIFACT_FORMAT_TEXT,
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
        var activeJob = engine.activeJob()
        var current = if (activeJob == null) runtimeStatus() else cachedRuntimeStatus()
        if (!current.ready && activeJob == null) {
            activeJob = engine.activeJob()
            if (activeJob != null) current = cachedRuntimeStatus()
        }
        val ready = engine.ready() && current.ready
        val activeSource = engine.activeSourceUuid()
        val queueLength = engine.queueLength()
        return baseStatus.toBuilder()
            .setState(
                when {
                    !engine.ready() -> ServerState.SERVER_STATE_FAILED
                    activeJob != null || queueLength > 0 -> ServerState.SERVER_STATE_BUSY
                    ready -> ServerState.SERVER_STATE_READY
                    else -> ServerState.SERVER_STATE_FAILED
                },
            )
            .setActiveRequestId(activeJob?.requestId.orEmpty())
            .setRuntimeReady(ready)
            .setRuntimeState(runtimeState(engine.ready(), activeJob, current.ready))
            .setCurrentSaveId(current.currentSaveId)
            .setCurrentDimensionId(current.currentDimensionId)
            .setActiveSourceUuid(if (activeSource.isBlank()) current.activeSourceUuid else activeSource)
            .setQueueLength(queueLength)
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

    private fun runtimeStatus(): RuntimeStatus {
        val current = try {
            runtime.getStatus().toCompletableFuture().get(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            unavailable()
        } catch (_: Exception) {
            unavailable()
        }
        if (current.ready) {
            lastReadyStatus = current
        }
        return current
    }

    private fun cachedRuntimeStatus(): RuntimeStatus = lastReadyStatus ?: unavailable()

    private fun runtimeState(
        engineReady: Boolean,
        activeJob: VibrisCoreEngine.ActiveJob?,
        runtimeReady: Boolean,
    ): RuntimeState {
        if (!engineReady) return RuntimeState.RUNTIME_STATE_FAILED
        if (activeJob == null) {
            return if (runtimeReady) RuntimeState.RUNTIME_STATE_READY else RuntimeState.RUNTIME_STATE_FAILED
        }
        return when (activeJob.stage) {
            JobStage.JOB_STAGE_ACTIVATING_SOURCE,
            JobStage.JOB_STAGE_RELOADING_SHADERS,
            -> RuntimeState.RUNTIME_STATE_RELOADING_SHADERS
            JobStage.JOB_STAGE_LOADING_WORLD,
            JobStage.JOB_STAGE_APPLYING_CONTEXT,
            -> RuntimeState.RUNTIME_STATE_LOADING_WORLD
            else -> if (runtimeReady) RuntimeState.RUNTIME_STATE_READY else RuntimeState.RUNTIME_STATE_NOT_READY
        }
    }

    private fun resourceCatalog(): ResourceCatalog {
        val catalog = ResourceCatalog.newBuilder()
        for (resource in runtime.getResourceCatalog().resources) {
            if (resource.kind == dev.vibris.api.ResourceCatalog.ResourceKind.PATCHED_SHADERS) continue
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
                        dev.vibris.api.ResourceCatalog.ResourceKind.PATCHED_SHADERS ->
                            ResourceKind.RESOURCE_KIND_PATCHED_SHADERS
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