package dev.vibris.api;

import java.util.List;
import java.util.Objects;

public record CapturePlan(List<Target> targets) {
    public CapturePlan {
        targets = List.copyOf(targets);
    }

    public static CapturePlan empty() {
        return new CapturePlan(List.of());
    }

    public record Target(
        ResourceCatalog.ResourceKind kind,
        String logicalName,
        ArtifactFormat format,
        String artifactName,
        int mipLevel,
        int layer
    ) {
        public Target {
            kind = Objects.requireNonNull(kind, "kind");
            logicalName = Objects.requireNonNull(logicalName, "logicalName");
            format = Objects.requireNonNull(format, "format");
            artifactName = Objects.requireNonNull(artifactName, "artifactName");
            if (mipLevel < 0 || layer < 0) {
                throw new IllegalArgumentException("Mip level and layer must be non-negative");
            }
        }
    }

    public enum ArtifactFormat {
        PNG,
        EXR,
        RAW,
        BIN
    }
}