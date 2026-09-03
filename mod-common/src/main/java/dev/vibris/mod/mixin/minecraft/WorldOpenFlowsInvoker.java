package dev.vibris.mod.mixin.minecraft;

import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(WorldOpenFlows.class)
public interface WorldOpenFlowsInvoker {
	@Invoker("openWorldLoadLevelData")
	void iris$openWorldLoadLevelData(LevelStorageSource.LevelStorageAccess access, Runnable rejectionCallback);
}
