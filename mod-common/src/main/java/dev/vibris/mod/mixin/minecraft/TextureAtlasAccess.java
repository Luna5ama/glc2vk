package dev.vibris.mod.mixin.minecraft;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(TextureAtlas.class)
public interface TextureAtlasAccess {
	@Accessor("animatedTexturesStates")
	List<SpriteContents.AnimationState> vibris$animatedTextureStates();

	@Accessor("maxMipLevel")
	int vibris$maxMipLevel();
}
