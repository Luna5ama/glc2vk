package dev.vibris.mod;

import dev.vibris.api.EffectiveShaderSettings;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.option.BooleanOption;
import net.irisshaders.iris.shaderpack.option.OptionLocation;
import net.irisshaders.iris.shaderpack.option.OptionSet;
import net.irisshaders.iris.shaderpack.option.OptionType;
import net.irisshaders.iris.shaderpack.option.StringOption;
import net.irisshaders.iris.shaderpack.option.values.MutableOptionValues;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisVibrisEffectiveSettingsTest {
	@Test
	void resolvesCompleteDeterministicOriginsAfterReload() {
		OptionSet options = options();
		MutableOptionValues values = new MutableOptionValues(options, Map.of(
			"SHADOWS", "false",
			"QUALITY", "high"
		));

		EffectiveShaderSettings override = IrisVibrisEffectiveSettings.capture(
			options,
			values,
			Map.of(),
			Map.of("SHADOWS", "false"),
			Map.of("QUALITY", "high")
		);

		assertEquals(List.of("MODE", "QUALITY", "SHADOWS"),
			override.settings().stream().map(EffectiveShaderSettings.Setting::name).toList());
		assertEquals(Map.of(
			"MODE", EffectiveShaderSettings.Origin.DEFAULT,
			"QUALITY", EffectiveShaderSettings.Origin.PRESET,
			"SHADOWS", EffectiveShaderSettings.Origin.REQUEST_OVERRIDE
		), origins(override));
		assertEquals(Map.of("MODE", "stable", "QUALITY", "high", "SHADOWS", "false"), override.values());
		assertFalse(override.settings().getFirst().changedFromDefault());
		assertTrue(override.settings().get(1).changedFromDefault());
		assertTrue(override.settings().get(2).changedFromDefault());

		EffectiveShaderSettings preserved = IrisVibrisEffectiveSettings.capture(
			options,
			values,
			override.values(),
			Map.of(),
			Map.of("QUALITY", "high")
		);
		assertEquals(Map.of(
			"MODE", EffectiveShaderSettings.Origin.PRESERVED_CURRENT,
			"QUALITY", EffectiveShaderSettings.Origin.PRESERVED_CURRENT,
			"SHADOWS", EffectiveShaderSettings.Origin.PRESERVED_CURRENT
		), origins(preserved));
		assertEquals(override.settingsSha256(), preserved.settingsSha256());
		assertTrue(override.hasSameResolvedState(preserved));
	}

	private static OptionSet options() {
		OptionSet.Builder builder = OptionSet.builder();
		OptionLocation location = new OptionLocation(AbsolutePackPath.fromAbsolutePath("/fixture.glsl"), 0);
		builder.addBooleanOption(location, new BooleanOption(OptionType.DEFINE, "SHADOWS", "", true));
		builder.addStringOption(location, Objects.requireNonNull(
			StringOption.create(OptionType.CONST, "QUALITY", "[low high]", "low")));
		builder.addStringOption(location, Objects.requireNonNull(
			StringOption.create(OptionType.CONST, "MODE", "[stable experimental]", "stable")));
		return builder.build();
	}

	private static Map<String, EffectiveShaderSettings.Origin> origins(EffectiveShaderSettings settings) {
		return settings.settings().stream().collect(Collectors.toMap(
			EffectiveShaderSettings.Setting::name,
			EffectiveShaderSettings.Setting::origin
		));
	}
}