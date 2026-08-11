package dev.vibris.core

import dev.vibris.api.RuntimeStatus
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.ArtifactCapacity
import dev.vibris.protocol.v2.Capability
import dev.vibris.protocol.v2.ResourceCatalog
import dev.vibris.protocol.v2.ResourceDescriptor
import dev.vibris.protocol.v2.ResourceKind
import dev.vibris.protocol.v2.RuntimePhase
import dev.vibris.protocol.v2.RuntimeReadiness
import dev.vibris.protocol.v2.ScalarType
import dev.vibris.protocol.v2.ServerHello
import dev.vibris.protocol.v2.ServerLimits
import dev.vibris.protocol.v2.ServerState
import dev.vibris.protocol.v2.ServerStatus
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class ServerDescriptor @JvmOverloads constructor(
    pending: Path,
    private val artifacts: ArtifactManager,
    private val runtime: VibrisRuntimeAdapter,
    maxSourceBytes: Long = ServerConfiguration.DEFAULT_MAX_SOURCE_BYTES,
    maxSourceFiles: Int = ServerConfiguration.DEFAULT_MAX_SOURCE_FILES,
    maxQueuedJobs: Int = ServerConfiguration.DEFAULT_MAX_GLOBAL_QUEUE,
    maxActionsPerJob: Int = ServerConfiguration.DEFAULT_MAX_ACTIONS_PER_JOB,
) {
    @Volatile
    private var lastReadyStatus: RuntimeStatus? = null

    private val baseHello = ServerHello.newBuilder()
        .setServerVersion("vibris-core")
        .addCapabilities(Capability.CAPABILITY_CONTROL_STREAM)
        .setLimits(
            ServerLimits.newBuilder()
                .setMaxSourceBytes(maxSourceBytes)
                .setMaxSourceFiles(maxSourceFiles)
                .setMaxQueuedJobs(maxQueuedJobs)
                .setMaxActionsPerJob(maxActionsPerJob)
                .setMaxStatusWaitMs(0),
        )
        .setPendingSourceRoot(pending.toString())
        .build()

    fun status(engine: VibrisCoreEngine): ServerStatus {
        var activeJob = engine.activeJob()
        var current = if (activeJob == null) runtimeStatus() else cachedRuntimeStatus()
        if (!current.ready && activeJob == null) {
            activeJob = engine.activeJob()
            if (activeJob != null) current = cachedRuntimeStatus()
        }
        val coreReady = engine.ready()
        val runtimeReady = current.ready
        val available = coreReady && runtimeReady
        val queueLength = engine.queueLength()
        val phase = runtimePhase(coreReady, activeJob, runtimeReady)
        val state = when {
            !coreReady -> ServerState.SERVER_STATE_FAILED
            activeJob != null || queueLength > 0 -> ServerState.SERVER_STATE_OCCUPIED
            runtimeReady -> ServerState.SERVER_STATE_AVAILABLE
            else -> ServerState.SERVER_STATE_FAILED
        }
        val activeSource = engine.activeSourceUuid().ifBlank { current.activeSourceUuid }
        return ServerStatus.newBuilder()
            .setState(state)
            .setReadiness(
                RuntimeReadiness.newBuilder()
                    .setCoreOnline(coreReady)
                    .setMinecraftConnected(runtimeReady)
                    .setWorldLoaded(runtimeReady && current.currentSaveId.isNotBlank())
                    .setSceneApplied(runtimeReady && current.currentDimensionId.isNotBlank())
                    .setShaderReloadComplete(available)
                    .setGpuTimingAvailable(false)
                    .setPhase(phase)
                    .setDetail(readinessDetail(coreReady, current)),
            )
            .setCanAcceptJob(coreReady)
            .setCanStartJob(available && activeJob == null && queueLength == 0)
            .setArtifactCapacity(
                ArtifactCapacity.newBuilder()
                    .setCapBytes(artifacts.quotaBytes())
                    .setUsedBytes(artifacts.usedBytes())
                    .setFits(artifacts.usedBytes() <= artifacts.quotaBytes()),
            )
            .setActiveSourceUuid(activeSource)
            .build()
    }

    fun hello(engine: VibrisCoreEngine): ServerHello = baseHello.toBuilder()
        .setStatus(status(engine))
        .build()

    fun resources(): ResourceCatalog {
        val catalog = ResourceCatalog.newBuilder()
        runtime.getResourceCatalog().resources.forEach { resource ->
            catalog.addResources(
                ResourceDescriptor.newBuilder()
                    .setLogicalName(resource.logicalName)
                    .setKind(resourceKind(resource.kind))
                    .setWidth(resource.width)
                    .setHeight(resource.height)
                    .setDepth(resource.depth)
                    .setMipLevels(resource.mipLevels)
                    .setLayers(resource.layers)
                    .setInternalFormat(resource.internalFormat)
                    .setChannelCount(resource.channelCount)
                    .setScalarType(ScalarType.valueOf("SCALAR_TYPE_" + resource.scalarType.name))
                    .setByteSize(resource.byteSize)
                    .setFrameId(resource.frameId)
                    .build(),
            )
        }
        return catalog.build()
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
        if (current.ready) lastReadyStatus = current
        return current
    }

    private fun cachedRuntimeStatus(): RuntimeStatus = lastReadyStatus ?: unavailable()

    private fun runtimePhase(
        coreReady: Boolean,
        activeJob: VibrisCoreEngine.ActiveJob?,
        runtimeReady: Boolean,
    ): RuntimePhase {
        if (!coreReady) return RuntimePhase.RUNTIME_PHASE_FAILED
        if (activeJob == null) {
            return if (runtimeReady) RuntimePhase.RUNTIME_PHASE_AVAILABLE else RuntimePhase.RUNTIME_PHASE_DISCONNECTED
        }
        return when (activeJob.stage) {
            dev.vibris.protocol.v2.JobStage.JOB_STAGE_ACTIVATING_SOURCE,
            dev.vibris.protocol.v2.JobStage.JOB_STAGE_COMPILING,
            -> RuntimePhase.RUNTIME_PHASE_RELOADING_SHADERS
            dev.vibris.protocol.v2.JobStage.JOB_STAGE_LOADING_WORLD -> RuntimePhase.RUNTIME_PHASE_LOADING_WORLD
            dev.vibris.protocol.v2.JobStage.JOB_STAGE_APPLYING_CONTEXT -> RuntimePhase.RUNTIME_PHASE_APPLYING_SCENE
            dev.vibris.protocol.v2.JobStage.JOB_STAGE_RESTORING -> RuntimePhase.RUNTIME_PHASE_RESTORING
            dev.vibris.protocol.v2.JobStage.JOB_STAGE_RECOVERING -> RuntimePhase.RUNTIME_PHASE_RECOVERING
            else -> RuntimePhase.RUNTIME_PHASE_EXECUTING
        }
    }

    private fun readinessDetail(coreReady: Boolean, current: RuntimeStatus): String = when {
        !coreReady -> "Core source activation is unavailable."
        !current.ready -> "Minecraft runtime is unavailable."
        else -> "Runtime is available."
    }

    private fun resourceKind(kind: dev.vibris.api.ResourceCatalog.ResourceKind): ResourceKind = when (kind) {
        dev.vibris.api.ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER ->
            ResourceKind.RESOURCE_KIND_FINAL_FRAMEBUFFER
        dev.vibris.api.ResourceCatalog.ResourceKind.TEXTURE -> ResourceKind.RESOURCE_KIND_TEXTURE
        dev.vibris.api.ResourceCatalog.ResourceKind.BUFFER -> ResourceKind.RESOURCE_KIND_BUFFER
        dev.vibris.api.ResourceCatalog.ResourceKind.PATCHED_SHADERS ->
            ResourceKind.RESOURCE_KIND_PATCHED_SHADERS
    }

    private fun unavailable(): RuntimeStatus = RuntimeStatus(false, "", "", "")
}
