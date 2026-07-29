package dev.vibris.integration;

import dev.vibris.core.VibrisBootstrap;
import dev.vibris.protocol.v1.Action;
import dev.vibris.protocol.v1.ActionSequence;
import dev.vibris.protocol.v1.ArtifactFormat;
import dev.vibris.protocol.v1.CaptureScreenshot;
import dev.vibris.protocol.v1.JobCompleted;
import dev.vibris.protocol.v1.JobResultKind;
import dev.vibris.protocol.v1.JobTimeouts;
import dev.vibris.protocol.v1.PreparedSourceRef;
import dev.vibris.protocol.v1.ResetTemporalState;
import dev.vibris.protocol.v1.SubmitJob;
import dev.vibris.protocol.v1.WaitFrames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class ActionSurfaceTest {
    private static final String WORKSPACE = "phase-six-actions";

    @TempDir
    Path temporaryDirectory;

    @Test
    void allowedSequenceAndForbiddenActions() throws Exception {
        Path pendingRoot = temporaryDirectory.resolve("pending");
        Path shaderLink = temporaryDirectory.resolve("shaderpacks/vibris");
        PhaseSixRuntime runtime = new PhaseSixRuntime(shaderLink);
        try (VibrisBootstrap bootstrap = VibrisBootstrap.start(new VibrisBootstrap.Config(
            0, pendingRoot, temporaryDirectory.resolve("artifacts"), shaderLink), runtime);
             PhaseThreeHarness.Client client = new PhaseThreeHarness.Client(bootstrap.port(), WORKSPACE)) {
            PreparedSourceRef source = PhaseThreeHarness.createSource(pendingRoot, "actions").reference();
            ActionSequence actions = ActionSequence.newBuilder()
                .addActions(Action.newBuilder().setResetTemporalState(ResetTemporalState.getDefaultInstance()))
                .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(1)))
                .addActions(Action.newBuilder().setCaptureScreenshot(CaptureScreenshot.newBuilder()
                    .setArtifactName("first").setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)))
                .addActions(Action.newBuilder().setWaitFrames(WaitFrames.newBuilder().setFrameCount(2)))
                .addActions(Action.newBuilder().setCaptureScreenshot(CaptureScreenshot.newBuilder()
                    .setArtifactName("second").setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)))
                .build();
            client.submit(submission("sequence", source).setActions(actions).build());
            JobCompleted completed = client.awaitCompleted("sequence");

            assertEquals(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE, completed.getResult().getKind());
            assertEquals(List.of(101L, 102L), completed.getResult().getFrameIdsList());
            assertEquals(List.of(
                "reload:actions", "context:actions", "reset:actions", "frames:1:actions",
                "capture:[first]:actions", "frames:2:actions", "capture:[second]:actions"),
                List.copyOf(runtime.events));

            runtime.events.clear();
            PreparedSourceRef empty = PhaseThreeHarness.createSource(pendingRoot, "empty").reference();
            client.submit(submission("empty", empty).setActions(ActionSequence.getDefaultInstance()).build());
            JobCompleted emptyResult = client.awaitCompleted("empty");
            assertEquals(JobResultKind.JOB_RESULT_KIND_ACTION_SEQUENCE, emptyResult.getResult().getKind());
            assertEquals(List.of("reload:empty", "context:empty"), List.copyOf(runtime.events));
            assertFalse(emptyResult.getResult().hasComparison());

            List<String> supported = Action.getDescriptor().getOneofs().getFirst().getFields().stream()
                .map(field -> field.getName()).toList();
            assertEquals(List.of("reset_temporal_state", "wait_frames", "capture_screenshot",
                "dump_texture", "dump_buffer"), supported);
            assertFalse(supported.stream().anyMatch(name -> name.contains("source") || name.contains("reload") ||
                name.contains("shell") || name.contains("renderdoc")));
        }
    }

    private static SubmitJob.Builder submission(String requestId, PreparedSourceRef source) {
        return SubmitJob.newBuilder().setRequestId(requestId).setWorkspaceId(WORKSPACE)
            .setContext(PhaseThreeHarness.context(requestId)).addSources(source)
            .setTimeouts(JobTimeouts.newBuilder().setExecutionTimeoutMs(10_000).setTotalTimeoutMs(15_000));
    }
}