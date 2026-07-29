package dev.vibris.testruntime;

import dev.vibris.core.CoreProbe;
import dev.vibris.protocol.v1.SceneContext;

import java.io.PrintStream;
import java.util.Map;

final class ProbeJson {
    private ProbeJson() {
    }

    static void write(CoreProbe.Snapshot snapshot, int maxRuntimeOperations, PrintStream output) {
        for (CoreProbe.JobTrace trace : snapshot.jobTraces()) {
            output.println("{\"type\":\"JobTrace\",\"request_id\":" + quote(trace.requestId()) +
                ",\"workspace_id\":" + quote(trace.workspaceId()) + ",\"context\":" + context(trace.context()) + "}");
        }
        for (CoreProbe.SourceTransition transition : snapshot.sourceTransitions()) {
            output.println("{\"type\":\"SourceTransition\",\"uuid\":" + quote(transition.uuid()) +
                ",\"from\":" + quote(transition.from()) + ",\"to\":" + quote(transition.to()) + "}");
        }
        output.println("{\"type\":\"ServerSummary\",\"max_concurrent_jobs\":" + snapshot.maxConcurrentJobs() +
            ",\"max_runtime_operations\":" + maxRuntimeOperations +
            ",\"execution_order\":" + strings(snapshot.executionOrder()) +
            ",\"execution_events\":" + strings(snapshot.executionEvents()) +
            ",\"execution_counts\":" + counts(snapshot.executionCounts()) +
            ",\"queue_limit\":32,\"queue_peak\":" + snapshot.queuePeak() +
            ",\"request_registry_peak\":" + snapshot.requestRegistryPeak() +
            ",\"request_registry_limit\":192,\"request_registry_size\":" + snapshot.requestRegistrySize() +
            ",\"source_registry_limit\":128,\"source_registry_peak\":" + snapshot.sourceRegistryPeak() +
            ",\"source_registry_size\":" + snapshot.sourceRegistrySize() + "}");
    }

    private static String context(SceneContext value) {
        return "{\"save_id\":" + quote(value.getSaveId()) +
            ",\"dimension_id\":" + quote(value.getDimensionId()) +
            ",\"time_preset_id\":" + quote(value.getTimePresetId()) +
            ",\"weather_preset_id\":" + quote(value.getWeatherPresetId()) +
            ",\"camera_preset_id\":" + quote(value.getCameraPresetId()) +
            ",\"fov\":" + Double.toString(value.getFov()) +
            ",\"resolution\":{\"width\":" + value.getResolution().getWidth() +
            ",\"height\":" + value.getResolution().getHeight() + "},\"settings_preset_id\":" +
            quote(value.getSettingsPresetId()) + "}";
    }

    private static String strings(Iterable<String> values) {
        StringBuilder result = new StringBuilder("[");
        for (String value : values) {
            if (result.length() > 1) result.append(',');
            result.append(quote(value));
        }
        return result.append(']').toString();
    }

    private static String counts(Map<String, Integer> values) {
        StringBuilder result = new StringBuilder("{");
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            if (result.length() > 1) result.append(',');
            result.append(quote(entry.getKey())).append(':').append(entry.getValue());
        }
        return result.append('}').toString();
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) result.append(String.format("\\u%04x", (int) character));
                    else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }
}