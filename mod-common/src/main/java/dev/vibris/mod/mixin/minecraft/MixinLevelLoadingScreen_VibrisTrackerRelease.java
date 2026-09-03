package dev.vibris.mod.mixin.minecraft;

import dev.vibris.mod.mixinterface.VibrisLevelLoadRelease;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLoadingScreen.class)
public class MixinLevelLoadingScreen_VibrisTrackerRelease implements VibrisLevelLoadRelease {
	@Shadow
	private LevelLoadTracker loadTracker;

	@Inject(method = "update", at = @At("HEAD"))
	private void iris$releaseReplacedTracker(LevelLoadTracker nextTracker,
		LevelLoadingScreen.Reason reason, CallbackInfo ci) {
		if (loadTracker != nextTracker && loadTracker instanceof VibrisLevelLoadRelease release) {
			release.iris$releaseLevelLoadReferences();
		}
	}

	@Override
	public void iris$releaseLevelLoadReferences() {
		if (loadTracker instanceof VibrisLevelLoadRelease release) {
			release.iris$releaseLevelLoadReferences();
		}
	}
}
