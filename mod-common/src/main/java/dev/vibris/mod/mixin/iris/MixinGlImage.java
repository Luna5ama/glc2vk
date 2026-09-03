package dev.vibris.mod.mixin.iris;

import dev.vibris.mod.GlImageAccess;
import net.irisshaders.iris.gl.image.GlImage;
import org.lwjgl.opengl.ARBClearTexture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = GlImage.class, remap = false)
public abstract class MixinGlImage implements GlImageAccess {
	@Override
	public void vibris$clearContents() {
		GlImage image = (GlImage) (Object) this;
		ARBClearTexture.glClearTexImage(
			image.getId(), 0, image.getFormat().getGlFormat(), image.getPixelType().getGlFormat(), (int[]) null);
	}
}
