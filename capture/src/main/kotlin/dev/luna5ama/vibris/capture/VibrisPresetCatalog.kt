package dev.luna5ama.vibris.capture

import dev.vibris.api.ContextValidationResult
import dev.vibris.api.SceneContext
import dev.vibris.api.ScenePreset
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import java.util.HashSet

class VibrisPresetCatalog private constructor(
    private val path: Path,
    times: Map<String, TimePreset>,
    worlds: Map<String, WorldPreset>,
    settings: Set<String>,
) {
    @Volatile
    private var times = java.util.Map.copyOf(times)

    @Volatile
    private var worlds = java.util.Map.copyOf(worlds)

    private val settings = java.util.Set.copyOf(settings)

    @Synchronized
    fun save(snapshot: PresetSnapshot): String {
        val setting = settings.minOrNull() ?: throw IllegalStateException("No settings preset is configured")
        val updatedTimes = HashMap(times)
        updatedTimes[snapshot.id] = TimePreset(snapshot.tick, snapshot.weather)

        val existingWorld = worlds.entries.firstOrNull { it.value.saveName == snapshot.saveName }
        val worldId = existingWorld?.key ?: snapshot.saveName
        val oldWorld = existingWorld?.value ?: WorldPreset(snapshot.saveName, emptySet(), emptyMap())
        val updatedCameras = HashMap(oldWorld.cameras)
        updatedCameras[snapshot.id] = snapshot.camera
        val updatedWorld = WorldPreset(
            oldWorld.saveName,
            oldWorld.dimensions + snapshot.dimensionId,
            updatedCameras,
        )
        val updatedWorlds = HashMap(worlds)
        updatedWorlds[worldId] = updatedWorld

        write(path, updatedTimes, updatedWorlds, settings)
        times = java.util.Map.copyOf(updatedTimes)
        worlds = java.util.Map.copyOf(updatedWorlds)
        return listOf(worldId, snapshot.dimensionId, snapshot.id, snapshot.id, setting).joinToString("/")
    }

    @Synchronized
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

    @Synchronized
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

    @Synchronized
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

    @JvmRecord
    data class PresetSnapshot(
        val id: String,
        val saveName: String,
        val dimensionId: String,
        val tick: Long,
        val weather: String,
        val camera: CameraPreset,
    ) {
        init {
            require(id.isNotBlank() && '/' !in id) { "Invalid preset id" }
            require(saveName.isNotBlank() && dimensionId.isNotBlank()) { "World preset fields are blank" }
            require(weather == "clear" || weather == "rain" || weather == "thunder") {
                "Unknown weather preset: $weather"
            }
        }
    }

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
                return VibrisPresetCatalog(path, parseTimes(root), parseWorlds(root), parseSettings(root))
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

        @OptIn(ExperimentalSerializationApi::class)
        private fun write(
            path: Path,
            times: Map<String, TimePreset>,
            worlds: Map<String, WorldPreset>,
            settings: Set<String>,
        ) {
            val root = buildJsonObject {
                put("schema_version", JsonPrimitive(1))
                put("time_presets", buildJsonArray {
                    for ((id, time) in times.toSortedMap()) add(buildJsonObject {
                        put("id", JsonPrimitive(id))
                        put("tick", JsonPrimitive(time.tick))
                        put("weather", JsonPrimitive(time.weather))
                    })
                })
                put("settings_presets", buildJsonArray {
                    for (id in settings.sorted()) add(buildJsonObject { put("id", JsonPrimitive(id)) })
                })
                put("worlds", buildJsonArray {
                    for ((id, world) in worlds.toSortedMap()) add(buildJsonObject {
                        put("id", JsonPrimitive(id))
                        put("save_name", JsonPrimitive(world.saveName))
                        put("dimensions", buildJsonArray {
                            for (dimension in world.dimensions.sorted()) add(JsonPrimitive(dimension))
                        })
                        put("cameras", buildJsonArray {
                            for ((cameraId, camera) in world.cameras.toSortedMap()) add(buildJsonObject {
                                put("id", JsonPrimitive(cameraId))
                                put("dimension_id", JsonPrimitive(camera.dimensionId))
                                put("position", buildJsonArray {
                                    add(JsonPrimitive(camera.x))
                                    add(JsonPrimitive(camera.y))
                                    add(JsonPrimitive(camera.z))
                                })
                                put("yaw", JsonPrimitive(camera.yaw))
                                put("pitch", JsonPrimitive(camera.pitch))
                                put("default_fov", JsonPrimitive(70.0))
                            })
                        })
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
