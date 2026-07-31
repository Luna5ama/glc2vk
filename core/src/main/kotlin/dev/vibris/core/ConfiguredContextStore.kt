package dev.vibris.core

import dev.vibris.api.SceneContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import dev.vibris.protocol.v1.SceneContext as ProtocolSceneContext

internal class ConfiguredContextStore(private val file: Path) {
    fun load(): SceneContext? {
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) {
            "Configured Vibris context is not an ordinary file"
        }
        val size = Files.size(file)
        require(size in 1..MAX_BYTES) { "Configured Vibris context has an invalid size" }
        return RuntimeJobContext.toApi(ProtocolSceneContext.parseFrom(Files.readAllBytes(file)))
    }

    fun save(context: SceneContext) {
        val parent = file.parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".configured-context-", ".tmp")
        try {
            Files.write(temporary, RuntimeJobContext.toProtocol(context).toByteArray())
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private const val MAX_BYTES = 64L * 1024
    }
}
