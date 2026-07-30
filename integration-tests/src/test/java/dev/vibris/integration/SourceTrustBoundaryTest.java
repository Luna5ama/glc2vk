package dev.vibris.integration;

import dev.vibris.protocol.v1.ErrorCode;
import dev.vibris.protocol.v1.JobStage;
import dev.vibris.protocol.v1.PreparedSourceRef;
import dev.vibris.protocol.v1.SceneContext;
import dev.vibris.protocol.v1.SubmitJob;
import dev.vibris.testruntime.FakeVibrisServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceTrustBoundaryTest {
    private static final Set<String> SOURCE_STATES = Set.of(
        "VALIDATED", "QUEUED", "ACTIVATING", "ACTIVE", "RELEASED_ACTIVE", "RECLAIMABLE",
        "DELETING", "DELETED", "FAILED"
    );

    @TempDir
    Path temp;

    @Test
    void invalidUuidAndDisconnectOwnership() throws Exception {
        Path pending = temp.resolve("pending");
        Path artifacts = temp.resolve("artifacts");
        Path outside = temp.resolve("outside.txt");
        Files.writeString(outside, "unchanged", StandardCharsets.UTF_8);
        try (FakeVibrisServer server = FakeVibrisServer.start(0, pending, artifacts)) {
            Files.delete(pending);
            try (IntegrationHarness.Client invalid = new IntegrationHarness.Client(server, "invalid")) {
                PreparedSourceRef source = IntegrationHarness.invalidSource("../outside");
                SubmitJob job = IntegrationHarness.job("invalid", "invalid", IntegrationHarness.context("invalid"),
                    source, 5_000, 1);
                invalid.submit(job);
                invalid.awaitFailed("invalid", ErrorCode.INVALID_SOURCE_UUID);
            }
            assertEquals("unchanged", Files.readString(outside, StandardCharsets.UTF_8));
            assertFalse(Files.exists(pending), "Invalid UUID must be rejected before filesystem access");
            assertFalse(Files.exists(temp.resolve("main.glsl")));

            Files.createDirectories(pending);
            IntegrationHarness.Probe probe = new IntegrationHarness.Probe(server);
            probe.pauseExecution();
            IntegrationHarness.Source accepted = IntegrationHarness.createSource(pending, "disconnect");
            SceneContext context = IntegrationHarness.context("disconnect");
            IntegrationHarness.Client owner = new IntegrationHarness.Client(server, "owner");
            owner.submit(IntegrationHarness.job("disconnect", "owner", context, accepted.reference(), 5_000, 1_000));
            owner.awaitAccepted("disconnect");
            owner.awaitProgress("disconnect", JobStage.JOB_STAGE_WARMING_UP);
            owner.disconnect();
            probe.resumeExecution();
            awaitDeleted(accepted.directory());

            List<String> states = probe.sourceStates(accepted.reference().getUuid());
            assertFalse(states.isEmpty());
            assertEquals("VALIDATED", states.get(0));
            assertEquals("DELETED", states.get(states.size() - 1));
            assertTrue(SOURCE_STATES.containsAll(states), () -> "Unexpected source state trace: " + states);
            IntegrationHarness.assertDirectoryEmpty(pending);
            IntegrationHarness.assertDirectoryEmpty(artifacts);
            assertCoreIndependent();
        }
    }

    private static void awaitDeleted(Path source) throws Exception {
        long deadline = System.nanoTime() + IntegrationHarness.WAIT.toNanos();
        while (Files.exists(source) && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(Files.exists(source), "Server did not clean its accepted source after disconnect");
    }

    private static void assertCoreIndependent() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        for (String module : List.of("api", "core", "protocol-java")) {
            Path source = root.resolve(module).resolve("src");
            if (!Files.exists(source)) continue;
            try (var files = Files.walk(source)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    assertFalse(text.contains("net.irisshaders") || text.contains("org.lwjgl") || text.contains("git "),
                        () -> "Iris/LWJGL/Git dependency leaked into " + file);
                }
            }
        }
    }
}