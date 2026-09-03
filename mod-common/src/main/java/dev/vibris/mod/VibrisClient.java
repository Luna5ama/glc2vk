package dev.vibris.mod;

import dev.luna5ama.vibris.capture.CaptureManager;
import dev.luna5ama.vibris.capture.ShaderDebugControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VibrisClient {
	public static final Logger LOGGER = LoggerFactory.getLogger("Vibris");
	private static final CaptureManager CAPTURE_MANAGER = new CaptureManager();
	private static final ShaderDebugControl SHADER_DEBUG_CONTROL = new ShaderDebugControl(new IrisShaderDebugHost());

	private VibrisClient() {
	}

	public static CaptureManager captureManager() {
		return CAPTURE_MANAGER;
	}

	public static ShaderDebugControl shaderDebugControl() {
		return SHADER_DEBUG_CONTROL;
	}

	public static void initializeAutomation() {
		IrisVibrisLifecycle.initializeAutomation();
	}

	public static void onGlReady() {
		IrisVibrisLifecycle.start();
	}

	public static void close() {
		IrisVibrisLifecycle.close();
	}
}
