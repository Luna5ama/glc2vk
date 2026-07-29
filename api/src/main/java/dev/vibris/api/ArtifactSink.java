package dev.vibris.api;

import java.io.IOException;
import java.io.OutputStream;

@FunctionalInterface
public interface ArtifactSink {
    OutputStream open(String artifactName) throws IOException;
}