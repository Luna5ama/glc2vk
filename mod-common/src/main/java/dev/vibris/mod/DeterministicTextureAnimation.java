package dev.vibris.mod;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vibris.mod.mixinterface.VibrisTextureAtlasAnimation;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public final class DeterministicTextureAnimation {
	private DeterministicTextureAnimation() {
	}

	public static void resetAll(Minecraft minecraft) {
		RenderSystem.assertOnRenderThread();
		List<Throwable> failures = new ArrayList<>();
		minecraft.getAtlasManager().forEach((id, atlas) -> {
			try {
				((VibrisTextureAtlasAnimation) atlas).iris$resetAnimationPhase();
			} catch (Throwable failure) {
				IllegalStateException atlasFailure = new IllegalStateException(
					"Failed to reset texture atlas animation " + id, failure);
				failures.add(atlasFailure);
			}
		});
		if (!failures.isEmpty()) {
			throw aggregate("Failed to reset deterministic texture animation", failures);
		}
	}

	private static IllegalStateException aggregate(String message, List<Throwable> failures) {
		IllegalStateException aggregate = new IllegalStateException(message);
		failures.forEach(aggregate::addSuppressed);
		return aggregate;
	}
}
