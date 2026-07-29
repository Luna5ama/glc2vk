package dev.vibris.api;

import java.nio.file.Path;
import java.util.Map;

public record CaptureResult(long frameId, Map<String, Path> artifacts) {
    public CaptureResult {
        if (frameId < 0) throw new IllegalArgumentException("frameId must not be negative");
        artifacts = Map.copyOf(artifacts);
    }
}