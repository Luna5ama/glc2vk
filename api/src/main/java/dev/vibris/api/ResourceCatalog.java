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

    public record ResourceDescriptor(String logicalName, ResourceKind kind) {
        public ResourceDescriptor {
            logicalName = Objects.requireNonNull(logicalName, "logicalName");
            kind = Objects.requireNonNull(kind, "kind");
        }
    }

    public enum ResourceKind {
        FINAL_FRAMEBUFFER,
        TEXTURE,
        BUFFER
    }
}