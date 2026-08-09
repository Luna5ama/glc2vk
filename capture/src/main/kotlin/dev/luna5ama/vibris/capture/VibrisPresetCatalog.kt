package dev.luna5ama.vibris.capture

import dev.vibris.api.ContextValidationResult
import dev.vibris.api.SceneContext
import dev.vibris.api.ScenePreset
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.HashMap

class VibrisPresetCatalog private constructor(
    private val path: Path,
    presets: Map<String, Preset>,
) {
    @Volatile
    private var values = java.util.Map.copyOf(presets)

    @Synchronized
    fun save(preset: Preset): String {
        val saveId = values.values.firstOrNull { it.saveName == preset.saveName }?.saveId ?: preset.saveId
        val stored = preset.copy(saveId = saveId)
        val updated = HashMap(values)
        updated[stored.id] = stored
        write(path, updated)
        values = java.util.Map.copyOf(updated)
        return stored.id
    }

    @Synchronized
    fun resolve(context: SceneContext): ResolvedContext {
        val preset = requirePreset(context, false)
        return ResolvedContext(
            preset.saveName,
            preset.tick,
            preset.weather,
            CameraPreset(preset.x, preset.y, preset.z, preset.yaw, preset.pitch),
        )
    }

    @Synchronized
    fun presets(): List<ScenePreset> = values.values
        .sortedBy { it.id }
        .map { ScenePreset(it.id, it.id, it.context(), SCHEMA_VERSION.toString()) }

    @Synchronized
    fun validate(context: SceneContext): ContextValidationResult = try {
        requirePreset(context, true)
        ContextValidationResult.accepted()
    } catch (exception: IllegalArgumentException) {
        ContextValidationResult.invalid(exception.message!!)
    }

    private fun requirePreset(context: SceneContext, allowIncomplete: Boolean): Preset {
        val preset = values[context.cameraPresetId]
            ?: throw IllegalArgumentException("Unknown preset: ${context.cameraPresetId}")
        require(context.timePresetId == preset.id) { "Time and camera must select the same preset" }
        require(context.saveId == preset.saveId) { "Preset belongs to another save" }
        require(context.dimensionId == preset.dimensionId) { "Preset belongs to another dimension" }
        require(context.fov == preset.fov) { "Field of view does not match the selected preset" }
        if (!allowIncomplete || context.weatherPresetId.isNotEmpty()) {
            require(context.weatherPresetId == preset.weather) { "Weather does not match the selected preset" }
        }
        if (!allowIncomplete || context.settingsPresetId.isNotEmpty()) {
            require(context.settingsPresetId == preset.settingsPresetId) {
                "Settings do not match the selected preset"
            }
        }
        if (!allowIncomplete || context.resolution.isSpecified()) {
            require(context.resolution == preset.resolution) { "Resolution does not match the selected preset" }
        }
        return preset
    }

    @JvmRecord
    data class ResolvedContext(
        val saveName: String,
        val tick: Long,
        val weather: String,
        val camera: CameraPreset,
    )

    @JvmRecord
    data class CameraPreset(
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
    )

    @JvmRecord
    data class Preset(
        val id: String,
        val saveId: String,
        val saveName: String,
        val dimensionId: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val fov: Double,
        val tick: Long,
        val weather: String,
        val resolution: SceneContext.Resolution,
        val settingsPresetId: String,
    ) {
        init {
            require(id.isNotBlank() && '/' !in id) { "Invalid preset id" }
            require(saveId.isNotBlank() && saveName.isNotBlank() && dimensionId.isNotBlank()) {
                "World preset fields are blank"
            }
            require(x.isFinite() && y.isFinite() && z.isFinite() && yaw.isFinite() && pitch.isFinite()) {
                "Camera values must be finite"
            }
            require(fov.isFinite() && fov > 0.0 && fov < 180.0) { "Invalid field of view" }
            require(weather == "clear" || weather == "rain" || weather == "thunder") {
                "Unknown weather preset: $weather"
            }
            require(resolution.isSpecified()) { "Preset resolution is unspecified" }
            require(settingsPresetId.isNotBlank()) { "Settings preset is blank" }
        }

        fun context(): SceneContext = SceneContext(
            saveId,
            dimensionId,
            id,
            weather,
            id,
            fov,
            resolution,
            settingsPresetId,
        )
    }

    companion object {
        private const val SCHEMA_VERSION = 2

        @JvmStatic
        @Throws(IOException::class)
        fun load(path: Path): VibrisPresetCatalog {
            try {
                val root = Json.parseToJsonElement(Files.readString(path)) as JsonObject
                if (integer(root, "schema_version") != SCHEMA_VERSION) {
                    throw IOException("Unsupported Vibris preset schema")
                }
                val presets = HashMap<String, Preset>()
                for (element in array(root, "presets")) {
                    val value = element as JsonObject
                    val position = array(value, "position")
                    val resolution = array(value, "resolution")
                    require(position.size == 3) { "Preset position must have three values" }
                    require(resolution.size == 2) { "Preset resolution must have two values" }
                    val preset = Preset(
                        string(value, "id"),
                        string(value, "save_id"),
                        string(value, "save_name"),
                        string(value, "dimension_id"),
                        position[0].jsonPrimitive.double,
                        position[1].jsonPrimitive.double,
                        position[2].jsonPrimitive.double,
                        value.getValue("yaw").jsonPrimitive.float,
                        value.getValue("pitch").jsonPrimitive.float,
                        value.getValue("fov").jsonPrimitive.double,
                        value.getValue("tick").jsonPrimitive.long,
                        string(value, "weather"),
                        SceneContext.Resolution(
                            resolution[0].jsonPrimitive.int,
                            resolution[1].jsonPrimitive.int,
                        ),
                        string(value, "settings_preset_id"),
                    )
                    if (presets.putIfAbsent(preset.id, preset) != null) {
                        throw IllegalArgumentException("Duplicate preset id: ${preset.id}")
                    }
                }
                return VibrisPresetCatalog(path, presets)
            } catch (exception: RuntimeException) {
                throw IOException("Invalid Vibris preset file: $path", exception)
            }
        }

        private fun array(value: JsonObject, name: String): JsonArray =
            value[name] as? JsonArray ?: throw IllegalArgumentException("Missing array: $name")

        private fun string(value: JsonObject, name: String): String =
            value.getValue(name).jsonPrimitive.content.also {
                require(it.isNotBlank()) { "Blank preset field: $name" }
            }

        private fun integer(value: JsonObject, name: String): Int = value.getValue(name).jsonPrimitive.int

        @OptIn(ExperimentalSerializationApi::class)
        private fun write(path: Path, presets: Map<String, Preset>) {
            val root = buildJsonObject {
                put("schema_version", JsonPrimitive(SCHEMA_VERSION))
                put("presets", buildJsonArray {
                    for (preset in presets.values.sortedBy { it.id }) add(buildJsonObject {
                        put("id", JsonPrimitive(preset.id))
                        put("save_id", JsonPrimitive(preset.saveId))
                        put("save_name", JsonPrimitive(preset.saveName))
                        put("dimension_id", JsonPrimitive(preset.dimensionId))
                        put("position", buildJsonArray {
                            add(JsonPrimitive(preset.x))
                            add(JsonPrimitive(preset.y))
                            add(JsonPrimitive(preset.z))
                        })
                        put("yaw", JsonPrimitive(preset.yaw))
                        put("pitch", JsonPrimitive(preset.pitch))
                        put("fov", JsonPrimitive(preset.fov))
                        put("tick", JsonPrimitive(preset.tick))
                        put("weather", JsonPrimitive(preset.weather))
                        put("resolution", buildJsonArray {
                            add(JsonPrimitive(preset.resolution.width))
                            add(JsonPrimitive(preset.resolution.height))
                        })
                        put("settings_preset_id", JsonPrimitive(preset.settingsPresetId))
                    })
                })
            }
            val absolutePath = path.toAbsolutePath()
            Files.createDirectories(absolutePath.parent)
            val temporary = Files.createTempFile(absolutePath.parent, absolutePath.fileName.toString(), ".tmp")
            try {
                Files.writeString(temporary, Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(root))
                try {
                    Files.move(
                        temporary,
                        absolutePath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: IOException) {
                    Files.move(temporary, absolutePath, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
