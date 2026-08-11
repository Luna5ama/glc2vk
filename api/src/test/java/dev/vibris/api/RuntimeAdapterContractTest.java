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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        var groups = new ArrayList<>(List.of(
            new CaptureResult.ArtifactGroup("colortex0", descriptor, List.of())
        ));
        CaptureResult result = new CaptureResult(1, groups);
        groups.clear();
        assertEquals(1, result.groups().size());
        assertThrows(UnsupportedOperationException.class, () -> result.groups().clear());

        var errors = new ArrayList<>(List.of("invalid context"));
        ContextValidationResult validation = new ContextValidationResult(false, errors);
        errors.clear();
        assertEquals(List.of("invalid context"), validation.errors());
        assertThrows(UnsupportedOperationException.class, () -> validation.errors().clear());

        var diagnostic = new ReloadResult.Diagnostic(
            ReloadResult.Severity.ERROR, "program.glsl", 7, "compile failed"
        );
        var diagnostics = new ArrayList<>(List.of(diagnostic));
        ReloadResult reload = new ReloadResult(false, true, EffectiveShaderSettings.empty(), diagnostics);
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
    void effectiveSettingsAreCompleteCanonicalAndDeterministic() {
        var quality = new EffectiveShaderSettings.Setting(
            "QUALITY", "high", "medium", EffectiveShaderSettings.Origin.REQUEST_OVERRIDE
        );
        var shadows = new EffectiveShaderSettings.Setting(
            "SHADOWS", "true", "true", EffectiveShaderSettings.Origin.DEFAULT
        );
        var input = new ArrayList<>(List.of(shadows, quality));

        EffectiveShaderSettings override = EffectiveShaderSettings.of(input);
        input.clear();
        assertEquals(List.of(quality, shadows), override.settings());
        assertEquals(Map.of("QUALITY", "high", "SHADOWS", "true"), override.values());
        assertEquals(List.of(quality), override.changedFromDefault());
        assertThrows(UnsupportedOperationException.class, () -> override.settings().clear());

        EffectiveShaderSettings preserved = EffectiveShaderSettings.of(List.of(
            new EffectiveShaderSettings.Setting(
                "SHADOWS", "true", "true", EffectiveShaderSettings.Origin.PRESERVED_CURRENT
            ),
            new EffectiveShaderSettings.Setting(
                "QUALITY", "high", "medium", EffectiveShaderSettings.Origin.PRESERVED_CURRENT
            )
        ));
        assertEquals(override.settingsSha256(), preserved.settingsSha256());
        assertTrue(override.hasSameResolvedState(preserved));
        assertNotEquals(override.settings(), preserved.settings());

        assertThrows(IllegalArgumentException.class, () -> EffectiveShaderSettings.of(List.of(
            quality,
            new EffectiveShaderSettings.Setting(
                "QUALITY", "low", "medium", EffectiveShaderSettings.Origin.PRESET
            )
        )));
        assertThrows(IllegalArgumentException.class, () -> new EffectiveShaderSettings(
            List.of(quality), "0".repeat(64)
        ));
        assertThrows(NullPointerException.class, () -> ReloadResult.success(null, List.of()));
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
        assertRecord(CaptureResult.class, "frameId", "groups");
        assertRecord(ContextValidationResult.class, "valid", "errors");
        assertRecord(EffectiveShaderSettings.class, "settings", "settingsSha256");
        assertRecord(EffectiveShaderSettings.Setting.class, "name", "value", "defaultValue", "origin");
        assertRecord(ReloadResult.class, "successful", "activeStatePreserved", "effectiveSettings", "diagnostics");
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
            return CompletableFuture.completedFuture(ReloadResult.success(EffectiveShaderSettings.empty(), List.of()));
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
            return CompletableFuture.completedFuture(new CaptureResult(9, List.of()));
        }

        @Override
        public void close() {
        }
    }
}