package dev.vibris.mod.mixin.minecraft;

import dev.vibris.mod.VibrisClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraftShutdown {
	@Inject(method = "destroy", at = @At("HEAD"), require = 1, expect = 1)
	private void vibris$close(CallbackInfo ci) {
		VibrisClient.close();
	}
}
