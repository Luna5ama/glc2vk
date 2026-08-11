package dev.vibris.core

import dev.vibris.api.EffectiveShaderSettings
import dev.vibris.api.SceneContext
import dev.vibris.protocol.v2.EffectiveShaderSetting
import dev.vibris.protocol.v2.ShaderSettingOrigin
import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object BenchmarkProvenance {
    fun effectiveSettings(settings: EffectiveShaderSettings): dev.vibris.protocol.v2.EffectiveShaderSettings =
        dev.vibris.protocol.v2.EffectiveShaderSettings.newBuilder()
            .setSettingsSha256(settings.settingsSha256)
            .addAllSettings(settings.settings.map { setting ->
                EffectiveShaderSetting.newBuilder()
                    .setName(setting.name)
                    .setValue(setting.value)
                    .setDefaultValue(setting.defaultValue)
                    .setOrigin(setting.origin.toProtocol())
                    .setChangedFromDefault(setting.changedFromDefault())
                    .build()
            })
            .build()

    fun sceneHash(context: SceneContext): String = sha256(context(context))

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

    private fun EffectiveShaderSettings.Origin.toProtocol(): ShaderSettingOrigin = when (this) {
        EffectiveShaderSettings.Origin.DEFAULT -> ShaderSettingOrigin.SHADER_SETTING_ORIGIN_DEFAULT
        EffectiveShaderSettings.Origin.PRESERVED_CURRENT ->
            ShaderSettingOrigin.SHADER_SETTING_ORIGIN_PRESERVED_CURRENT
        EffectiveShaderSettings.Origin.REQUEST_OVERRIDE ->
            ShaderSettingOrigin.SHADER_SETTING_ORIGIN_REQUEST_OVERRIDE
        EffectiveShaderSettings.Origin.PRESET -> ShaderSettingOrigin.SHADER_SETTING_ORIGIN_PRESET
    }

    private fun sha256(value: JsonElement): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}