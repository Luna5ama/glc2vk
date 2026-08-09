package dev.vibris.integration;

import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.RuntimeStatus;
import dev.vibris.api.RuntimeAction;
import dev.vibris.api.SceneContext;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.api.VibrisRuntimeAdapter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class CaptureTestRuntime implements VibrisRuntimeAdapter {
    private final Path shaderLink;
    private final Map<String, ResourceCatalog.ResourceDescriptor> resources;
    private final AtomicLong frame = new AtomicLong(100);
    final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
    volatile CountDownLatch baselineCaptureStarted;
    volatile CountDownLatch releaseBaselineCapture;
    volatile Path baselineDirectory;
    volatile boolean baselineDeletedBeforeCandidateCapture;
    private volatile String active = "none";

    CaptureTestRuntime(Path shaderLink) {
        this.shaderLink = shaderLink.resolve("shaders");
        resources = Map.of(
            "beauty", resource("beauty", ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER, 4, 16),
            "colortex0", resource("colortex0", ResourceCatalog.ResourceKind.TEXTURE, 4, 16),
            "radiance_cache", resource("radiance_cache", ResourceCatalog.ResourceKind.BUFFER, 0, 16));
    }

    @Override
    public CompletionStage<RuntimeStatus> getStatus() {
        return CompletableFuture.completedFuture(new RuntimeStatus(true, "test-save", "minecraft:overworld", ""));
    }

    @Override
    public CompletionStage<ContextApplyResult> ensureWorldAndContext(
        SceneContext context, CancellationToken cancellation
    ) {
        events.add("context:" + active);
        return CompletableFuture.completedFuture(ContextApplyResult.success(context));
    }

    @Override
    public CompletionStage<ReloadResult> reloadVibrisShaderpack(
        Map<String, String> config, CancellationToken cancellation
    ) {
        try {
            String source = Files.readString(shaderLink.resolve("main.glsl"), StandardCharsets.UTF_8).trim();
            active = source.startsWith("// ") ? source.substring(3) : source;
            events.add("reload:" + active);
            return CompletableFuture.completedFuture(ReloadResult.success(List.of(
                new ReloadResult.Diagnostic(ReloadResult.Severity.INFO, "main.glsl", 1, "compiled " + active))));
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletionStage<TemporalResetResult> resetTemporalState(CancellationToken cancellation) {
        events.add("reset:" + active);
        return CompletableFuture.completedFuture(new TemporalResetResult(true));
    }

    @Override
    public CompletionStage<String> executeAction(RuntimeAction action) {
        if (action == RuntimeAction.InspectShader.INSTANCE) {
            return CompletableFuture.completedFuture(
                "{\"status\":\"ok\",\"pack_loaded\":true,\"shaderpack\":\"vibris\",\"errors\":[]}");
        }
        return VibrisRuntimeAdapter.super.executeAction(action);
    }

    @Override
    public CompletionStage<Long> waitRenderedFrames(int frameCount, CancellationToken cancellation) {
        events.add("frames:" + frameCount + ':' + active);
        return CompletableFuture.completedFuture(frame.get());
    }

    @Override
    public ResourceCatalog getResourceCatalog() {
        return new ResourceCatalog(List.copyOf(resources.values()));
    }

    @Override
    public CompletionStage<CaptureResult> capture(
        CapturePlan plan, ArtifactSink sink, CancellationToken cancellation
    ) {
        try {
            if (active.equals("A") && baselineCaptureStarted != null) {
                baselineCaptureStarted.countDown();
                if (!releaseBaselineCapture.await(5, TimeUnit.SECONDS)) {
                    return CompletableFuture.failedFuture(new IOException("baseline capture gate timed out"));
                }
            }
            if (active.equals("B") && baselineDirectory != null) {
                baselineDeletedBeforeCandidateCapture = !Files.exists(baselineDirectory);
            }
            long captureFrame = frame.incrementAndGet();
            Map<String, ResourceCatalog.ResourceDescriptor> captured = new LinkedHashMap<>();
            for (CapturePlan.Target target : plan.targets()) {
                ResourceCatalog.ResourceDescriptor descriptor = withFrame(
                    resources.get(target.logicalName()), captureFrame);
                write(sink, target, descriptor, active);
                captured.put(target.artifactName(), descriptor);
            }
            events.add("capture:" + plan.targets().stream()
                .map(CapturePlan.Target::artifactName).toList() + ':' + active);
            return CompletableFuture.completedFuture(new CaptureResult(captureFrame, captured));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public void close() {
    }

    private static void write(ArtifactSink sink, CapturePlan.Target target,
        ResourceCatalog.ResourceDescriptor descriptor, String marker) throws IOException {
        try (OutputStream output = sink.open(target.fileName())) {
            if (target.format() == CapturePlan.ArtifactFormat.PNG) {
                BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
                int color = marker.equals("A") ? 0xff000000 : marker.equals("B") ? 0xffffffff : 0xff336699;
                for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, color);
                if (!ImageIO.write(image, "png", output)) throw new IOException("PNG writer unavailable");
            } else {
                output.write(new byte[Math.toIntExact(descriptor.byteSize())]);
            }
        }
        if (target.format() == CapturePlan.ArtifactFormat.RAW || target.format() == CapturePlan.ArtifactFormat.BIN) {
            try (OutputStream output = sink.open(target.metadataFileName())) {
                output.write(("{\"frame_id\":" + descriptor.frameId() + "}").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static ResourceCatalog.ResourceDescriptor resource(
        String name, ResourceCatalog.ResourceKind kind, int channels, long bytes
    ) {
        int size = kind == ResourceCatalog.ResourceKind.BUFFER ? 0 : 2;
        return new ResourceCatalog.ResourceDescriptor(name, kind, size, size, size == 0 ? 0 : 1, 1, 1,
            kind == ResourceCatalog.ResourceKind.BUFFER ? "binary" : "RGBA8", channels,
            ResourceCatalog.ScalarType.UINT8, bytes, 0, name);
    }

    private static ResourceCatalog.ResourceDescriptor withFrame(
        ResourceCatalog.ResourceDescriptor resource, long frameId
    ) {
        return new ResourceCatalog.ResourceDescriptor(resource.logicalName(), resource.kind(), resource.width(),
            resource.height(), resource.depth(), resource.mipLevels(), resource.layers(), resource.internalFormat(),
            resource.channelCount(), resource.scalarType(), resource.byteSize(), frameId, resource.semanticLabel());
    }
}
