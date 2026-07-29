package dev.vibris.integration;

import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobStage;
import dev.vibris.protocol.v1.JobState;
import dev.vibris.protocol.v1.SceneContext;
import dev.vibris.protocol.v1.SubmitJob;
import dev.vibris.testruntime.FakeVibrisServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceIsolationTest {
    @TempDir
    Path temp;

    @Test
    void foreignWorkspaceCannotObserveCancelOrResumeRequest() throws Exception {
        Path pending = temp.resolve("pending");
        try (FakeVibrisServer server = FakeVibrisServer.start(0, pending, temp.resolve("artifacts"));
             PhaseThreeHarness.Client owner = new PhaseThreeHarness.Client(server, "workspace-a");
             PhaseThreeHarness.Client foreign = new PhaseThreeHarness.Client(server, "workspace-b")) {
            PhaseThreeHarness.Probe probe = new PhaseThreeHarness.Probe(server);
            probe.pauseExecution();
            PhaseThreeHarness.Source source = PhaseThreeHarness.createSource(pending, "shared-request");
            SceneContext context = PhaseThreeHarness.context("shared-request");
            SubmitJob ownerJob = PhaseThreeHarness.job(
                "shared-request", "workspace-a", context, source.reference(), 5_000, 1_000);
            SubmitJob foreignJob = ownerJob.toBuilder().setWorkspaceId("workspace-b").build();

            owner.submit(ownerJob);
            owner.awaitAccepted("shared-request");
            owner.awaitProgress("shared-request", JobStage.JOB_STAGE_WARMING_UP);

            foreign.submit(foreignJob);
            foreign.awaitFailed("shared-request", ErrorCode.INTERNAL_ERROR);
            foreign.cancel("shared-request");
            assertTrue(foreign.resumeState("shared-request").getJobsList().isEmpty());
            assertEquals(JobState.JOB_STATE_RUNNING, owner.resume("shared-request").getState());

            probe.resumeExecution();
            owner.awaitCompleted("shared-request");
            foreign.submit(foreignJob);
            foreign.awaitFailed("shared-request", ErrorCode.INTERNAL_ERROR);
            assertEquals(1, probe.executionCount("shared-request"));
        }
    }

    @Test
    void malformedEnvelopeDisconnectsAcceptedJobAndReclaimsItsSource() throws Exception {
        Path pending = temp.resolve("pending-malformed");
        try (FakeVibrisServer server = FakeVibrisServer.start(0, pending, temp.resolve("artifacts-malformed"));
             PhaseThreeHarness.Client client = new PhaseThreeHarness.Client(server, "workspace-owner")) {
            PhaseThreeHarness.Probe probe = new PhaseThreeHarness.Probe(server);
            probe.pauseExecution();
            PhaseThreeHarness.Source source = PhaseThreeHarness.createSource(pending, "malformed-envelope");
            SubmitJob job = PhaseThreeHarness.job(
                "malformed-envelope", "workspace-owner", PhaseThreeHarness.context("malformed-envelope"),
                source.reference(), 5_000, 1_000);

            client.submit(job);
            client.awaitAccepted("malformed-envelope");
            client.sendWorkspaceViolation("malformed-envelope");
            client.awaitStreamFailure();

            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(4).toNanos();
            while (Files.exists(source.directory()) && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertFalse(Files.exists(source.directory()), "protocol failure stranded an accepted source");
        }
    }
}