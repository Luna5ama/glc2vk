package dev.vibris.mod.mixin.minecraft;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlStateManager;
import dev.vibris.mod.VibrisClient;
import net.irisshaders.iris.vertices.ImmediateState;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL46C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GlCommandEncoder.class)
public abstract class MixinGlCommandEncoderVibris {
	@Redirect(method = "drawFromBuffers", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_drawArrays(III)V"), require = 1, expect = 1)
	private void vibris$drawArrays(int mode, int first, int count) {
		if (!VibrisClient.captureManager().drawArrays(mode, first, count, 1)) GlStateManager._drawArrays(mode, first, count);
	}

	@Redirect(method = "drawFromBuffers", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL31;glDrawArraysInstanced(IIII)V"), require = 1, expect = 1)
	private void vibris$drawArraysInstanced(int mode, int first, int count, int instances) {
		if (!VibrisClient.captureManager().drawArrays(mode, first, count, instances)) GL31C.glDrawArraysInstanced(mode, first, count, instances);
	}

	@Redirect(method = "drawFromBuffers", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_drawElements(IIIJ)V"), require = 1, expect = 1)
	private void vibris$drawElements(int mode, int count, int type, long offset) {
		if (!VibrisClient.captureManager().drawElements(vibris$mode(mode), count, type, offset, 0, 1)) GlStateManager._drawElements(mode, count, type, offset);
	}

	@Redirect(method = "drawFromBuffers", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32;glDrawElementsBaseVertex(IIIJI)V"), require = 1, expect = 1)
	private void vibris$drawElementsBaseVertex(int mode, int count, int type, long offset, int baseVertex) {
		if (!VibrisClient.captureManager().drawElements(vibris$mode(mode), count, type, offset, baseVertex, 1)) GL32C.glDrawElementsBaseVertex(mode, count, type, offset, baseVertex);
	}

	@Redirect(method = "drawFromBuffers", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL31;glDrawElementsInstanced(IIIJI)V"), require = 1, expect = 1)
	private void vibris$drawElementsInstanced(int mode, int count, int type, long offset, int instances) {
		if (!VibrisClient.captureManager().drawElements(vibris$mode(mode), count, type, offset, 0, instances)) GL31C.glDrawElementsInstanced(mode, count, type, offset, instances);
	}

	@Redirect(method = "drawFromBuffers", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32;glDrawElementsInstancedBaseVertex(IIIJII)V"), require = 1, expect = 1)
	private void vibris$drawElementsInstancedBaseVertex(int mode, int count, int type, long offset, int instances, int baseVertex) {
		if (!VibrisClient.captureManager().drawElements(vibris$mode(mode), count, type, offset, baseVertex, instances)) GL32C.glDrawElementsInstancedBaseVertex(mode, count, type, offset, instances, baseVertex);
	}

	@Unique private static int vibris$mode(int mode) {
		return mode == GL46C.GL_TRIANGLES && ImmediateState.usingTessellation ? GL46C.GL_PATCHES : mode;
	}
}
