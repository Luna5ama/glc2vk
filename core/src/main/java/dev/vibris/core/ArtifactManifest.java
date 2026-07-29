package dev.vibris.core;

import java.nio.charset.StandardCharsets;
import java.util.Map;

final class ArtifactManifest {
    private ArtifactManifest() {
    }

    static byte[] encode(Map<String, Long> artifacts) {
        StringBuilder json = new StringBuilder("{\n  \"artifacts\": [");
        boolean first = true;
        for (Map.Entry<String, Long> artifact : artifacts.entrySet()) {
            if (!first) json.append(',');
            json.append("\n    {\"file_name\": \"").append(escapeJson(artifact.getKey()))
                .append("\", \"byte_size\": ").append(artifact.getValue()).append('}');
            first = false;
        }
        if (!artifacts.isEmpty()) json.append('\n').append("  ");
        return json.append("]\n}\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.toString();
    }
}