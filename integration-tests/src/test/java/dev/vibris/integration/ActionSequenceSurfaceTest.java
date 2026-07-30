package dev.vibris.integration;

import dev.vibris.core.VibrisBootstrap;
import dev.vibris.protocol.v1.Action;
import dev.vibris.protocol.v1.ActivateSource;
import dev.vibris.protocol.v1.ActionSequence;
import dev.vibris.protocol.v1.ArtifactFormat;
import dev.vibris.protocol.v1.CaptureScreenshot;
import dev.vibris.protocol.v1.CompareCaptures;
import dev.vibris.protocol.v1.DumpBuffer;
import dev.vibris.protocol.v1.DumpTexture;
import dev.vibris.protocol.v1.JobCompleted;
import dev.vibris.protocol.v1.JobResultKind;
import dev.vibris.protocol.v1.JobTimeouts;
import dev.vibris.protocol.v1.PreparedSourceRef;
import dev.vibris.protocol.v1.ResetTemporalState;
import dev.vibris.protocol.v1.SubmitJob;
import dev.vibris.protocol.v1.WaitFrames;
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

final class ActionSequenceSurfaceTest {
    private static final String WORKSPACE = "action-sequence-tests";

    @TempDir
    Path temporaryDirectory;

    @Test
    void reloadAndDebugBundle() throws Exception {
        Fixture fixture = new Fixture();
        try (VibrisBootstrap bootstrap = fixture.start();
             IntegrationHarness.Client client = new IntegrationHarness.Client(bootstrap.port(), WORKSPACE)) {
            PreparedSourceRef reloadSource = IntegrationHarness.createSource(fixture.pendingRoot, "reload").reference();
            client.submit(submission("reload", reloadSource).setActions(ActionSequence.newBuilder()
                .addActions(activate(reloadSource))
                .addActions(reset())
                .addActions(waitFrames(2))
                .addActions(Action.newBuilder().setCaptureScreenshot(CaptureScreenshot.newBuilder()
                    .setArtifactName("screenshot").setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)))).build());
            JobCompleted reload = client.awaitCompleted("reload");

            assertEquals(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE, reload.getResult().getKind());
            assertEquals(3, reload.getResult().getArtifactsCount());
            assertEquals(1, reload.getResult().getFrameIdsCount());
            assertEquals(1, reload.getResult().getShaderDiagnosticsCount());
            assertTrue(reload.getResult().getTimings().getStartedAtUnixMs() > 0);
            assertTrue(reload.getResult().getTimings().getCompletedAtUnixMs() >=
                reload.getResult().getTimings().getStartedAtUnixMs());
            assertTrue(Files.isReadable(Path.of(reload.getResult().getManifestPath())));

            PreparedSourceRef bundleSource = IntegrationHarness.createSource(fixture.pendingRoot, "bundle").reference();
            client.submit(submission("bundle", bundleSource).setActions(ActionSequence.newBuilder()
                .addActions(activate(bundleSource))
                .addActions(reset())
                .addActions(waitFrames(2))
                .addActions(Action.newBuilder().setCaptureScreenshot(CaptureScreenshot.newBuilder()
                    .setArtifactName("screenshot").setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)))
                .addActions(Action.newBuilder().setDumpTexture(DumpTexture.newBuilder()
                    .setLogicalName("colortex0").setArtifactName("colortex0")
                    .setFormat(ArtifactFormat.ARTIFACT_FORMAT_RAW)))
                .addActions(Action.newBuilder().setDumpBuffer(DumpBuffer.newBuilder()
                    .setLogicalName("radiance_cache").setArtifactName("radiance_cache")
                    .setFormat(ArtifactFormat.ARTIFACT_FORMAT_BIN)))).build());
            JobCompleted bundle = client.awaitCompleted("bundle");

