package dev.vibris.core;

import dev.vibris.api.RuntimeStatus;
import dev.vibris.protocol.v1.Capability;
import dev.vibris.protocol.v1.DebugControlKind;
import dev.vibris.protocol.v1.JobActionKind;
import dev.vibris.protocol.v1.RuntimeState;
import dev.vibris.protocol.v1.ServerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerDescriptorTest {
    @TempDir
    Path temp;

    @Test
    void reportsRuntimeStatusInsteadOfHardCodedWorldState() {
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        runtime.status = new RuntimeStatus(false, "actual-save", "minecraft:the_nether", "runtime-source");
        Path pending = temp.resolve("pending").toAbsolutePath();
        VibrisCoreEngine engine = new VibrisCoreEngine(pending, runtime);
        ServerDescriptor descriptor = new ServerDescriptor(
            pending, new ArtifactManager(temp.resolve("artifacts")), runtime);

        var status = descriptor.status(engine);

        assertFalse(status.getRuntimeReady());
        assertEquals(ServerState.SERVER_STATE_FAILED, status.getState());
        assertEquals(RuntimeState.RUNTIME_STATE_FAILED, status.getRuntimeState());
        assertEquals("actual-save", status.getCurrentSaveId());
        assertEquals("minecraft:the_nether", status.getCurrentDimensionId());
        assertEquals("runtime-source", status.getActiveSourceUuid());
        engine.close();
    }

    @Test
    void advertisesJobActionsAndDebugControlsSeparately() {
        RuntimeTestAdapter runtime = new RuntimeTestAdapter();
        Path pending = temp.resolve("pending").toAbsolutePath();
        VibrisCoreEngine engine = new VibrisCoreEngine(pending, runtime);
        ServerDescriptor descriptor = new ServerDescriptor(
            pending, new ArtifactManager(temp.resolve("artifacts")), runtime);

        var hello = descriptor.hello(engine);

        assertIterableEquals(
            java.util.List.of(
                JobActionKind.JOB_ACTION_KIND_RESET_TEMPORAL_STATE,
                JobActionKind.JOB_ACTION_KIND_WAIT_FRAMES,
                JobActionKind.JOB_ACTION_KIND_CAPTURE_SCREENSHOT,
                JobActionKind.JOB_ACTION_KIND_DUMP_TEXTURE,
                JobActionKind.JOB_ACTION_KIND_DUMP_BUFFER,
                JobActionKind.JOB_ACTION_KIND_ACTIVATE_SOURCE,
                JobActionKind.JOB_ACTION_KIND_COMPARE_CAPTURES),
            hello.getSupportedJobActionsList());
        assertIterableEquals(
            java.util.Arrays.stream(DebugControlKind.values())
                .filter(kind -> kind != DebugControlKind.DEBUG_CONTROL_KIND_UNSPECIFIED)
                .filter(kind -> kind != DebugControlKind.UNRECOGNIZED)
                .toList(),
            hello.getSupportedDebugControlsList());
        assertIterableEquals(
            java.util.List.of(
                Capability.CAPABILITY_CONTROL_STREAM,
                Capability.CAPABILITY_RESUME,
                Capability.CAPABILITY_PREPARED_SOURCES,
                Capability.CAPABILITY_ACTION_SEQUENCE,
                Capability.CAPABILITY_ARTIFACT_METADATA,
                Capability.CAPABILITY_DEBUG_CONTROL),
            hello.getCapabilitiesList());
        assertTrue(hello.getStatus().getSupportedDebugControlsList()
            .contains(DebugControlKind.DEBUG_CONTROL_KIND_RELOAD_SHADER));
        engine.close();
    }
}
