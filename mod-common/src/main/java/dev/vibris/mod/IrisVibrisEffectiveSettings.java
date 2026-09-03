package dev.vibris.mod;

import dev.vibris.api.EffectiveShaderSettings;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.option.OptionSet;
import net.irisshaders.iris.shaderpack.option.values.OptionValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class IrisVibrisEffectiveSettings {
	private IrisVibrisEffectiveSettings() {
	}

	public static EffectiveShaderSettings capture(
		ShaderPack pack,
		Map<String, String> preservedValues,
		Map<String, String> requestOverrides
	) {
		var options = pack.getShaderPackOptions();
		Map<String, String> presetValues = pack.getMenuContainer().getProfiles()
			.scan(options.getOptionSet(), options.getOptionValues())
			.current
			.map(profile -> profile.optionValues)
			.orElse(Map.of());
		return capture(
			options.getOptionSet(),
			options.getOptionValues(),
			preservedValues,
			requestOverrides,
			presetValues
		);
	}

	static EffectiveShaderSettings capture(
		OptionSet options,
		OptionValues values,
		Map<String, String> preservedValues,
		Map<String, String> requestOverrides,
		Map<String, String> presetValues
	) {
		List<EffectiveShaderSettings.Setting> settings = new ArrayList<>(
			options.getBooleanOptions().size() + options.getStringOptions().size());
		options.getBooleanOptions().forEach((name, merged) -> {
			String value = Boolean.toString(values.getBooleanValueOrDefault(name));
			String defaultValue = Boolean.toString(merged.getOption().getDefaultValue());
			settings.add(setting(
				name, value, defaultValue, preservedValues, requestOverrides, presetValues));
		});
		options.getStringOptions().forEach((name, merged) -> {
			String value = values.getStringValueOrDefault(name);
			String defaultValue = merged.getOption().getDefaultValue();
			settings.add(setting(
				name, value, defaultValue, preservedValues, requestOverrides, presetValues));
		});
		return EffectiveShaderSettings.of(settings);
	}

	private static EffectiveShaderSettings.Setting setting(
		String name,
		String value,
		String defaultValue,
		Map<String, String> preservedValues,
		Map<String, String> requestOverrides,
		Map<String, String> presetValues
	) {
		return new EffectiveShaderSettings.Setting(
			name,
			value,
			defaultValue,
			origin(name, value, preservedValues, requestOverrides, presetValues)
		);
	}

	private static EffectiveShaderSettings.Origin origin(
		String name,
		String value,
		Map<String, String> preservedValues,
		Map<String, String> requestOverrides,
		Map<String, String> presetValues
	) {
		if (value.equals(requestOverrides.get(name))) {
			return EffectiveShaderSettings.Origin.REQUEST_OVERRIDE;
		}
		if (value.equals(preservedValues.get(name))) {
			return EffectiveShaderSettings.Origin.PRESERVED_CURRENT;
		}
		if (value.equals(presetValues.get(name))) {
			return EffectiveShaderSettings.Origin.PRESET;
		}
		return EffectiveShaderSettings.Origin.DEFAULT;
	}
}