            assertEquals(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE, bundle.getResult().getKind());
            assertEquals(5, bundle.getResult().getArtifactsCount());
            assertEquals(1, bundle.getResult().getFrameIdsCount());
            long frameId = bundle.getResult().getFrameIds(0);
            bundle.getResult().getArtifactsList().stream().filter(artifact -> artifact.hasResource())
                .forEach(artifact -> assertEquals(frameId, artifact.getResource().getFrameId()));
        }
    }

    @Test
    void twoSourceComparisonIsOneNonInterleavedJob() throws Exception {
        Fixture fixture = new Fixture();
        fixture.runtime.baselineCaptureStarted = new CountDownLatch(1);
        fixture.runtime.releaseBaselineCapture = new CountDownLatch(1);
        try (VibrisBootstrap bootstrap = fixture.start();
             IntegrationHarness.Client abClient = new IntegrationHarness.Client(bootstrap.port(), WORKSPACE);
             IntegrationHarness.Client competitor = new IntegrationHarness.Client(bootstrap.port(), "competitor")) {
            IntegrationHarness.Source sourceA = IntegrationHarness.createSource(fixture.pendingRoot, "A");
            IntegrationHarness.Source sourceB = IntegrationHarness.createSource(fixture.pendingRoot, "B");
            IntegrationHarness.Source sourceC = IntegrationHarness.createSource(fixture.pendingRoot, "C");
            fixture.runtime.baselineDirectory = sourceA.directory();

            ActionSequence abActions = ActionSequence.newBuilder()
                .addActions(activate(sourceA.reference()))
                .addActions(reset())
                .addActions(waitFrames(1))
                .addActions(Action.newBuilder().setCaptureScreenshot(CaptureScreenshot.newBuilder()
                    .setArtifactName("a-0").setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)))
                .addActions(activate(sourceB.reference()))
                .addActions(reset())
                .addActions(waitFrames(1))
                .addActions(Action.newBuilder().setCaptureScreenshot(CaptureScreenshot.newBuilder()
                    .setArtifactName("b-0").setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)))
                .addActions(Action.newBuilder().setCompareCaptures(CompareCaptures.newBuilder()
                    .setBaselineCaptureIndex(0).setCandidateCaptureIndex(1)
                    .setBaselineLabel("baseline").setCandidateLabel("candidate")))
                .build();
            SubmitJob ab = SubmitJob.newBuilder().setRequestId("ab").setWorkspaceId(WORKSPACE)
                .setContext(IntegrationHarness.context("ab"))
                .addSources(sourceA.reference()).addSources(sourceB.reference())
                .setActions(abActions)
                .setTimeouts(timeouts()).build();
            abClient.submit(ab);
            abClient.awaitAccepted("ab");
            assertTrue(fixture.runtime.baselineCaptureStarted.await(
                IntegrationHarness.WAIT.toMillis(), TimeUnit.MILLISECONDS));

            competitor.submit(SubmitJob.newBuilder().setRequestId("competitor").setWorkspaceId("competitor")
                .setContext(IntegrationHarness.context("competitor")).addSources(sourceC.reference())
                .setActions(ActionSequence.newBuilder().addActions(activate(sourceC.reference())))
                .setTimeouts(timeouts()).build());
            competitor.awaitAccepted("competitor");
            fixture.runtime.releaseBaselineCapture.countDown();

            JobCompleted completed = abClient.awaitCompleted("ab");
            assertEquals(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE, completed.getResult().getKind());
            assertEquals(List.of(101L, 102L), completed.getResult().getFrameIdsList());
            assertEquals("baseline", completed.getResult().getComparison().getBaselineLabel());
            assertEquals("candidate", completed.getResult().getComparison().getCandidateLabel());
            assertEquals(List.of("compiled A", "compiled B"), completed.getResult().getShaderDiagnosticsList()
                .stream().map(diagnostic -> diagnostic.getMessage()).toList());
            Path metrics = artifact(completed, "diff.json");
            Path heatmap = artifact(completed, "diff-heatmap.png");
            assertTrue(Files.readString(metrics).contains("mean_absolute_error"));
            assertNotNull(ImageIO.read(heatmap.toFile()));
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
            .setContext(IntegrationHarness.context(requestId)).addSources(source).setTimeouts(timeouts());
    }

    private static Action activate(PreparedSourceRef source) {
        return Action.newBuilder().setActivateSource(
            ActivateSource.newBuilder().setSourceUuid(source.getUuid())).build();
    }

    private static Action reset() {
        return Action.newBuilder().setResetTemporalState(ResetTemporalState.getDefaultInstance()).build();
    }

    private static Action waitFrames(int frames) {
        return Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(frames)).build();
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
        final CaptureTestRuntime runtime = new CaptureTestRuntime(shaderLink);

        VibrisBootstrap start() throws VibrisBootstrap.Failure {
            return VibrisBootstrap.start(new VibrisBootstrap.Config(
                0, pendingRoot, artifactRoot, shaderLink), runtime);
        }
    }
}
