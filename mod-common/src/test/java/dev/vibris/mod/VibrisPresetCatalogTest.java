package dev.vibris.mod;

import dev.luna5ama.vibris.capture.VibrisPresetCatalog;
import dev.vibris.api.SceneContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VibrisPresetCatalogTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void resolvesOneCompletePreset() throws IOException {
		VibrisPresetCatalog catalog = VibrisPresetCatalog.load(writePresets());
		SceneContext context = context("rooftop", "clear");

		var resolved = catalog.resolve(context);
		assertEquals("shader-test-world", resolved.saveName());
		assertEquals(12000, resolved.tick());
		assertEquals(124.5, resolved.camera().x());
		assertEquals(137.0f, resolved.camera().yaw());
		assertEquals(1, catalog.presets().size());
		assertEquals("rooftop", catalog.presets().getFirst().presetId());
		assertTrue(catalog.validate(context).valid());
		SceneContext configureContext = new SceneContext(
			"shader-test-world", "minecraft:overworld", "rooftop", "", "rooftop", 70.0,
			SceneContext.Resolution.unspecified(), "");
		assertTrue(catalog.validate(configureContext).valid());
	}

	@Test
	void rejectsMixedOrUnknownPresetIds() throws IOException {
		VibrisPresetCatalog catalog = VibrisPresetCatalog.load(writePresets());

		assertThrows(IllegalArgumentException.class, () -> catalog.resolve(context("unknown", "clear")));
		assertFalse(catalog.validate(new SceneContext(
			"shader-test-world", "minecraft:overworld", "other", "", "rooftop", 70.0,
			SceneContext.Resolution.unspecified(), "")).valid());
		assertThrows(IllegalArgumentException.class, () -> catalog.resolve(context("rooftop", "rain")));
	}

	private SceneContext context(String preset, String weather) {
		return new SceneContext(
			"shader-test-world",
			"minecraft:overworld",
			preset,
			weather,
			preset,
			70.0,
			new SceneContext.Resolution(1280, 720),
			"automation");
	}

	private Path writePresets() throws IOException {
		Path path = temporaryDirectory.resolve("presets.json");
		Files.writeString(path, """
			{
			  "schema_version": 2,
			  "presets": [{
			    "id":"rooftop",
			    "save_id":"shader-test-world",
			    "save_name":"shader-test-world",
			    "dimension_id":"minecraft:overworld",
			    "position":[124.5,82.0,-31.5],
			    "yaw":137.0,
			    "pitch":-8.0,
			    "fov":70.0,
			    "tick":12000,
			    "weather":"clear",
			    "resolution":[1280,720],
			    "settings_preset_id":"automation"
			  }]
			}
			""");
		return path;
	}
}