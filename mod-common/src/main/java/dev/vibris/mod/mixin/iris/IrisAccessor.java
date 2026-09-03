package dev.vibris.mod.mixin.iris;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.file.FileSystem;

@Mixin(value = Iris.class, remap = false)
public interface IrisAccessor {
	@Accessor("currentPack") static void vibris$setCurrentPack(ShaderPack pack) { throw new AssertionError(); }
	@Accessor("currentPackName") static void vibris$setCurrentPackName(String name) { throw new AssertionError(); }
	@Accessor("fallback") static boolean vibris$getFallback() { throw new AssertionError(); }
	@Accessor("fallback") static void vibris$setFallback(boolean fallback) { throw new AssertionError(); }
	@Accessor("zipFileSystem") static FileSystem vibris$getZipFileSystem() { throw new AssertionError(); }
	@Accessor("zipFileSystem") static void vibris$setZipFileSystem(FileSystem fileSystem) { throw new AssertionError(); }
	@Invoker("loadExternalShaderpack") static boolean vibris$loadExternalShaderpack(String name) { throw new AssertionError(); }
	@Invoker("closeShaderpackFileSystem") static void vibris$closeShaderpackFileSystem(FileSystem fileSystem) { throw new AssertionError(); }
	@Invoker("handleException") static void vibris$handleException(Exception exception) { throw new AssertionError(); }
}
