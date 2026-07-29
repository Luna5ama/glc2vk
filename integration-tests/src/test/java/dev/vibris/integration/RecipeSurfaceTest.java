package dev.vibris.integration;

import dev.vibris.core.VibrisBootstrap;
import dev.vibris.protocol.v1.AbCompareRecipe;
import dev.vibris.protocol.v1.ActionSequence;
import dev.vibris.protocol.v1.ArtifactFormat;
import dev.vibris.protocol.v1.CaptureDebugBundleRecipe;
import dev.vibris.protocol.v1.CaptureTarget;
import dev.vibris.protocol.v1.CaptureTargetKind;
import dev.vibris.protocol.v1.JobCompleted;
import dev.vibris.protocol.v1.JobResultKind;
import dev.vibris.protocol.v1.JobTimeouts;
import dev.vibris.protocol.v1.PreparedSourceRef;
import dev.vibris.protocol.v1.RecipeSpec;
import dev.vibris.protocol.v1.ReloadAndCaptureRecipe;
import dev.vibris.protocol.v1.SourceVariant;
import dev.vibris.protocol.v1.SubmitJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecipeSurfaceTest {
    private static final String WORKSPACE = "phase-six-recipes";

    @TempDir
    Path temporaryDirectory;

    @Test
    void reloadAndDebugBundle() throws Exception {
        Fixture fixture = new Fixture();
        try (VibrisBootstrap bootstrap = fixture.start();
             PhaseThreeHarness.Client client = new PhaseThreeHarness.Client(bootstrap.port(), WORKSPACE)) {
            PreparedSourceRef reloadSource = PhaseThreeHarness.createSource(fixture.pendingRoot, "reload").reference();
            client.submit(submission("reload", reloadSource).setRecipe(RecipeSpec.newBuilder()
                .setReloadAndCapture(ReloadAndCaptureRecipe.newBuilder()
                    .setSourceUuid(reloadSource.getUuid()).setWarmupFrames(2)
                    .setScreenshotFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG))).build());
            JobCompleted reload = client.awaitCompleted("reload");

            assertEquals(JobResultKind.JOB_RESULT_KIND_RELOAD_AND_CAPTURE, reload.getResult().getKind());
            assertEquals(3, reload.getResult().getArtifactsCount());
            assertEquals(1, reload.getResult().getFrameIdsCount());
            assertEquals(1, reload.getResult().getShaderDiagnosticsCount());
            assertTrue(reload.getResult().getTimings().getStartedAtUnixMs() > 0);
            assertTrue(reload.getResult().getTimings().getCompletedAtUnixMs() >=
                reload.getResult().getTimings().getStartedAtUnixMs());
            assertTrue(Files.isReadable(Path.of(reload.getResult().getManifestPath())));

            PreparedSourceRef bundleSource = PhaseThreeHarness.createSource(fixture.pendingRoot, "bundle").reference();
            client.submit(submission("bundle", bundleSource).setRecipe(RecipeSpec.newBuilder()
                .setCaptureDebugBundle(CaptureDebugBundleRecipe.newBuilder()
                    .setSourceUuid(bundleSource.getUuid()).setWarmupFrames(2).setScreenshot(true)
                    .addTextures("colortex0").addBuffers("radiance_cache"))).build());
            JobCompleted bundle = client.awaitCompleted("bundle");

            assertEquals(JobResultKind.JOB_RESULT_KIND_CAPTURE_DEBUG_BUNDLE, bundle.getResult().getKind());
            assertEquals(5, bundle.getResult().getArtifactsCount());
            assertEquals(1, bundle.getResult().getFrameIdsCount());
            long frameId = bundle.getResult().getFrameIds(0);
            bundle.getResult().getArtifactsList().stream().filter(artifact -> artifact.hasResource())
                .forEach(artifact -> assertEquals(frameId, artifact.getResource().getFrameId()));
        }
    }

    @Test
    void abCompareIsOneNonInterleavedJob() throws Exception {
        Fixture fixture = new Fixture();
        fixture.runtime.baselineCaptureStarted = new CountDownLatch(1);
        fixture.runtime.releaseBaselineCapture = new CountDownLatch(1);
        try (VibrisBootstrap bootstrap = fixture.start();
             PhaseThreeHarness.Client abClient = new PhaseThreeHarness.Client(bootstrap.port(), WORKSPACE);
             PhaseThreeHarness.Client competitor = new PhaseThreeHarness.Client(bootstrap.port(), "competitor")) {
            PhaseThreeHarness.Source sourceA = PhaseThreeHarness.createSource(fixture.pendingRoot, "A");
            PhaseThreeHarness.Source sourceB = PhaseThreeHarness.createSource(fixture.pendingRoot, "B");
            PhaseThreeHarness.Source sourceC = PhaseThreeHarness.createSource(fixture.pendingRoot, "C");
            fixture.runtime.baselineDirectory = sourceA.directory();

            SubmitJob ab = SubmitJob.newBuilder().setRequestId("ab").setWorkspaceId(WORKSPACE)
                .setContext(PhaseThreeHarness.context("ab"))
                .addSources(sourceA.reference()).addSources(sourceB.reference())
                .setRecipe(RecipeSpec.newBuilder().setAbCompare(AbCompareRecipe.newBuilder()
                    .setBaseline(SourceVariant.newBuilder().setLabel("baseline")
                        .setSourceUuid(sourceA.reference().getUuid()))
                    .setCandidate(SourceVariant.newBuilder().setLabel("candidate")
                        .setSourceUuid(sourceB.reference().getUuid()))
                    .setWarmupFrames(1)
                    .addCaptures(CaptureTarget.newBuilder().setKind(CaptureTargetKind.CAPTURE_TARGET_KIND_SCREENSHOT)
                        .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG))))
                .setTimeouts(timeouts()).build();
            abClient.submit(ab);
            abClient.awaitAccepted("ab");
            assertTrue(fixture.runtime.baselineCaptureStarted.await(
                PhaseThreeHarness.WAIT.toMillis(), TimeUnit.MILLISECONDS));

            competitor.submit(SubmitJob.newBuilder().setRequestId("competitor").setWorkspaceId("competitor")
                .setContext(PhaseThreeHarness.context("competitor")).addSources(sourceC.reference())
                .setActions(ActionSequence.getDefaultInstance()).setTimeouts(timeouts()).build());
            competitor.awaitAccepted("competitor");
            fixture.runtime.releaseBaselineCapture.countDown();

            JobCompleted completed = abClient.awaitCompleted("ab");
            assertEquals(JobResultKind.JOB_RESULT_KIND_AB_COMPARE, completed.getResult().getKind());
            assertEquals(List.of(101L, 102L), completed.getResult().getFrameIdsList());
            assertEquals("baseline", completed.getResult().getComparison().getBaselineLabel());
            assertEquals("candidate", completed.getResult().getComparison().getCandidateLabel());
            assertEquals(List.of("compiled A", "compiled B"), completed.getResult().getShaderDiagnosticsList()
                .stream().map(diagnostic -> diagnostic.getMessage()).toList());
            Path metrics = artifact(completed, "diff.json");
            Path heatmap = artifact(completed, "diff-heatmap.png");
            assertTrue(Files.readString(metrics).contains("mean_absolute_error"));
            assertNotNull(ImageIO.read(heatmap.toFile()));
            assertTrue(fixture.runtime.baselineDeletedBeforeCandidateCapture);
            assertFalse(Files.exists(sourceA.directory()));

            competitor.awaitCompleted("competitor");
            int candidateCapture = fixture.runtime.events.indexOf("capture:[b-0]:B");
            int competitorReload = fixture.runtime.events.indexOf("reload:C");
            assertTrue(candidateCapture >= 0 && competitorReload > candidateCapture,
                () -> "Unexpected execution trace: " + fixture.runtime.events);
        }
    }

    private static SubmitJob.Builder submission(String requestId, PreparedSourceRef source) {
        return SubmitJob.newBuilder().setRequestId(requestId).setWorkspaceId(WORKSPACE)
            .setContext(PhaseThreeHarness.context(requestId)).addSources(source).setTimeouts(timeouts());
    }

    private static JobTimeouts timeouts() {
        return JobTimeouts.newBuilder().setExecutionTimeoutMs(10_000).setTotalTimeoutMs(15_000).build();
    }

    private static Path artifact(JobCompleted completed, String fileName) {
        return completed.getResult().getArtifactsList().stream()
            .filter(artifact -> artifact.getFileName().equals(fileName)).map(artifact -> Path.of(artifact.getPath()))
            .filter(Files::isReadable).findFirst().orElseThrow();
    }

    private final class Fixture {
        final Path pendingRoot = temporaryDirectory.resolve("pending");
        final Path artifactRoot = temporaryDirectory.resolve("artifacts");
        final Path shaderLink = temporaryDirectory.resolve("shaderpacks/vibris");
        final PhaseSixRuntime runtime = new PhaseSixRuntime(shaderLink);

        VibrisBootstrap start() throws VibrisBootstrap.Failure {
            return VibrisBootstrap.start(new VibrisBootstrap.Config(
                0, pendingRoot, artifactRoot, shaderLink), runtime);
        }
    }
}