package dev.vibris.api;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

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
        private static final Pattern SAFE_ARTIFACT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

        public Target {
            kind = Objects.requireNonNull(kind, "kind");
            logicalName = Objects.requireNonNull(logicalName, "logicalName");
            format = Objects.requireNonNull(format, "format");
            artifactName = Objects.requireNonNull(artifactName, "artifactName");
            if (mipLevel < 0 || layer < 0) {
                throw new IllegalArgumentException("Mip level and layer must be non-negative");
            }
            if (!SAFE_ARTIFACT_NAME.matcher(artifactName).matches()) {
                throw new IllegalArgumentException("Artifact name must be a safe file name");
            }
        }

        public String fileName() {
            String extension = "." + format.name().toLowerCase(Locale.ROOT);
            return artifactName.toLowerCase(Locale.ROOT).endsWith(extension)
                ? artifactName
                : artifactName + extension;
        }

        public String metadataFileName() {
            return artifactName + ".json";
        }
    }

    public enum ArtifactFormat {
        PNG,
        EXR,
        RAW,
        BIN
    }
}