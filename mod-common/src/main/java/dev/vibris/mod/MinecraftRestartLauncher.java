package dev.vibris.mod;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

final class MinecraftRestartLauncher {
	private static final String PARENT_PID_ENV = "VIBRIS_RESTART_PARENT_PID";
	private static final String EXECUTABLE_ENV = "VIBRIS_RESTART_EXECUTABLE";
	private static final String WAIT_AND_RESTART = "& { " +
		"$parentId = [long]$env:" + PARENT_PID_ENV + "; " +
		"$restartPath = $env:" + EXECUTABLE_ENV + "; " +
		"Wait-Process -Id $parentId -ErrorAction SilentlyContinue; " +
		"Start-Process -FilePath $restartPath -WindowStyle Hidden }";

	private MinecraftRestartLauncher() {
	}

	static void restart(Path configuredExecutable) {
		Path executable = configuredExecutable.toAbsolutePath().normalize();
		if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(executable)) {
			throw new IllegalArgumentException("restart_executable is missing or is not an ordinary file");
		}
		try {
			processBuilder(executable, ProcessHandle.current().pid()).start();
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to start the Minecraft restart helper.", exception);
		}
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(minecraft::stop);
	}

	static ProcessBuilder processBuilder(Path executable, long parentProcessId) {
		ProcessBuilder helper = new ProcessBuilder(command())
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.redirectError(ProcessBuilder.Redirect.DISCARD);
		helper.environment().put(PARENT_PID_ENV, Long.toString(parentProcessId));
		helper.environment().put(EXECUTABLE_ENV, executable.toString());
		return helper;
	}

	static List<String> command() {
		return List.of(
			powerShellExecutable(),
			"-NoLogo",
			"-NoProfile",
			"-NonInteractive",
			"-WindowStyle",
			"Hidden",
			"-Command",
			WAIT_AND_RESTART
		);
	}

	private static String powerShellExecutable() {
		String systemRoot = System.getenv("SystemRoot");
		if (systemRoot == null || systemRoot.isBlank()) return "powershell.exe";
		return Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
	}
}
