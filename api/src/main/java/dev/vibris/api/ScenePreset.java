package dev.vibris.api;

import java.util.Objects;

public record ScenePreset(String presetId, String displayName, SceneContext context) {
    public ScenePreset {
        presetId = Objects.requireNonNull(presetId, "presetId");
        displayName = Objects.requireNonNull(displayName, "displayName");
        context = Objects.requireNonNull(context, "context");
        if (presetId.isBlank() || displayName.isBlank()) throw new IllegalArgumentException("Preset names are blank");
    }
}