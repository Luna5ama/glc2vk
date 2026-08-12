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
    void runtimeEnvironmentRejectsBlankIdentityValues() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeEnvironment(
            "", "iris", "vibris", "java", "os", "vendor", "renderer", "opengl", "driver"
        ));
    }

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

        assertEquals("test-minecraft", adapter.getRuntimeEnvironment().toCompletableFuture().join().minecraftVersion());
        assertTrue(adapter.getStatus().toCompletableFuture().join().ready());
        assertEquals(context, adapter.ensureWorldAndContext(context, cancellation.token())
            .toCompletableFuture().join().context());
        assertTrue(adapter.reloadVibrisShaderpack(null, cancellation.token())
            .toCompletableFuture().join().successful());
        assertEquals(0, adapter.getCompileCatalog(cancellation.token())
            .toCompletableFuture().join().shaderGeneration());
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
            new CapturePlan.ResourceSelector(
                ResourceCatalog.ResourceKind.TEXTURE,
                "colortex0",
                ResourceCatalog.TextureView.CURRENT,
                0,
                0
            ),
            CapturePlan.ArtifactFormat.PNG,
            "colortex0",
            List.of()
        );
        var targets = new ArrayList<>(List.of(target));
        CapturePlan plan = new CapturePlan(targets);
        targets.clear();
        assertEquals(List.of(target), plan.targets());
        assertThrows(UnsupportedOperationException.class, () -> plan.targets().clear());

        var descriptor = textureDescriptor("colortex0", 1);
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
        ResourceCatalog catalog = ResourceCatalog.of(resources, List.of());
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
    void namedPassResourceContractIsCanonicalAndStrict() {
        var texture = ResourceCatalog.ResourceDescriptor.of(
            "colortex0",
            ResourceCatalog.ResourceKind.TEXTURE,
            List.of(ResourceCatalog.TextureView.ALT, ResourceCatalog.TextureView.CURRENT),
            1920, 1080, 1, 4, 1, "RGBA16F", 4, ResourceCatalog.ScalarType.FLOAT16,
            1920L * 1080 * 8, 7, "", "render_target", "TEXTURE_2D", "RGBA", "float", 16,
            "RGBA", "HALF_FLOAT"
        );
        var buffer = ResourceCatalog.ResourceDescriptor.of(
            "sceneData",
            ResourceCatalog.ResourceKind.BUFFER,
            List.of(),
            0, 0, 0, 0, 0, "binary", 0, ResourceCatalog.ScalarType.UNSPECIFIED,
            4096, 7, "", "shader_storage", "", "", "", 0, "", ""
        );
        var pass = ResourceCatalog.PassDescriptor.of(
            ResourceCatalog.PassStage.COMPOSITE,
            "composite21",
            3,
            List.of("sceneData", "colortex0")
        );

        ResourceCatalog catalog = ResourceCatalog.of(List.of(buffer, texture), List.of(pass));
        assertEquals(List.of("colortex0", "sceneData"),
            catalog.resources().stream().map(ResourceCatalog.ResourceDescriptor::logicalName).toList());
        assertEquals(List.of(ResourceCatalog.TextureView.CURRENT, ResourceCatalog.TextureView.ALT),
            catalog.resources().get(0).availableViews());
        assertEquals("composite/composite21", catalog.passes().get(0).passId());
        assertEquals(catalog.mappingSha256(),
            ResourceCatalog.of(List.of(texture, buffer), List.of(pass)).mappingSha256());

        var target = new CapturePlan.Target(
            new CapturePlan.ResourceSelector(
                ResourceCatalog.ResourceKind.TEXTURE,
                "colortex0",
                ResourceCatalog.TextureView.CURRENT,
                1,
                0
            ),
            CapturePlan.ArtifactFormat.PNG,
            "shade-diffuse",
            List.of()
        );
        var request = new CapturePlan.AfterPassRequest(catalog.mappingSha256(), pass, target);
        assertEquals("composite/composite21", request.pass().passId());
        assertThrows(IllegalArgumentException.class, () -> new CapturePlan.ResourceSelector(
            ResourceCatalog.ResourceKind.TEXTURE, "colortex0.main", ResourceCatalog.TextureView.MAIN, 0, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> new CapturePlan.ResourceSelector(
            ResourceCatalog.ResourceKind.BUFFER, "sceneData", ResourceCatalog.TextureView.CURRENT, 0, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> ResourceCatalog.PassDescriptor.of(
            ResourceCatalog.PassStage.COMPOSITE, "composite21", 3, List.of("colortex0.main")
        ));
        assertThrows(IllegalArgumentException.class, () -> ResourceCatalog.of(
            List.of(texture, texture), List.of(pass)
        ));
    }

    @Test
    void compileCatalogCoversEveryTerminalStateAndHasStableIdentities() {
        String sourceHash = "a".repeat(64);
        var compileError = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.ERROR, "composite.fsh", 17, 3, "compile failed", "compile.log"
        );
        var linkError = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.ERROR, "final", 0, 0, "link failed", "link.log"
        );
        var warning = CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.WARNING, "composite.fsh", 4, 1, "unused value"
        );
        var graphics = CompileCatalog.ProgramEntry.of(
            "graphics", "composite", List.of(CompileCatalog.ShaderStage.FRAGMENT, CompileCatalog.ShaderStage.VERTEX),
            CompileCatalog.CompileState.SUCCEEDED, CompileCatalog.CompileState.SUCCEEDED,
            sourceHash, List.of(warning)
        );
        var compute = CompileCatalog.ProgramEntry.of(
            "compute", "prepare", List.of(CompileCatalog.ShaderStage.COMPUTE),
            CompileCatalog.CompileState.SUCCEEDED, CompileCatalog.CompileState.SUCCEEDED,
            "b".repeat(64), List.of()
        );
        var missing = CompileCatalog.ProgramEntry.of(
            "missing", "shadow", List.of(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
            CompileCatalog.CompileState.NOT_PRESENT, CompileCatalog.CompileState.NOT_PRESENT, "", List.of()
        );
        var compileFailed = CompileCatalog.ProgramEntry.of(
            "compile-failed", "deferred",
            List.of(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
            CompileCatalog.CompileState.FAILED, CompileCatalog.CompileState.NOT_APPLICABLE,
            "c".repeat(64), List.of(warning, compileError)
        );
        var linkFailed = CompileCatalog.ProgramEntry.of(
            "link-failed", "final", List.of(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
            CompileCatalog.CompileState.SUCCEEDED, CompileCatalog.CompileState.FAILED,
            "d".repeat(64), List.of(linkError)
        );

        List<CompileCatalog.ProgramEntry> unordered = new ArrayList<>(
            List.of(missing, graphics, linkFailed, compute, compileFailed)
        );
        CompileCatalog first = CompileCatalog.of(unordered, 42);
        java.util.Collections.reverse(unordered);
        CompileCatalog second = CompileCatalog.of(unordered, 42);

        assertEquals(List.of("compile-failed", "compute", "graphics", "link-failed", "missing"),
            first.programs().stream().map(CompileCatalog.ProgramEntry::programId).toList());
        assertEquals(List.of(CompileCatalog.ShaderStage.VERTEX, CompileCatalog.ShaderStage.FRAGMENT),
            graphics.stages());
        assertEquals(first.mappingSha256(), second.mappingSha256());
        assertEquals(first.programs(), second.programs());
        assertEquals(compileError.fingerprintSha256(), CompileCatalog.Diagnostic.of(
            CompileCatalog.DiagnosticSeverity.ERROR, "composite.fsh", 17, 3, "compile failed", "other.log"
        ).fingerprintSha256());
        assertEquals(List.of(compileError.fingerprintSha256(), warning.fingerprintSha256()).stream().sorted().toList(),
            compileFailed.diagnostics().stream().map(CompileCatalog.Diagnostic::fingerprintSha256).toList());
        assertThrows(UnsupportedOperationException.class, () -> first.programs().clear());
        assertThrows(UnsupportedOperationException.class, () -> graphics.stages().clear());
        assertThrows(IllegalArgumentException.class, () -> new CompileCatalog(
            first.programs(), "0".repeat(64), 42
        ));
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
        assertRecord(CapturePlan.ResourceSelector.class, "kind", "logicalName", "textureView", "mipLevel", "layer");
        assertRecord(CapturePlan.Target.class, "resource", "format", "artifactName", "outputs");
        assertRecord(CapturePlan.AfterPassRequest.class, "mappingSha256", "pass", "target");
        assertRecord(CapturePlan.AfterPassReceipt.class, "request", "passOccurrence", "physicalName", "capture");
        assertRecord(CaptureResult.class, "frameId", "groups");
        assertRecord(CompileCatalog.class, "programs", "mappingSha256", "shaderGeneration");
        assertRecord(CompileCatalog.ProgramEntry.class, "programId", "passId", "stages", "compileState",
            "linkState", "patchedSourceSha256", "diagnostics");
        assertRecord(CompileCatalog.Diagnostic.class, "severity", "fileName", "line", "column", "message",
            "fingerprintSha256", "logPath");
        assertRecord(ContextValidationResult.class, "valid", "errors");
        assertRecord(EffectiveShaderSettings.class, "settings", "settingsSha256");
        assertRecord(EffectiveShaderSettings.Setting.class, "name", "value", "defaultValue", "origin");
        assertRecord(ReloadResult.class, "successful", "activeStatePreserved", "effectiveSettings", "diagnostics");
        assertRecord(ResourceCatalog.class, "resources", "passes", "mappingSha256");
        assertRecord(RuntimeEnvironment.class, "minecraftVersion", "irisVersion", "vibrisVersion", "javaVersion",
            "operatingSystem", "gpuVendor", "gpuRenderer", "openglVersion", "driverVersion");
        assertRecord(ResourceCatalog.PassDescriptor.class,
            "passId", "stage", "programId", "order", "readableResources");
        assertRecord(ResourceCatalog.ResourceDescriptor.class,
            "logicalName", "kind", "availableViews", "width", "height", "depth", "mipLevels", "layers",
            "internalFormat", "channelCount", "scalarType", "byteSize", "frameId", "semanticLabel", "category",
            "textureTarget", "channelLayout", "numericClass", "componentBits", "readbackFormat", "readbackType");
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
        public CompletionStage<RuntimeEnvironment> getRuntimeEnvironment() {
            return CompletableFuture.completedFuture(new RuntimeEnvironment(
                "test-minecraft", "test-iris", "test-vibris", "test-java", "test-os",
                "test-gpu-vendor", "test-gpu-renderer", "test-opengl", "test-driver"
            ));
        }

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
        public CompletionStage<CompileCatalog> getCompileCatalog(CancellationToken cancellation) {
            return CompletableFuture.completedFuture(CompileCatalog.empty(0));
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
        public CompletionStage<CapturePlan.AfterPassReceipt> captureAfterPass(
            CapturePlan.AfterPassRequest request,
            ArtifactSink sink,
            CancellationToken cancellation
        ) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("not used"));
        }

        @Override
        public void close() {
        }
    }

    private static ResourceCatalog.ResourceDescriptor textureDescriptor(String name, long frameId) {
        return ResourceCatalog.ResourceDescriptor.of(
            name,
            ResourceCatalog.ResourceKind.TEXTURE,
            List.of(ResourceCatalog.TextureView.CURRENT),
            1, 1, 1, 1, 1, "RGBA8", 4, ResourceCatalog.ScalarType.UINT8,
            4, frameId, name, "render_target", "TEXTURE_2D", "RGBA", "unorm", 8, "RGBA", "UNSIGNED_BYTE"
        );
    }
}
