package dev.vibris.testruntime;

import com.google.protobuf.CodedOutputStream;
import dev.vibris.protocol.v1.Resolution;
import dev.vibris.protocol.v1.SceneContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class RuntimeSerializationTest {
    @Test
    void completeContextSnapshotRoundTrips() throws Exception {
        SceneContext.Builder source = SceneContext.newBuilder()
            .setSaveId("test-save")
            .setDimensionId("minecraft:overworld")
            .setTimePresetId("noon")
            .setWeatherPresetId("clear")
            .setCameraPresetId("origin")
            .setFov(70.0)
            .setResolution(Resolution.newBuilder().setWidth(3840).setHeight(2160))
            .setSettingsPresetId("cinematic");
        SceneContext snapshot = source.build();

        byte[] serialized = serializeDeterministically(snapshot);
        assertArrayEquals(serialized, serializeDeterministically(snapshot));
        SceneContext restored = SceneContext.parseFrom(serialized);

        source.clear().setSaveId("mutated-source");
        Arrays.fill(serialized, (byte) 0);
        SceneContext.Builder changed = restored.toBuilder().setSaveId("mutated-restored");
        changed.getResolutionBuilder().setWidth(1);

        assertNotSame(snapshot, restored);
        assertEquals(snapshot, restored);
        assertEquals("test-save", restored.getSaveId());
        assertEquals(3840, restored.getResolution().getWidth());
        assertNotEquals(restored, changed.build());
    }

    private static byte[] serializeDeterministically(SceneContext context) throws IOException {
        byte[] serialized = new byte[context.getSerializedSize()];
        CodedOutputStream output = CodedOutputStream.newInstance(serialized);
        output.useDeterministicSerialization();
        context.writeTo(output);
        output.checkNoSpaceLeft();
        return serialized;
    }
}