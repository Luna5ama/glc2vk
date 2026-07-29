package dev.vibris.api;

import java.util.Objects;

public record RuntimeStatus(
    boolean ready,
    String currentSaveId,
    String currentDimensionId,
    String activeSourceUuid
) {
    public RuntimeStatus {
        currentSaveId = Objects.requireNonNull(currentSaveId, "currentSaveId");
        currentDimensionId = Objects.requireNonNull(currentDimensionId, "currentDimensionId");
        activeSourceUuid = Objects.requireNonNull(activeSourceUuid, "activeSourceUuid");
    }
}