package dev.vibris.core

import dev.vibris.api.SceneContext
import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object BenchmarkProvenance {
    fun shaderConfigHash(settings: Map<String, String>): String = sha256(buildJsonObject {
        settings.toSortedMap().forEach { (key, value) -> put(key, value) }
    })

    fun presetHash(presetId: String, version: String, displayName: String, context: SceneContext): String =
        sha256(buildJsonObject {
            put("preset_id", presetId)
            put("version", version)
            put("display_name", displayName)
            put("effective_context", context(context))
        })

    private fun context(value: SceneContext): JsonObject = buildJsonObject {
        put("save_id", value.saveId)
        put("dimension_id", value.dimensionId)
        put("time_preset_id", value.timePresetId)
        put("weather_preset_id", value.weatherPresetId)
        put("camera_preset_id", value.cameraPresetId)
        put("fov", value.fov)
        put("resolution", buildJsonObject {
            put("width", value.resolution.width)
            put("height", value.resolution.height)
        })
        put("settings_preset_id", value.settingsPresetId)
    }

    private fun sha256(value: JsonElement): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
