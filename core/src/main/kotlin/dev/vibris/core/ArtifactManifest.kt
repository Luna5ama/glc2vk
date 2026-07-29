package dev.vibris.core

import java.nio.charset.StandardCharsets

internal object ArtifactManifest {
    @JvmStatic
    fun encode(artifacts: Map<String, Long>): ByteArray {
        val json = StringBuilder("{\n  \"artifacts\": [")
        var first = true
        for ((fileName, byteSize) in artifacts) {
            if (!first) {
                json.append(',')
            }
            json.append("\n    {\"file_name\": \"")
                .append(escapeJson(fileName))
                .append("\", \"byte_size\": ")
                .append(byteSize)
                .append('}')
            first = false
        }
        if (artifacts.isNotEmpty()) {
            json.append('\n').append("  ")
        }
        return json.append("]\n}\n").toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun escapeJson(value: String): String {
        val escaped = StringBuilder(value.length)
        for (character in value) {
            when (character) {
                '"' -> escaped.append("\\\"")
                '\\' -> escaped.append("\\\\")
                '\b' -> escaped.append("\\b")
                '\u000c' -> escaped.append("\\f")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\t' -> escaped.append("\\t")
                else -> {
                    if (character < '\u0020') {
                        escaped.append(String.format("\\u%04x", character.code))
                    } else {
                        escaped.append(character)
                    }
                }
            }
        }
        return escaped.toString()
    }
}