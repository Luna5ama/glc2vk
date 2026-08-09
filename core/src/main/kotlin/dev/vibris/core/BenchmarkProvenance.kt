package dev.vibris.core

import dev.vibris.api.SceneContext
import dev.vibris.protocol.v1.LoadShader
import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object BenchmarkProvenance {
    fun create(
        job: CoreJob,
        load: LoadShader,
        loaded: RuntimeJobExecutor.LoadResult,
        inspection: JsonObject,
    ): JsonObject {
        val source = job.sources.single { it.uuid.equals(load.sourceUuid, ignoreCase = true) }
        val reference = source.reference
        val sourceIdentity = buildJsonObject {
            put("kind", reference.origin.kindCase.name.removeSuffix("_NOT_SET").lowercase())
            put("requested_revision", reference.requestedRevision)
            put("resolved_commit", reference.resolvedRevision)
            put("snapshot_sha256", source.snapshotSha256)
            put("file_count", reference.fileCount)
            put("total_bytes", reference.totalBytes)
        }
        val settings = loaded.effectiveShaderSettings
        val settingsJson: JsonElement = settings?.let { values ->
            buildJsonObject { values.toSortedMap().forEach { (key, value) -> put(key, value) } }
        } ?: JsonNull
        val shaderIdentity = shaderIdentity(settings, settingsJson)
        val patched = inspection["patched_shader"] as? JsonObject ?: buildJsonObject {
            put("available", false)
            put("reason", "runtime_did_not_report_patched_shader_identity")
        }
        val sceneContext = context(loaded.context.context)
        val preset = job.submission.benchmarkProvenance
        val sourceComplete = reference.requestedRevision.isNotBlank() &&
            reference.resolvedRevision.matches(Regex("[0-9a-fA-F]{40}")) &&
            source.snapshotSha256.matches(Regex("[0-9a-f]{64}"))
        val patchedComplete = (patched["available"] as? JsonPrimitive)?.content == "true" &&
            (patched["sha256"] as? JsonPrimitive)?.content?.matches(Regex("[0-9a-f]{64}")) == true
        val presetComplete = preset.presetId.isNotBlank() && preset.presetVersion.isNotBlank()
        val complete = sourceComplete && settings != null && patchedComplete && presetComplete
        val sourceHash = sha256(sourceIdentity)
        val configHash = sha256(shaderIdentity)
        val sceneHash = sha256(sceneContext)
        val presetHash = presetHash(
            preset.presetId,
            preset.presetVersion,
            preset.presetDisplayName,
            sceneContext,
        )
        val patchedHash = (patched["sha256"] as? JsonPrimitive)?.content.orEmpty()
        val caseHash = sha256(buildJsonObject {
            put("schema_version", 1)
            put("source_sha256", sourceHash)
            put("config_sha256", configHash)
            put("scene_sha256", sceneHash)
            put("preset_sha256", presetHash)
            put("patched_shader_sha256", patchedHash)
        })
        return buildJsonObject {
            put("schema_version", 1)
            put("complete", complete)
            put("case_hash", caseHash)
            put("source", JsonObject(sourceIdentity.toMutableMap().apply {
                put("identity_sha256", JsonPrimitive(sourceHash))
                put("active_source_uuid", JsonPrimitive(source.uuid))
            }))
            put("shader", JsonObject(shaderIdentity.toMutableMap().apply {
                put("config_sha256", JsonPrimitive(configHash))
                put("patched", patched)
            }))
            put("scene", buildJsonObject {
                put("context_sha256", sceneHash)
                put("effective_context", sceneContext)
                put("preset_id", preset.presetId)
                put("preset_version", preset.presetVersion)
                put("preset_display_name", preset.presetDisplayName)
                put("preset_sha256", presetHash)
            })
        }
    }

    fun shaderConfigHash(settings: Map<String, String>): String {
        val settingsJson = buildJsonObject {
            settings.toSortedMap().forEach { (key, value) -> put(key, value) }
        }
        return sha256(shaderIdentity(settings, settingsJson))
    }

    fun presetHash(presetId: String, version: String, displayName: String, context: SceneContext): String =
        presetHash(presetId, version, displayName, context(context))

    private fun presetHash(
        presetId: String,
        version: String,
        displayName: String,
        sceneContext: JsonObject,
    ): String = sha256(buildJsonObject {
        put("preset_id", presetId)
        put("version", version)
        put("display_name", displayName)
        put("effective_context", sceneContext)
    })

    private fun shaderIdentity(settings: Map<String, String>?, settingsJson: JsonElement): JsonObject =
        buildJsonObject {
            put("mode", if (settings == null) "preserve" else "explicit")
            put("settings_known", settings != null)
            put("effective_settings", settingsJson)
        }

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
