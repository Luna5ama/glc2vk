package dev.vibris.api;

import java.util.Map;

public record CaptureResult(
    long frameId,
    Map<String, ResourceCatalog.ResourceDescriptor> artifacts
) {
    public CaptureResult {
        if (frameId < 0) throw new IllegalArgumentException("frameId must not be negative");
        artifacts = Map.copyOf(artifacts);
    }
}