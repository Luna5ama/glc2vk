package dev.vibris.mod.fabric;

import dev.vibris.mod.VibrisPlatform;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class VibrisFabricPlatform implements VibrisPlatform {
	@Override
	public Path gameDirectory() {
		return FabricLoader.getInstance().getGameDir();
	}

	@Override
	public String modVersion() {
		return FabricLoader.getInstance().getModContainer("vibris")
			.orElseThrow()
			.getMetadata()
			.getVersion()
			.getFriendlyString();
	}
}
