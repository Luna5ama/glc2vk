package dev.vibris.mod;

import java.nio.file.Path;
import java.util.ServiceLoader;

public interface VibrisPlatform {
	VibrisPlatform INSTANCE = ServiceLoader.load(VibrisPlatform.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No Vibris platform provider is installed"));

	Path gameDirectory();

	String modVersion();

	static VibrisPlatform getInstance() {
		return INSTANCE;
	}
}
