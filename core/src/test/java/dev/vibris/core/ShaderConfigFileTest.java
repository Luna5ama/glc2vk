package dev.vibris.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShaderConfigFileTest {
    @TempDir
    Path temp;

    @Test
    void writesSortedIrisPropertiesThroughScratchFile() throws Exception {
        Path target = temp.resolve("game/shaderpacks/vibris.txt");
        Path scratch = temp.resolve("ram/config/vibris.txt");

        ShaderConfigFile.write(target, scratch, Map.of(
            "SETTING_PCSS_SAMPLE_COUNT", "32",
            "SETTING_CLOUDS_CU_WIND", "false"));

        String expected = "SETTING_CLOUDS_CU_WIND=false\nSETTING_PCSS_SAMPLE_COUNT=32\n";
        assertEquals(expected, Files.readString(scratch));
        assertEquals(expected, Files.readString(target));
    }

    @Test
    void rejectsPropertyInjection() {
        assertThrows(IllegalArgumentException.class, () -> ShaderConfigFile.write(
            temp.resolve("vibris.txt"),
            temp.resolve("scratch.txt"),
            Map.of("SETTING_OK", "true\nINJECTED=true")));
    }
}
