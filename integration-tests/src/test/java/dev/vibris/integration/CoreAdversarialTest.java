package dev.vibris.integration;

import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobCompleted;
import dev.vibris.protocol.v1.JobStage;
import dev.vibris.protocol.v1.JobState;
import dev.vibris.protocol.v1.SceneContext;
import dev.vibris.protocol.v1.SubmitJob;
import dev.vibris.testruntime.FakeVibrisServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreAdversarialTest {
    private static final String EDGE_WORKSPACE_ID = "22222222-2222-4222-8222-222222222222";
    private static final String RACE_WORKSPACE_ID = "33333333-3333-4333-8333-333333333333";
    private static final String RESUME_WORKSPACE_ID = "44444444-4444-4444-8444-444444444444";

    @TempDir
    Path temp;

    @Test
    void queueDuplicateCancelTimeoutResume() throws Exception {
        Path pending = temp.resolve("pending");
        Path artifacts = temp.resolve("artifacts");
        try (FakeVibrisServer server = FakeVibrisServer.start(0, pending, artifacts);
             IntegrationHarness.Client client = new IntegrationHarness.Client(server, EDGE_WORKSPACE_ID)) {
            IntegrationHarness.Probe probe = new IntegrationHarness.Probe(server);
            probe.pauseExecution();

            SubmitJob blocker = newJob(pending, "blocker", 30_000, 1_000);
            client.submit(blocker);
            client.awaitAccepted("blocker");
            client.awaitProgress("blocker", JobStage.JOB_STAGE_WARMING_UP);

            List<SubmitJob> queued = new ArrayList<>();
            for (int index = 1; index <= 32; index++) {
                SubmitJob job = newJob(pending, "queued-" + index, 5_000, 1);
                queued.add(job);
                client.submit(job);
                client.awaitAccepted(job.getRequestId());
            }
            SubmitJob overflow = newJob(pending, "queued-33", 5_000, 1);
            client.submit(overflow);
            client.awaitFailed("queued-33", ErrorCode.QUEUE_FULL);
            assertTrue(Files.isDirectory(pending.resolve(overflow.getSources(0).getUuid())),
                "Rejected source ownership must remain with the client");

            SubmitJob cancelled = queued.get(31);
            client.cancel(cancelled.getRequestId());
            client.awaitFailed(cancelled.getRequestId(), ErrorCode.CANCELLED);
            assertFalse(Files.exists(pending.resolve(cancelled.getSources(0).getUuid())));

            assertEquals(JobState.JOB_STATE_QUEUED, client.resume("queued-1").getState());
            probe.resumeExecution();
            client.awaitCompleted("blocker");
            JobCompleted first = null;
            for (SubmitJob job : queued.subList(0, 31)) {
                JobCompleted completed = client.awaitCompleted(job.getRequestId());
                if (job.getRequestId().equals("queued-1")) first = completed;
            }
            client.submit(queued.get(0));
            JobCompleted duplicate = client.awaitCompleted("queued-1");
            assertEquals(first, duplicate);
            assertEquals(1, probe.executionCount("queued-1"));

            probe.pauseExecution();
            SubmitJob timeout = newJob(pending, "timeout", 25, 1_000);
            client.submit(timeout);
            client.awaitAccepted("timeout");
            client.awaitProgress("timeout", JobStage.JOB_STAGE_WARMING_UP);
            client.awaitFailed("timeout", ErrorCode.EXECUTION_TIMEOUT);
            assertTrue(probe.strings("executionEvents").contains("timeout:SAFE_POINT_TIMEOUT"));
            probe.resumeExecution();

            assertDisconnectResume(server, pending, probe);
            Files.delete(pending.resolve(overflow.getSources(0).getUuid()).resolve("main.glsl"));
            Files.delete(pending.resolve(overflow.getSources(0).getUuid()));
            probe.assertRegistriesBounded();
            IntegrationHarness.assertDirectoryEmpty(pending);
            IntegrationHarness.assertDirectoryEmpty(artifacts);
        }
    }

    @Test
    void duplicateDuringSourceValidationSharesTheAcceptedRequest() throws Exception {
        Path pending = temp.resolve("pending-validation-race");
        try (FakeVibrisServer server = FakeVibrisServer.start(
                 0, pending, temp.resolve("artifacts-validation-race"));
             IntegrationHarness.Client first = new IntegrationHarness.Client(server, RACE_WORKSPACE_ID);
             IntegrationHarness.Client duplicate = new IntegrationHarness.Client(server, RACE_WORKSPACE_ID)) {
            IntegrationHarness.Probe probe = new IntegrationHarness.Probe(server);
            probe.pauseExecution();
            IntegrationHarness.Source source = IntegrationHarness.createSource(pending, "validation-race");
            long extraBytes = 0;
            int extraFiles = 4_096;
            for (int index = 0; index < extraFiles; index++) {
                Files.writeString(source.directory().resolve("slow-" + index + ".glsl"), "x");
                extraBytes++;
            }
            var reference = source.reference().toBuilder()
                .setFileCount(source.reference().getFileCount() + extraFiles)
                .setTotalBytes(source.reference().getTotalBytes() + extraBytes)
                .build();
            SubmitJob job = IntegrationHarness.job(
                "validation-race", RACE_WORKSPACE_ID, IntegrationHarness.context("validation-race"),
                reference, 5_000, 1);

            first.submit(job);
            Thread.sleep(25);
            duplicate.submit(job);

            first.awaitAccepted("validation-race");
            duplicate.awaitAccepted("validation-race");
            probe.resumeExecution();
            duplicate.awaitCompleted("validation-race");
            assertEquals(1, probe.executionCount("validation-race"));
        }
    }

    private static SubmitJob newJob(Path pending, String id, long timeoutMs, int frames) throws Exception {
        return newJob(pending, id, EDGE_WORKSPACE_ID, timeoutMs, frames);
    }

    private static SubmitJob newJob(Path pending, String id, String workspaceId, long timeoutMs, int frames)
        throws Exception {
        IntegrationHarness.Source source = IntegrationHarness.createSource(pending, id);
        SceneContext context = IntegrationHarness.context(id);
        return IntegrationHarness.job(id, workspaceId, context, source.reference(), timeoutMs, frames);
    }

    private static void assertDisconnectResume(FakeVibrisServer server, Path pending,
                                               IntegrationHarness.Probe probe) throws Exception {
        probe.pauseExecution();
        IntegrationHarness.Client first = new IntegrationHarness.Client(server, RESUME_WORKSPACE_ID);
        SubmitJob job = newJob(pending, "resume-job", RESUME_WORKSPACE_ID, 5_000, 1_000);
        first.submit(job);
        first.awaitAccepted("resume-job");
        first.awaitProgress("resume-job", JobStage.JOB_STAGE_WARMING_UP);
        first.disconnect();

        try (IntegrationHarness.Client resumed = new IntegrationHarness.Client(server, RESUME_WORKSPACE_ID)) {
            assertEquals(JobState.JOB_STATE_RUNNING, resumed.resume("resume-job").getState());
            probe.resumeExecution();
            resumed.awaitCompleted("resume-job");
            assertEquals(JobState.JOB_STATE_COMPLETED, resumed.resume("resume-job").getState());
            assertEquals(1, probe.executionCount("resume-job"));
            resumed.disconnect();
        }
        try (IntegrationHarness.Client replay = new IntegrationHarness.Client(server, RESUME_WORKSPACE_ID)) {
            assertEquals(JobState.JOB_STATE_COMPLETED, replay.resume("resume-job").getState());
            replay.awaitCompleted("resume-job");
        }
    }
}
