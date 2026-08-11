package dev.vibris.core

import dev.vibris.api.SceneContext
import java.time.Duration
import dev.vibris.protocol.v2.SceneContext as ProtocolSceneContext

internal object RuntimeJobContext {
    @JvmStatic
    fun deadline(job: CoreJob): Long {
        val executionMillis = job.submission.timeouts.executionTimeoutMs
        val totalMillis = job.submission.timeouts.totalTimeoutMs
        val now = System.nanoTime()
        val execution = addDuration(now, executionMillis)
        val total = addDuration(job.acceptedNanos, totalMillis)
        return minOf(execution, total)
    }

    @JvmStatic
    fun toApi(source: ProtocolSceneContext): SceneContext {
        val resolution = source.resolution
        return SceneContext(
            source.saveId,
            source.dimensionId,
            source.timePresetId,
            source.weatherPresetId,
            source.cameraPresetId,
            source.fov,
            if (resolution.width == 0) {
                SceneContext.Resolution.unspecified()
            } else {
                SceneContext.Resolution(resolution.width, resolution.height)
            },
            source.settingsPresetId,
        )
    }

    @JvmStatic
    fun toProtocol(source: SceneContext): ProtocolSceneContext =
        ProtocolSceneContext.newBuilder()
            .setSaveId(source.saveId)
            .setDimensionId(source.dimensionId)
            .setTimePresetId(source.timePresetId)
            .setWeatherPresetId(source.weatherPresetId)
            .setCameraPresetId(source.cameraPresetId)
            .setFov(source.fov)
            .setSettingsPresetId(source.settingsPresetId)
            .setResolution(
                dev.vibris.protocol.v2.Resolution.newBuilder()
                    .setWidth(source.resolution.width)
                    .setHeight(source.resolution.height),
            )
            .build()

    private fun addDuration(start: Long, milliseconds: Long): Long {
        if (milliseconds == 0L) {
            return Long.MAX_VALUE
        }
        return try {
            Math.addExact(start, Duration.ofMillis(milliseconds).toNanos())
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }
}