package dev.vibris.mod;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vibris.api.CancellationToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class DeterministicWorldSimulation {
	private static Scope activeScope;

	private DeterministicWorldSimulation() {
	}

	public static synchronized boolean isActive() {
		return activeScope != null;
	}

	static Scope begin(Minecraft minecraft, CancellationToken cancellation) {
		RenderSystem.assertOnRenderThread();
		cancellation.throwIfCancellationRequested();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.level == null) {
			throw new IllegalStateException("A loaded integrated-server world is required");
		}

		synchronized (DeterministicWorldSimulation.class) {
			if (activeScope != null) {
				if (activeScope.server == server) return activeScope;
				throw new IllegalStateException("The deterministic simulation scope belongs to another world");
			}
		}

		boolean clientWasFrozen = minecraft.level.tickRateManager().isFrozen();
		boolean serverWasFrozen = queryServerFrozen(server);
		setServerFrozen(server, true);
		try {
			cancellation.throwIfCancellationRequested();
			minecraft.level.tickRateManager().setFrozen(true);
			Scope scope = new Scope(minecraft, server, clientWasFrozen, serverWasFrozen);
			synchronized (DeterministicWorldSimulation.class) {
				activeScope = scope;
			}
			return scope;
		} catch (Throwable failure) {
			setServerFrozen(server, serverWasFrozen);
			throw failure;
		}
	}

	private static boolean queryServerFrozen(IntegratedServer server) {
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		server.execute(() -> result.complete(server.tickRateManager().isFrozen()));
		return join(result);
	}

	private static void setServerFrozen(IntegratedServer server, boolean frozen) {
		CompletableFuture<Void> result = new CompletableFuture<>();
		server.execute(() -> {
			try {
				server.tickRateManager().setFrozen(frozen);
				result.complete(null);
			} catch (Throwable failure) {
				result.completeExceptionally(failure);
			}
		});
		join(result);
	}

	private static <T> T join(CompletableFuture<T> future) {
		try {
			return future.join();
		} catch (CompletionException failure) {
			Throwable cause = failure.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			if (cause instanceof Error error) throw error;
			throw failure;
		}
	}

	static final class Scope implements AutoCloseable {
		private final Minecraft minecraft;
		private final IntegratedServer server;
		private final boolean clientWasFrozen;
		private final boolean serverWasFrozen;
		private boolean closed;

		private Scope(
			Minecraft minecraft,
			IntegratedServer server,
			boolean clientWasFrozen,
			boolean serverWasFrozen
		) {
			this.minecraft = minecraft;
			this.server = server;
			this.clientWasFrozen = clientWasFrozen;
			this.serverWasFrozen = serverWasFrozen;
		}

		@Override
		public void close() {
			RenderSystem.assertOnRenderThread();
			if (closed) return;
			setServerFrozen(server, serverWasFrozen);
			if (minecraft.level != null) {
				minecraft.level.tickRateManager().setFrozen(clientWasFrozen);
			}
			synchronized (DeterministicWorldSimulation.class) {
				if (activeScope != this) {
					throw new IllegalStateException("Deterministic simulation scope is not active");
				}
				activeScope = null;
			}
			closed = true;
		}
	}
}
