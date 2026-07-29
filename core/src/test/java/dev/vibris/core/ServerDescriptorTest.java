package dev.vibris.core;

import dev.vibris.api.RuntimeStatus;
import dev.vibris.protocol.v1.RuntimeState;
import dev.vibris.protocol.v1.ServerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}