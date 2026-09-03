package dev.vibris.mod.mixin.iris;

import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;

@Mixin(value = ShaderPrinter.class, remap = false)
public interface ShaderPrinterAccess {
	@Invoker("awaitPendingWrites")
	static void vibris$awaitPendingWrites() throws IOException {
		throw new AssertionError();
	}
}
