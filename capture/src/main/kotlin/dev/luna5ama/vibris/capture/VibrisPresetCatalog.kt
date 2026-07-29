package dev.luna5ama.vibris.capture

import dev.vibris.api.ContextValidationResult
import dev.vibris.api.SceneContext
import dev.vibris.api.ScenePreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.HashMap
import java.util.HashSet

class VibrisPresetCatalog private constructor(
    times: Map<String, TimePreset>,
    worlds: Map<String, WorldPreset>,
    settings: Set<String>,
) {
    private val times = java.util.Map.copyOf(times)
    private val worlds = java.util.Map.copyOf(worlds)
    private val settings = java.util.Set.copyOf(settings)

    fun resolve(context: SceneContext): ResolvedContext {
        val world = requirePreset(worlds, context.saveId, "save")
        if (!world.dimensions.contains(context.dimensionId)) {
            throw IllegalArgumentException("Unknown dimension preset: ${context.dimensionId}")
        }
        val camera = requirePreset(world.cameras, context.cameraPresetId, "camera")
        if (camera.dimensionId != context.dimensionId) {
            throw IllegalArgumentException("Camera preset belongs to another dimension")
        }
        val time = requirePreset(times, context.timePresetId, "time")
        if (time.weather != context.weatherPresetId) {
            throw IllegalArgumentException("Weather preset does not match the selected time preset")
        }
        if (!settings.contains(context.settingsPresetId)) {
            throw IllegalArgumentException("Unknown settings preset: ${context.settingsPresetId}")
        }
        return ResolvedContext(world.saveName, time.tick, time.weather, camera)
    }

    fun presets(): List<ScenePreset> {
        val result = ArrayList<ScenePreset>()
        val worldEntries = worlds.entries.sortedBy { it.key }
        val timeEntries = times.entries.sortedBy { it.key }
        val settingIds = settings.sorted()
        for ((worldId, world) in worldEntries) {
            val cameras = world.cameras.entries.sortedBy { it.key }
            for (dimension in world.dimensions.sorted()) {
                for ((cameraId, camera) in cameras) {
                    if (camera.dimensionId != dimension) {
                        continue
                    }
                    for ((timeId, time) in timeEntries) {
                        for (setting in settingIds) {
                            val id = listOf(worldId, dimension, timeId, cameraId, setting).joinToString("/")
                            val context = SceneContext(
                                worldId,
                                dimension,
                                timeId,
                                time.weather,
                                cameraId,
                                70.0,
                                SceneContext.Resolution.unspecified(),
                                setting,
                            )
                            result.add(ScenePreset(id, id, context))
                        }
                    }
                }
            }
        }
        return java.util.List.copyOf(result)
    }

    fun validate(context: SceneContext): ContextValidationResult {
        return try {
            val world = requirePreset(worlds, context.saveId, "save")
            if (!world.dimensions.contains(context.dimensionId)) {
                throw IllegalArgumentException("Unknown dimension preset: ${context.dimensionId}")
            }
            val camera = requirePreset(world.cameras, context.cameraPresetId, "camera")
            if (camera.dimensionId != context.dimensionId) {
                throw IllegalArgumentException("Camera preset belongs to another dimension")
            }
            val time = requirePreset(times, context.timePresetId, "time")
            if (context.weatherPresetId.isNotEmpty() && time.weather != context.weatherPresetId) {
                throw IllegalArgumentException("Weather preset does not match the selected time preset")
            }
            if (context.settingsPresetId.isNotEmpty() && !settings.contains(context.settingsPresetId)) {
                throw IllegalArgumentException("Unknown settings preset: ${context.settingsPresetId}")
            }
            ContextValidationResult.accepted()
        } catch (exception: IllegalArgumentException) {
            ContextValidationResult.invalid(exception.message!!)
        }
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
        val dimensionId: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
    )

    private data class TimePreset(
        val tick: Long,
        val weather: String,
    )

    private data class WorldPreset(
        val saveName: String,
        val dimensions: Set<String>,
        val cameras: Map<String, CameraPreset>,
    )

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun load(path: Path): VibrisPresetCatalog {
            try {
                val document: JsonElement = Json.parseToJsonElement(Files.readString(path))
                val root = document as JsonObject
                if (integer(root, "schema_version") != 1) {
                    throw IOException("Unsupported Vibris preset schema")
                }
                return VibrisPresetCatalog(parseTimes(root), parseWorlds(root), parseSettings(root))
            } catch (exception: RuntimeException) {
                throw IOException("Invalid Vibris preset file: $path", exception)
            }
        }

        private fun parseTimes(root: JsonObject): Map<String, TimePreset> {
            val result = HashMap<String, TimePreset>()
            for (element in array(root, "time_presets")) {
                val value = element as JsonObject
                val id = string(value, "id")
                putUnique(result, id, TimePreset(longValue(value, "tick"), string(value, "weather")))
            }
            return result
        }

        private fun parseWorlds(root: JsonObject): Map<String, WorldPreset> {
            val result = HashMap<String, WorldPreset>()
            for (element in array(root, "worlds")) {
                val value = element as JsonObject
                val dimensions = HashSet<String>()
                for (dimension in array(value, "dimensions")) {
                    dimensions.add(dimension.jsonPrimitive.content)
                }
                val cameras = HashMap<String, CameraPreset>()
                for (cameraElement in array(value, "cameras")) {
                    val camera = cameraElement as JsonObject
                    val position = array(camera, "position")
                    if (position.size != 3) {
                        throw IllegalArgumentException("Camera position must have three values")
                    }
                    putUnique(
                        cameras,
                        string(camera, "id"),
                        CameraPreset(
                            string(camera, "dimension_id"),
                            position[0].jsonPrimitive.double,
                            position[1].jsonPrimitive.double,
                            position[2].jsonPrimitive.double,
                            camera.getValue("yaw").jsonPrimitive.float,
                            camera.getValue("pitch").jsonPrimitive.float,
                        ),
                    )
                }
                putUnique(
                    result,
                    string(value, "id"),
                    WorldPreset(
                        string(value, "save_name"),
                        java.util.Set.copyOf(dimensions),
                        java.util.Map.copyOf(cameras),
                    ),
                )
            }
            return result
        }

        private fun parseSettings(root: JsonObject): Set<String> {
            val result = HashSet<String>()
            for (element in array(root, "settings_presets")) {
                if (element is JsonPrimitive) {
                    result.add(element.content)
                } else {
                    result.add(string(element as JsonObject, "id"))
                }
            }
            return result
        }

        private fun array(objectValue: JsonObject, name: String): JsonArray {
            val value = objectValue[name]
            if (value !is JsonArray) {
                throw IllegalArgumentException("Missing array: $name")
            }
            return value
        }

        private fun string(objectValue: JsonObject, name: String): String {
            val value = objectValue.getValue(name).jsonPrimitive.content
            if (value.isBlank()) {
                throw IllegalArgumentException("Blank preset field: $name")
            }
            return value
        }

        private fun integer(objectValue: JsonObject, name: String): Int {
            return objectValue.getValue(name).jsonPrimitive.int
        }

        private fun longValue(objectValue: JsonObject, name: String): Long {
            return objectValue.getValue(name).jsonPrimitive.long
        }

        private fun <T> requirePreset(values: Map<String, T>, id: String, kind: String): T {
            return values[id] ?: throw IllegalArgumentException("Unknown $kind preset: $id")
        }

        private fun <T> putUnique(values: MutableMap<String, T>, id: String, value: T) {
            if (values.putIfAbsent(id, value) != null) {
                throw IllegalArgumentException("Duplicate preset id: $id")
            }
        }
    }
}