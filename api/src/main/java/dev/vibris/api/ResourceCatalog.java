package dev.vibris.api;

import java.util.List;
import java.util.Objects;

public record ResourceCatalog(List<ResourceDescriptor> resources) {
    public ResourceCatalog {
        resources = List.copyOf(resources);
    }

    public static ResourceCatalog empty() {
        return new ResourceCatalog(List.of());
    }

    public record ResourceDescriptor(
        String logicalName,
        ResourceKind kind,
        int width,
        int height,
        int depth,
        int mipLevels,
        int layers,
        String internalFormat,
        int channelCount,
        ScalarType scalarType,
        long byteSize,
        long frameId,
        String semanticLabel
    ) {
        public ResourceDescriptor {
            logicalName = Objects.requireNonNull(logicalName, "logicalName");
            kind = Objects.requireNonNull(kind, "kind");
            internalFormat = Objects.requireNonNull(internalFormat, "internalFormat");
            scalarType = Objects.requireNonNull(scalarType, "scalarType");
            semanticLabel = Objects.requireNonNull(semanticLabel, "semanticLabel");
            if (width < 0 || height < 0 || depth < 0 || mipLevels < 0 || layers < 0 ||
                channelCount < 0 || byteSize < 0 || frameId < 0) {
                throw new IllegalArgumentException("Resource metadata must not be negative");
            }
        }

        public ResourceDescriptor(String logicalName, ResourceKind kind) {
            this(logicalName, kind, 0, 0, 0, 0, 0, "", 0, ScalarType.UNSPECIFIED, 0, 0, "");
        }
    }

    public enum ResourceKind {
        FINAL_FRAMEBUFFER,
        TEXTURE,
        BUFFER
    }

    public enum ScalarType {
        UNSPECIFIED,
        UINT8,
        SINT8,
        UINT16,
        SINT16,
        UINT32,
        SINT32,
        FLOAT16,
        FLOAT32
    }
}