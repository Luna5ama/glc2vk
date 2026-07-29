package dev.vibris.api;

import java.util.Objects;

public record SceneContext(
    String saveId,
    String dimensionId,
    String timePresetId,
    String weatherPresetId,
    String cameraPresetId,
    double fov,
    Resolution resolution,
    String settingsPresetId
) {
    public SceneContext {
        saveId = Objects.requireNonNull(saveId, "saveId");
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        timePresetId = Objects.requireNonNull(timePresetId, "timePresetId");
        weatherPresetId = Objects.requireNonNull(weatherPresetId, "weatherPresetId");
        cameraPresetId = Objects.requireNonNull(cameraPresetId, "cameraPresetId");
        resolution = Objects.requireNonNull(resolution, "resolution");
        settingsPresetId = Objects.requireNonNull(settingsPresetId, "settingsPresetId");
        if (!Double.isFinite(fov) || fov <= 0.0 || fov >= 180.0) {
            throw new IllegalArgumentException("fov must be finite and between 0 and 180 degrees");
        }
    }

    public record Resolution(int width, int height) {
        private static final Resolution UNSPECIFIED = new Resolution(0, 0);

        public Resolution {
            if (width < 0 || height < 0 || (width == 0) != (height == 0)) {
                throw new IllegalArgumentException("Resolution must be positive or unspecified");
            }
        }

        public static Resolution unspecified() {
            return UNSPECIFIED;
        }

        public boolean isSpecified() {
            return width != 0;
        }
    }
}