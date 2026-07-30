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
             IntegrationHarness.Client owner = new IntegrationHarness.Client(server, "workspace-a");
             IntegrationHarness.Client foreign = new IntegrationHarness.Client(server, "workspace-b")) {
            IntegrationHarness.Probe probe = new IntegrationHarness.Probe(server);
            probe.pauseExecution();
            IntegrationHarness.Source source = IntegrationHarness.createSource(pending, "shared-request");
            SceneContext context = IntegrationHarness.context("shared-request");
            SubmitJob ownerJob = IntegrationHarness.job(
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
             IntegrationHarness.Client client = new IntegrationHarness.Client(server, "workspace-owner")) {
            IntegrationHarness.Probe probe = new IntegrationHarness.Probe(server);
            probe.pauseExecution();
            IntegrationHarness.Source source = IntegrationHarness.createSource(pending, "malformed-envelope");
            SubmitJob job = IntegrationHarness.job(
                "malformed-envelope", "workspace-owner", IntegrationHarness.context("malformed-envelope"),
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