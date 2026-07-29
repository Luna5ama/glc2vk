package dev.vibris.core;

import dev.vibris.api.SceneContext;

import java.time.Duration;

final class RuntimeJobContext {
    private RuntimeJobContext() {
    }

    static long deadline(CoreJob job) {
        long executionMillis = job.submission.getTimeouts().getExecutionTimeoutMs();
        long totalMillis = job.submission.getTimeouts().getTotalTimeoutMs();
        long now = System.nanoTime();
        long execution = addDuration(now, executionMillis);
        long total = addDuration(job.acceptedNanos, totalMillis);
        return Math.min(execution, total);
    }

    static SceneContext toApi(dev.vibris.protocol.v1.SceneContext source) {
        dev.vibris.protocol.v1.Resolution resolution = source.getResolution();
        return new SceneContext(
            source.getSaveId(),
            source.getDimensionId(),
            source.getTimePresetId(),
            source.getWeatherPresetId(),
            source.getCameraPresetId(),
            source.getFov(),
            resolution.getWidth() == 0
                ? SceneContext.Resolution.unspecified()
                : new SceneContext.Resolution(resolution.getWidth(), resolution.getHeight()),
            source.getSettingsPresetId());
    }

    static dev.vibris.protocol.v1.SceneContext toProtocol(SceneContext source) {
        return dev.vibris.protocol.v1.SceneContext.newBuilder()
            .setSaveId(source.saveId())
            .setDimensionId(source.dimensionId())
            .setTimePresetId(source.timePresetId())
            .setWeatherPresetId(source.weatherPresetId())
            .setCameraPresetId(source.cameraPresetId())
            .setFov(source.fov())
            .setSettingsPresetId(source.settingsPresetId())
            .setResolution(dev.vibris.protocol.v1.Resolution.newBuilder()
                .setWidth(source.resolution().width())
                .setHeight(source.resolution().height()))
            .build();
    }

    private static long addDuration(long start, long milliseconds) {
        if (milliseconds == 0) return Long.MAX_VALUE;
        try {
            return Math.addExact(start, Duration.ofMillis(milliseconds).toNanos());
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}