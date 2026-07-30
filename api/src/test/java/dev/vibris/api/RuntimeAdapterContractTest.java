package dev.vibris.api;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAdapterContractTest {
    @Test
    void adapterUsesTypedAsynchronousOperations() {
        SceneContext context = new SceneContext(
            "test-save",
            "minecraft:overworld",
            "sunset",
            "clear",
            "village-rooftop",
            70.0,
            new SceneContext.Resolution(1920, 1080),
            "default"
        );
        CancellationToken.Source cancellation = CancellationToken.source();
        TestAdapter adapter = new TestAdapter();

        assertTrue(adapter.getStatus().toCompletableFuture().join().ready());
        assertEquals(context, adapter.ensureWorldAndContext(context, cancellation.token())
            .toCompletableFuture().join().context());
        assertTrue(adapter.reloadVibrisShaderpack(null, cancellation.token()).toCompletableFuture().join().successful());
        assertEquals(7, adapter.waitRenderedFrames(7, cancellation.token()).toCompletableFuture().join());
        assertEquals(9, adapter.capture(
            CapturePlan.empty(), name -> new ByteArrayOutputStream(), cancellation.token()
        ).toCompletableFuture().join().frameId());
    }

    @Test
    void cancellationIsObservableWithoutRuntimeDependencies() {
        CancellationToken.Source source = CancellationToken.source();
        assertTrue(!source.token().isCancellationRequested());

        source.cancel();

        assertTrue(source.token().isCancellationRequested());
        assertThrows(java.util.concurrent.CancellationException.class, source.token()::throwIfCancellationRequested);
    }

    @Test
    void canonicalConstructorsDefensivelySnapshotCollections() {
        var target = new CapturePlan.Target(
            ResourceCatalog.ResourceKind.TEXTURE,
            "colortex0",
            CapturePlan.ArtifactFormat.PNG,
            "colortex0",
            0,
            0
        );
        var targets = new ArrayList<>(List.of(target));
        CapturePlan plan = new CapturePlan(targets);
        targets.clear();
        assertEquals(List.of(target), plan.targets());
        assertThrows(UnsupportedOperationException.class, () -> plan.targets().clear());

        var descriptor = new ResourceCatalog.ResourceDescriptor(
            "colortex0", ResourceCatalog.ResourceKind.TEXTURE
        );
        var artifacts = new LinkedHashMap<String, ResourceCatalog.ResourceDescriptor>();
        artifacts.put("colortex0", descriptor);
        CaptureResult result = new CaptureResult(1, artifacts);
        artifacts.clear();
        assertEquals(Map.of("colortex0", descriptor), result.artifacts());
        assertThrows(UnsupportedOperationException.class, () -> result.artifacts().clear());

        var errors = new ArrayList<>(List.of("invalid context"));
        ContextValidationResult validation = new ContextValidationResult(false, errors);
        errors.clear();
        assertEquals(List.of("invalid context"), validation.errors());
        assertThrows(UnsupportedOperationException.class, () -> validation.errors().clear());

        var diagnostic = new ReloadResult.Diagnostic(
            ReloadResult.Severity.ERROR, "program.glsl", 7, "compile failed"
        );
        var diagnostics = new ArrayList<>(List.of(diagnostic));
        ReloadResult reload = new ReloadResult(false, true, diagnostics);
        diagnostics.clear();
        assertEquals(List.of(diagnostic), reload.diagnostics());
        assertThrows(UnsupportedOperationException.class, () -> reload.diagnostics().clear());

        var resources = new ArrayList<>(List.of(descriptor));
        ResourceCatalog catalog = new ResourceCatalog(resources);
        resources.clear();
        assertEquals(List.of(descriptor), catalog.resources());
        assertThrows(UnsupportedOperationException.class, () -> catalog.resources().clear());
    }

    @Test
    void publicValueValidationRemainsIntact() {
        assertThrows(IllegalArgumentException.class, () -> new SceneContext.Resolution(0, 1080));
        assertTrue(!SceneContext.Resolution.unspecified().isSpecified());
        assertThrows(IllegalArgumentException.class, () -> new SceneContext(
            "save", "dimension", "time", "weather", "camera", Double.NaN,
            new SceneContext.Resolution(1, 1), "settings"
        ));
    }

    @Test
    void kotlinDataCarriersPreserveJavaRecordAbi() {
        assertRecord(CapturePlan.class, "targets");
        assertRecord(CaptureResult.class, "frameId", "artifacts");
        assertRecord(ContextValidationResult.class, "valid", "errors");
        assertRecord(ReloadResult.class, "successful", "activeStatePreserved", "diagnostics");
        assertRecord(ResourceCatalog.class, "resources");
    }

    private static void assertRecord(Class<?> type, String... components) {
        assertTrue(type.isRecord(), type.getName() + " must remain a Java record");
        assertEquals(java.lang.Record.class, type.getSuperclass());
        assertEquals(List.of(components), Arrays.stream(type.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList());
    }

    private static final class TestAdapter implements VibrisRuntimeAdapter {
        @Override
        public CompletionStage<RuntimeStatus> getStatus() {
            return CompletableFuture.completedFuture(new RuntimeStatus(true, "test-save", "minecraft:overworld", ""));
        }

        @Override
        public CompletionStage<ContextApplyResult> ensureWorldAndContext(
            SceneContext context,
            CancellationToken cancellation
        ) {
            return CompletableFuture.completedFuture(ContextApplyResult.success(context));
        }

        @Override
        public CompletionStage<ReloadResult> reloadVibrisShaderpack(
            Map<String, String> config, CancellationToken cancellation
        ) {
            return CompletableFuture.completedFuture(ReloadResult.success(List.of()));
        }

        @Override
        public CompletionStage<TemporalResetResult> resetTemporalState(CancellationToken cancellation) {
            return CompletableFuture.completedFuture(new TemporalResetResult(true));
        }

        @Override
        public CompletionStage<Long> waitRenderedFrames(int frameCount, CancellationToken cancellation) {
            return CompletableFuture.completedFuture((long) frameCount);
        }

        @Override
        public ResourceCatalog getResourceCatalog() {
            return ResourceCatalog.empty();
        }

        @Override
        public CompletionStage<CaptureResult> capture(
            CapturePlan plan,
            ArtifactSink sink,
            CancellationToken cancellation
        ) {
            return CompletableFuture.completedFuture(new CaptureResult(9, Map.of()));
        }

        @Override
        public void close() {
        }
    }
}
