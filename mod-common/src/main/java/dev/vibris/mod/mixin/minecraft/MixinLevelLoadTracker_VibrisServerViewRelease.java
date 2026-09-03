package dev.vibris.mod.mixin.minecraft;

import dev.vibris.mod.mixinterface.VibrisLevelLoadRelease;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelLoadTracker.class)
public class MixinLevelLoadTracker_VibrisServerViewRelease implements VibrisLevelLoadRelease {
	@Shadow
	@Nullable
	private ChunkLoadStatusView serverChunkStatusView;

	@Inject(method = "isLevelReady", at = @At("RETURN"))
	private void iris$releaseCompletedServerView(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValue()) {
			iris$releaseLevelLoadReferences();
		}
	}

	@Override
	public void iris$releaseLevelLoadReferences() {
		serverChunkStatusView = null;
	}
}
