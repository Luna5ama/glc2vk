package dev.vibris.mod.mixin.sodium;

import dev.vibris.mod.VibrisClient;
import org.lwjgl.opengl.GL32C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.device.GLRenderDevice$ImmediateDrawCommandList", remap = false)
public abstract class MixinImmediateDrawCommandList {
	@Redirect(method = "multiDrawElementsBaseVertex", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32C;nglMultiDrawElementsBaseVertex(IJIJIJ)V"), require = 1, expect = 1)
	private void vibris$multiDrawElementsBaseVertex(int mode, long counts, int type, long offsets, int drawCount, long baseVertices) {
		if (!VibrisClient.captureManager().multiDrawElementsBaseVertex(mode, counts, type, offsets, drawCount, baseVertices)) {
			GL32C.nglMultiDrawElementsBaseVertex(mode, counts, type, offsets, drawCount, baseVertices);
		}
	}
}
