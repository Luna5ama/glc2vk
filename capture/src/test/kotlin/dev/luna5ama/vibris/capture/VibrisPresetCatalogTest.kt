package dev.luna5ama.vibris.capture

import dev.vibris.api.SceneContext
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VibrisPresetCatalogTest {
    @Test
    fun storesEachSceneAsOneCompletePreset() {
        val directory = createTempDirectory("vibris-presets-test")
        try {
            val path = directory.resolve("presets.json")
            Files.writeString(path, INITIAL_PRESETS)
            val catalog = VibrisPresetCatalog.load(path)
            val current = VibrisPresetCatalog.Preset(
                "current",
                "test-world",
                "Test World",
                "minecraft:the_nether",
                1.25,
                64.0,
                -3.5,
                90.0f,
                -12.0f,
                82.0,
                18_234,
                "rain",
                SceneContext.Resolution(1600, 900),
                "default",
            )

            assertEquals("current", catalog.save(current))
            assertEquals(listOf("current", "existing"), catalog.presets().map { it.presetId })
            assertEquals(current.context(), catalog.presets().first().context)
            assertEquals(18_234, catalog.resolve(current.context()).tick)
            assertTrue(VibrisPresetCatalog.load(path).validate(current.context()).valid)
            assertFalse(catalog.validate(SceneContext(
                "test-world", "minecraft:the_nether", "existing", "", "current", 82.0,
                SceneContext.Resolution.unspecified(), "",
            )).valid)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val INITIAL_PRESETS = """{
          "schema_version": 2,
          "presets": [{
            "id":"existing",
            "save_id":"test-world",
            "save_name":"Test World",
            "dimension_id":"minecraft:overworld",
            "position":[0.5,100.0,0.5],
            "yaw":180.0,
            "pitch":15.0,
            "fov":70.0,
            "tick":6000,
            "weather":"clear",
            "resolution":[1920,1080],
            "settings_preset_id":"default"
          }]
        }"""
    }
}
