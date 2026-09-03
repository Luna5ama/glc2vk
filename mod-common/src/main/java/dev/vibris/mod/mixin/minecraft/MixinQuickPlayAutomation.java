package dev.vibris.mod.mixin.minecraft;

import com.mojang.realmsclient.client.RealmsClient;
import dev.vibris.mod.IrisVibrisAutomation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.quickplay.QuickPlay;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(QuickPlay.class)
public abstract class MixinQuickPlayAutomation {
	@Inject(method = "connect", at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
	private static void vibris$createMissingWorld(Minecraft minecraft, GameConfig.QuickPlayVariant variant,
		RealmsClient realmsClient, CallbackInfo ci) {
		if (IrisVibrisAutomation.enabled() && variant instanceof GameConfig.QuickPlaySinglePlayerData singlePlayer &&
			!minecraft.getLevelSource().levelExists(singlePlayer.worldId())) {
			ci.cancel();
			vibris$createWorld(minecraft, singlePlayer.worldId());
		}
	}

	@Inject(method = "joinSingleplayerWorld", at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
	private static void vibris$openWorld(Minecraft minecraft, String name, CallbackInfo ci) {
		if (!IrisVibrisAutomation.enabled()) return;
		ci.cancel();
		if (!minecraft.getLevelSource().levelExists(name)) vibris$createWorld(minecraft, name);
		else minecraft.createWorldOpenFlows().openWorld(name, () -> minecraft.setScreen(new TitleScreen()));
	}

	private static void vibris$createWorld(Minecraft minecraft, String name) {
		minecraft.createWorldOpenFlows().createFreshLevel(name, new LevelSettings(name, GameType.CREATIVE, false,
			Difficulty.HARD, true, new GameRules(FeatureFlagSet.of(FeatureFlags.MINECART_IMPROVEMENTS,
			FeatureFlags.REDSTONE_EXPERIMENTS)), WorldDataConfiguration.DEFAULT), WorldOptions.defaultWithRandomSeed(),
			WorldPresets::createNormalWorldDimensions, minecraft.screen);
	}
}
