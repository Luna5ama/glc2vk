package dev.vibris.mod;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftRestartLauncherTest {
	@Test
	void helperWaitsForCurrentProcessBeforeInvokingConfiguredBatchFile() {
		Path executable = Path.of("I:/PCL/启动 1.21.11-Vibris.bat");
		List<String> command = MinecraftRestartLauncher.command();

		assertTrue(command.get(0).endsWith("powershell.exe"));
		assertTrue(command.contains("Hidden"));
		assertTrue(command.get(command.indexOf("-Command") + 1).contains("Wait-Process"));
		assertTrue(command.get(command.indexOf("-Command") + 1).contains("Start-Process"));
		assertTrue(command.stream().noneMatch(executable.toString()::equals));

		ProcessBuilder helper = MinecraftRestartLauncher.processBuilder(executable, 4242);
		assertEquals(ProcessBuilder.Redirect.PIPE, helper.redirectInput());
		assertEquals(ProcessBuilder.Redirect.DISCARD, helper.redirectOutput());
		assertEquals(ProcessBuilder.Redirect.DISCARD, helper.redirectError());
		assertTrue(helper.environment().containsValue("4242"));
		assertTrue(helper.environment().containsValue(executable.toString()));
	}
}