package dev.vibris.mod;

import net.minecraft.client.DeltaTracker;

public final class VibrisTime {
	private static final float FRAME_SECONDS = 1.0F / 60.0F;
	private static Scope active;

	private VibrisTime() { }

	public static synchronized Scope begin(long originFrame) {
		if (active != null) throw new IllegalStateException("Deterministic shader time is already active");
		active = new Scope(originFrame);
		return active;
	}

	public static synchronized boolean active() {
		return active != null;
	}

	public static synchronized float tickDelta(float value) {
		return active == null ? value : 1.0F;
	}

	public static synchronized DeltaTracker deltaTracker(DeltaTracker value) {
		return active == null ? value : DeltaTracker.ONE;
	}

	public static synchronized long deterministicFrame(long renderedFrame) {
		return active == null ? -1L : Math.subtractExact(renderedFrame, active.originFrame);
	}

	public static float frameSeconds() {
		return FRAME_SECONDS;
	}

	public static final class Scope implements AutoCloseable {
		private final long originFrame;
		private boolean closed;

		private Scope(long originFrame) { this.originFrame = originFrame; }

		@Override
		public synchronized void close() {
			if (closed) return;
			synchronized (VibrisTime.class) {
				if (active != this) throw new IllegalStateException("Deterministic shader time scope is not active");
				active = null;
				closed = true;
			}
		}
	}
}
