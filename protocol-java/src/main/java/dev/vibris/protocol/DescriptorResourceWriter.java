package dev.vibris.protocol;

import dev.vibris.protocol.v1.VibrisControlProto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DescriptorResourceWriter {
    private DescriptorResourceWriter() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected generated resource directory");
        Path descriptor = Path.of(arguments[0]).resolve("META-INF/vibris/vibris_control.desc");
        Files.createDirectories(descriptor.getParent());
        Files.write(descriptor, VibrisControlProto.getDescriptor().toProto().toByteArray());
    }
}