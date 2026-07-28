package dev.vibris.integration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildTupleTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();

    @Test
    void gradle92CodegenAndDescriptorParity() throws Exception {
        List<String> distributionUrls = Files.readAllLines(
            ROOT.resolve("gradle/wrapper/gradle-wrapper.properties"), StandardCharsets.UTF_8
        ).stream().filter(line -> line.startsWith("distributionUrl=")).toList();
        assertEquals(List.of(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.2.1-bin.zip"
        ), distributionUrls);

        String proto = Files.readString(ROOT.resolve("proto/vibris_control.proto"), StandardCharsets.UTF_8);
        assertFalse(proto.matches("(?s).*(?im:^\\s*(?:(?:optional|repeated)\\s+)?bytes\\s+).*$"));
        assertFalse(proto.matches("(?si).*\\babsolute_path\\b.*"));

        byte[] javaDescriptor;
        try (ZipFile jar = new ZipFile(ROOT.resolve(
            "protocol-java/build/libs/vibris-protocol-java.jar"
        ).toFile())) {
            var entry = jar.getEntry("META-INF/vibris/vibris_control.desc");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                javaDescriptor = input.readAllBytes();
            }
        }
        assertTrue(javaDescriptor.length > 0);

        Path cppDescriptor = Files.createTempFile("vibris-cpp-descriptor-", ".bin");
        try {
            ProcessHarness.Result dump = ProcessHarness.run(
                Duration.ofSeconds(10),
                ROOT.resolve("mcp/out/build/Release/vibris-descriptor-dump.exe").toString(),
                "--output",
                cppDescriptor.toString()
            );
            assertEquals(0, dump.exitCode(), dump.output());
            assertArrayEquals(javaDescriptor, Files.readAllBytes(cppDescriptor));
        } finally {
            Files.deleteIfExists(cppDescriptor);
        }
    }
}