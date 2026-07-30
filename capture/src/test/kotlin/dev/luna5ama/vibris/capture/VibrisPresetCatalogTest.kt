package dev.luna5ama.vibris.capture

import dev.vibris.api.SceneContext
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VibrisPresetCatalogTest {
    @Test
    fun savesAndImmediatelyResolvesCurrentContext() {
        val directory = createTempDirectory("vibris-presets-test")
        try {
            val path = directory.resolve("presets.json")
            Files.writeString(path, INITIAL_PRESETS)
            val catalog = VibrisPresetCatalog.load(path)

            val presetId = catalog.save(VibrisPresetCatalog.PresetSnapshot(
                "current",
                "Test World",
                "minecraft:the_nether",
                18_234,
                "rain",
                VibrisPresetCatalog.CameraPreset("minecraft:the_nether", 1.25, 64.0, -3.5, 90.0f, -12.0f),
            ))

            assertEquals("test-world/minecraft:the_nether/current/current/default", presetId)
            val context = SceneContext(
                "test-world", "minecraft:the_nether", "current", "rain", "current", 70.0,
                SceneContext.Resolution.unspecified(), "default",
            )
            assertEquals(18_234, catalog.resolve(context).tick)
            assertTrue(VibrisPresetCatalog.load(path).validate(context).valid)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val INITIAL_PRESETS = """{
          "schema_version": 1,
          "time_presets": [{"id":"noon","tick":6000,"weather":"clear"}],
          "settings_presets": [{"id":"default"}],
          "worlds": [{
            "id":"test-world",
            "save_name":"Test World",
            "dimensions":["minecraft:overworld"],
            "cameras":[{
              "id":"spawn",
              "dimension_id":"minecraft:overworld",
              "position":[0.5,100.0,0.5],
              "yaw":180.0,
              "pitch":15.0
            }]
          }]
        }"""
    }
}
