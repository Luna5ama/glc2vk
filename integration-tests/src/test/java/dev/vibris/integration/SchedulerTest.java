package dev.vibris.integration;

import dev.vibris.protocol.v1.JobStage;
import dev.vibris.protocol.v1.SceneContext;
import dev.vibris.protocol.v1.SubmitJob;
import dev.vibris.testruntime.FakeVibrisServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SchedulerTest {
    @TempDir
    Path temp;

    @Test
    void threeClientsRoundRobinWithoutInterleave() throws Exception {
        Path pending = temp.resolve("pending");
        Path artifacts = temp.resolve("artifacts");
        try (FakeVibrisServer server = FakeVibrisServer.start(0, pending, artifacts);
             PhaseThreeHarness.Client a = new PhaseThreeHarness.Client(server, "A");
             PhaseThreeHarness.Client b = new PhaseThreeHarness.Client(server, "B");
             PhaseThreeHarness.Client c = new PhaseThreeHarness.Client(server, "C")) {
            PhaseThreeHarness.Probe probe = new PhaseThreeHarness.Probe(server);
            probe.pauseExecution();
            Map<String, SceneContext> contexts = new LinkedHashMap<>();
            Map<String, SubmitJob> jobs = new LinkedHashMap<>();
            for (String id : List.of("A1", "A2", "B1", "B2", "C1")) {
                String workspace = id.substring(0, 1);
                SceneContext context = PhaseThreeHarness.context(id);
                PhaseThreeHarness.Source source = PhaseThreeHarness.createSource(pending, id);
                contexts.put(id, context);
                jobs.put(id, PhaseThreeHarness.job(id, workspace, context, source.reference(), 5_000, 1));
            }

            a.submit(jobs.get("A1"));
            a.awaitAccepted("A1");
            a.awaitProgress("A1", JobStage.JOB_STAGE_WARMING_UP);
            b.submit(jobs.get("B1"));
            b.awaitAccepted("B1");
            c.submit(jobs.get("C1"));
            c.awaitAccepted("C1");
            a.submit(jobs.get("A2"));
            a.awaitAccepted("A2");
            b.submit(jobs.get("B2"));
            b.awaitAccepted("B2");
            probe.resumeExecution();
            for (String id : jobs.keySet()) client(id, a, b, c).awaitCompleted(id);

            List<String> expected = List.of("A1", "B1", "C1", "A2", "B2");
            assertEquals(expected, probe.strings("executionOrder"));
            assertEquals(expected.stream().map(contexts::get).toList(), probe.contexts());
            assertEquals(1, probe.integer("maxConcurrentJobs"));
            assertNoExecutionEventInterleaves(probe.strings("executionEvents"));
            PhaseThreeHarness.assertDirectoryEmpty(pending);
            PhaseThreeHarness.assertDirectoryEmpty(artifacts);
        }
    }

    private static PhaseThreeHarness.Client client(String requestId, PhaseThreeHarness.Client a,
                                                    PhaseThreeHarness.Client b, PhaseThreeHarness.Client c) {
        return switch (requestId.charAt(0)) {
            case 'A' -> a;
            case 'B' -> b;
            case 'C' -> c;
            default -> throw new IllegalArgumentException(requestId);
        };
    }

    private static void assertNoExecutionEventInterleaves(List<String> events) {
        String active = null;
        for (String event : events) {
            int separator = event.indexOf(':');
            assertFalse(separator < 1, () -> "Malformed execution event: " + event);
            String requestId = event.substring(0, separator);
            String stage = event.substring(separator + 1);
            if (active == null) active = requestId;
            assertEquals(active, requestId, () -> "Interleaved execution event: " + event);
            if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(stage)) active = null;
        }
        assertEquals(null, active, "Execution trace ended before a terminal event");
    }
}