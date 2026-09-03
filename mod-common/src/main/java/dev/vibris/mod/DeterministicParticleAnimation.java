package dev.vibris.mod;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

public final class DeterministicParticleAnimation {
	private DeterministicParticleAnimation() {
	}

	public static Scope begin(Minecraft minecraft) {
		RenderSystem.assertOnRenderThread();
		minecraft.particleEngine.clearParticles();
		return new Scope(minecraft);
	}

	public static final class Scope implements AutoCloseable {
		private final Minecraft minecraft;
		private boolean closed;

		private Scope(Minecraft minecraft) {
			this.minecraft = minecraft;
		}

		@Override
		public void close() {
			RenderSystem.assertOnRenderThread();
			if (closed) {
				return;
			}
			minecraft.particleEngine.clearParticles();
			closed = true;
		}
	}
}
