package dev.vibris.integration;

import dev.vibris.api.ArtifactSink;
import dev.vibris.api.CancellationToken;
import dev.vibris.api.CapturePlan;
import dev.vibris.api.CaptureResult;
import dev.vibris.api.ContextApplyResult;
import dev.vibris.api.ReloadResult;
import dev.vibris.api.ResourceCatalog;
import dev.vibris.api.RuntimeStatus;
import dev.vibris.api.SceneContext;
import dev.vibris.api.TemporalResetResult;
import dev.vibris.api.VibrisRuntimeAdapter;
import dev.vibris.core.VibrisBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisSourceLifecycleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void startSwitchClose() throws Exception {
        Path pendingRoot = temporaryDirectory.resolve("pending");
        Path shaderpackRoot = temporaryDirectory.resolve("shaderpacks/vibris");
        Path shaderLink = shaderpackRoot.resolve("shaders");
        Files.createDirectories(pendingRoot.resolve("stale"));
        Files.writeString(pendingRoot.resolve("stale/orphan.glsl"), "stale");
        LifecycleRuntime runtime = new LifecycleRuntime();
        VibrisBootstrap bootstrap = VibrisBootstrap.start(
            new VibrisBootstrap.Config(0, pendingRoot, temporaryDirectory.resolve("artifacts"), shaderpackRoot),
            runtime
        );

        try {
            assertTrue(bootstrap.port() > 0);
            IntegrationHarness.assertDirectoryEmpty(pendingRoot);
            assertFalse(Files.exists(shaderLink));

            try (var client = new IntegrationHarness.Client(bootstrap.port(), "source-lifecycle-tests")) {
                var sourceA = IntegrationHarness.createSource(pendingRoot, "lifecycle-source-a");
                client.submit(IntegrationHarness.job(
                    "lifecycle-a", "source-lifecycle-tests", IntegrationHarness.context("a"),
                    sourceA.reference(), 5_000, 1));
                client.awaitCompleted("lifecycle-a");

                assertEquals(sourceA.directory(), Files.readSymbolicLink(shaderLink));
                assertTrue(Files.isDirectory(sourceA.directory()));

                var sourceB = IntegrationHarness.createSource(pendingRoot, "lifecycle-source-b");
                client.submit(IntegrationHarness.job(
                    "lifecycle-b", "source-lifecycle-tests", IntegrationHarness.context("b"),
                    sourceB.reference(), 5_000, 1));
                client.awaitCompleted("lifecycle-b");

                assertEquals(sourceB.directory(), Files.readSymbolicLink(shaderLink));
                assertFalse(Files.exists(sourceA.directory()));
                assertTrue(Files.isDirectory(sourceB.directory()));
            }
        } finally {
            bootstrap.close();
            bootstrap.close();
        }

        assertFalse(Files.exists(shaderLink));
        IntegrationHarness.assertDirectoryEmpty(pendingRoot);
        assertEquals(1, runtime.closeCount);
    }

    private static final class LifecycleRuntime implements VibrisRuntimeAdapter {
        private int closeCount;

        @Override
        public CompletionStage<RuntimeStatus> getStatus() {
            return CompletableFuture.completedFuture(
                new RuntimeStatus(true, "test-save", "minecraft:overworld", ""));
        }

        @Override
        public CompletionStage<ContextApplyResult> ensureWorldAndContext(
            SceneContext context,
            CancellationToken cancellation
        ) {
            return completed(cancellation, ContextApplyResult.success(context));
        }

        @Override
        public CompletionStage<ReloadResult> reloadVibrisShaderpack(CancellationToken cancellation) {
            return completed(cancellation, ReloadResult.success(List.of()));
        }

        @Override
        public CompletionStage<TemporalResetResult> resetTemporalState(CancellationToken cancellation) {
            return completed(cancellation, new TemporalResetResult(true));
        }

        @Override
        public CompletionStage<Long> waitRenderedFrames(int frameCount, CancellationToken cancellation) {
            return completed(cancellation, (long) frameCount);
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
            return completed(cancellation, new CaptureResult(0, Map.of()));
        }

        @Override
        public void close() {
            closeCount++;
        }

        private static <T> CompletionStage<T> completed(CancellationToken cancellation, T value) {
            cancellation.throwIfCancellationRequested();
            return CompletableFuture.completedFuture(value);
        }
    }
}