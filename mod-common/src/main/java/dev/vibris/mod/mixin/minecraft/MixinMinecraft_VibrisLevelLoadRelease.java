package dev.vibris.mod.mixin.minecraft;

import dev.vibris.mod.mixinterface.VibrisLevelLoadRelease;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft_VibrisLevelLoadRelease {
	@Shadow
	@Nullable
	public Screen screen;

	@Inject(method = "setScreen", at = @At("HEAD"))
	private void iris$releaseAbandonedLevelLoad(Screen nextScreen, CallbackInfo ci) {
		if (screen != nextScreen && screen instanceof VibrisLevelLoadRelease release) {
			release.iris$releaseLevelLoadReferences();
		}
	}
}
