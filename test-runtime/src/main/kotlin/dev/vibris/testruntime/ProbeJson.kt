package dev.vibris.testruntime

import dev.vibris.core.CoreProbe
import dev.vibris.protocol.v1.SceneContext
import java.io.PrintStream

internal object ProbeJson {
    @JvmStatic
    fun write(
        snapshot: CoreProbe.Snapshot,
        maxRuntimeOperations: Int,
        output: PrintStream,
    ) {
        for (trace in snapshot.jobTraces) {
            output.println(
                "{\"type\":\"JobTrace\",\"request_id\":" + quote(trace.requestId) +
                    ",\"workspace_id\":" + quote(trace.workspaceId) +
                    ",\"context\":" + context(trace.context) + "}",
            )
        }
        for (transition in snapshot.sourceTransitions) {
            output.println(
                "{\"type\":\"SourceTransition\",\"uuid\":" + quote(transition.uuid) +
                    ",\"from\":" + quote(transition.from) +
                    ",\"to\":" + quote(transition.to) + "}",
            )
        }
        output.println(
            "{\"type\":\"ServerSummary\",\"max_concurrent_jobs\":" + snapshot.maxConcurrentJobs +
                ",\"max_runtime_operations\":" + maxRuntimeOperations +
                ",\"execution_order\":" + strings(snapshot.executionOrder) +
                ",\"execution_events\":" + strings(snapshot.executionEvents) +
                ",\"execution_counts\":" + counts(snapshot.executionCounts) +
                ",\"queue_limit\":32,\"queue_peak\":" + snapshot.queuePeak +
                ",\"request_registry_peak\":" + snapshot.requestRegistryPeak +
                ",\"request_registry_limit\":192,\"request_registry_size\":" +
                snapshot.requestRegistrySize +
                ",\"source_registry_limit\":128,\"source_registry_peak\":" +
                snapshot.sourceRegistryPeak +
                ",\"source_registry_size\":" + snapshot.sourceRegistrySize + "}",
        )
    }

    private fun context(value: SceneContext): String =
        "{\"save_id\":" + quote(value.saveId) +
            ",\"dimension_id\":" + quote(value.dimensionId) +
            ",\"time_preset_id\":" + quote(value.timePresetId) +
            ",\"weather_preset_id\":" + quote(value.weatherPresetId) +
            ",\"camera_preset_id\":" + quote(value.cameraPresetId) +
            ",\"fov\":" + java.lang.Double.toString(value.fov) +
            ",\"resolution\":{\"width\":" + value.resolution.width +
            ",\"height\":" + value.resolution.height +
            "},\"settings_preset_id\":" + quote(value.settingsPresetId) + "}"

    private fun strings(values: Iterable<String>): String {
        val result = StringBuilder("[")
        for (value in values) {
            if (result.length > 1) {
                result.append(',')
            }
            result.append(quote(value))
        }
        return result.append(']').toString()
    }

    private fun counts(values: Map<String, Int>): String {
        val result = StringBuilder("{")
        for ((key, value) in values) {
            if (result.length > 1) {
                result.append(',')
            }
            result.append(quote(key)).append(':').append(value)
        }
        return result.append('}').toString()
    }

    private fun quote(value: String): String {
        val result = StringBuilder(value.length + 2).append('"')
        for (character in value) {
            when (character) {
                '"' -> result.append("\\\"")
                '\\' -> result.append("\\\\")
                '\b' -> result.append("\\b")
                '\u000c' -> result.append("\\f")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                else -> {
                    if (character < '\u0020') {
                        result.append(String.format("\\u%04x", character.code))
                    } else {
                        result.append(character)
                    }
                }
            }
        }
        return result.append('"').toString()
    }
}