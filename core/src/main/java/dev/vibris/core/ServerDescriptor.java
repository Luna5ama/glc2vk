package dev.vibris.core;

import dev.vibris.api.RuntimeStatus;
import dev.vibris.api.VibrisRuntimeAdapter;
import dev.vibris.protocol.v1.ActionKind;
import dev.vibris.protocol.v1.ArtifactFormat;
import dev.vibris.protocol.v1.Capability;
import dev.vibris.protocol.v1.RecipeKind;
import dev.vibris.protocol.v1.RuntimeState;
import dev.vibris.protocol.v1.ResourceCatalog;
import dev.vibris.protocol.v1.ResourceCatalogEntry;
import dev.vibris.protocol.v1.ResourceKind;
import dev.vibris.protocol.v1.ServerHello;
import dev.vibris.protocol.v1.ServerLimits;
import dev.vibris.protocol.v1.ServerState;
import dev.vibris.protocol.v1.ServerStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ServerDescriptor {
    private final VibrisRuntimeAdapter runtime;
    private final ArtifactManager artifacts;
    private final ServerStatus baseStatus;
    private final ServerHello baseHello;

    ServerDescriptor(Path pending, ArtifactManager artifacts, VibrisRuntimeAdapter runtime) {
        this.runtime = runtime;
        this.artifacts = artifacts;
        baseStatus = ServerStatus.newBuilder()
            .setPendingShadersRoot(pending.toString())
            .setArtifactRoot(artifacts.root().toString())
            .setArtifactQuotaCapBytes(artifacts.quotaBytes())
            .addAllSupportedRecipes(List.of(
                RecipeKind.RECIPE_KIND_RELOAD_AND_CAPTURE,
                RecipeKind.RECIPE_KIND_CAPTURE_DEBUG_BUNDLE,
                RecipeKind.RECIPE_KIND_AB_COMPARE))
            .addAllSupportedActions(List.of(
                ActionKind.ACTION_KIND_RESET_TEMPORAL_STATE,
                ActionKind.ACTION_KIND_WAIT_FRAMES,
                ActionKind.ACTION_KIND_CAPTURE_SCREENSHOT,
                ActionKind.ACTION_KIND_DUMP_TEXTURE,
                ActionKind.ACTION_KIND_DUMP_BUFFER))
            .addAllSupportedFormats(List.of(
                ArtifactFormat.ARTIFACT_FORMAT_PNG,
                ArtifactFormat.ARTIFACT_FORMAT_RAW,
                ArtifactFormat.ARTIFACT_FORMAT_BIN))
            .build();
        baseHello = ServerHello.newBuilder()
            .setProtocolVersion(ProtocolMessages.V1)
            .setServerVersion("vibris-core")
            .addCapabilities(Capability.CAPABILITY_CONTROL_STREAM)
            .addCapabilities(Capability.CAPABILITY_RESUME)
            .addCapabilities(Capability.CAPABILITY_PREPARED_SOURCES)
            .setLimits(ServerLimits.newBuilder().setMaxSourceBytes(512L * 1024 * 1024).setMaxSourceFiles(100_000))
            .addAllSupportedRecipes(baseStatus.getSupportedRecipesList())
            .addAllSupportedActions(baseStatus.getSupportedActionsList())
            .addAllSupportedFormats(baseStatus.getSupportedFormatsList())
            .setPendingShadersRoot(pending.toString())
            .setArtifactRoot(artifacts.root().toString())
            .build();
    }

    ServerStatus status(VibrisCoreEngine engine) {
        RuntimeStatus current = runtimeStatus();
        boolean ready = engine.ready() && current.ready();
        String activeSource = engine.activeSourceUuid();
        return baseStatus.toBuilder()
            .setState(ready
                ? engine.queueLength() == 0 ? ServerState.SERVER_STATE_READY : ServerState.SERVER_STATE_BUSY
                : ServerState.SERVER_STATE_FAILED)
            .setRuntimeReady(ready)
            .setRuntimeState(ready ? RuntimeState.RUNTIME_STATE_READY : RuntimeState.RUNTIME_STATE_FAILED)
            .setCurrentSaveId(current.currentSaveId())
            .setCurrentDimensionId(current.currentDimensionId())
            .setActiveSourceUuid(activeSource.isBlank() ? current.activeSourceUuid() : activeSource)
            .setQueueLength(engine.queueLength())
            .setResourceCatalog(resourceCatalog())
            .setArtifactQuotaUsedBytes(artifacts.usedBytes())
            .build();
    }

    ServerHello hello(VibrisCoreEngine engine) {
        ServerStatus current = status(engine);
        return baseHello.toBuilder().setReady(current.getRuntimeReady()).setStatus(current).build();
    }

    private RuntimeStatus runtimeStatus() {
        try {
            return runtime.getStatus().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailable();
        } catch (Exception exception) {
            return unavailable();
        }
    }

    private ResourceCatalog resourceCatalog() {
        ResourceCatalog.Builder catalog = ResourceCatalog.newBuilder();
        for (dev.vibris.api.ResourceCatalog.ResourceDescriptor resource :
            runtime.getResourceCatalog().resources()) {
            ResourceCatalogEntry entry = ResourceCatalogEntry.newBuilder()
                .setLogicalName(resource.logicalName())
                .setKind(switch (resource.kind()) {
                    case FINAL_FRAMEBUFFER -> ResourceKind.RESOURCE_KIND_FINAL_FRAMEBUFFER;
                    case TEXTURE -> ResourceKind.RESOURCE_KIND_TEXTURE;
                    case BUFFER -> ResourceKind.RESOURCE_KIND_BUFFER;
                })
                .setInternalFormat(resource.internalFormat())
                .build();
            if (resource.kind() == dev.vibris.api.ResourceCatalog.ResourceKind.BUFFER) {
                catalog.addBuffers(entry);
            } else {
                catalog.addTextures(entry);
            }
        }
        return catalog.build();
    }

    private static RuntimeStatus unavailable() {
        return new RuntimeStatus(false, "", "", "");
    }
}