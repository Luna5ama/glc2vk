package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ShaderDebugCatalogTest {
    @Test
    fun returnsFlatNamespacedTextureAndBufferMetadataWithoutGlIds() {
        val control = ShaderDebugControl(object : ShaderDebugHost {
            override fun shaderPackName() = "test"
            override fun reloadShaders() = Unit
            override fun gameDirectory(): Path = Path.of(".")
            override fun debugShadersEnabled() = true
            override fun storageBuffers() = listOf(StorageBufferInfo("iris_ssbo_6", 99, 4_194_304))
            override fun textureCatalog() = TextureCatalog(listOf(
                TextureInfo("colortex0.main", 1, "colortex", "TEXTURE_2D", 1920, 1080, 1, 1,
                    "RGBA16F", "RGBA", "float", 16),
                TextureInfo("colortex0.alt", 2, "colortex", "TEXTURE_2D", 1920, 1080, 1, 1,
                    "RGBA16F", "RGBA", "float", 16),
                TextureInfo("custom_texture.gbuffers.gtexture", 3, "custom_texture", "TEXTURE_2D",
                    1024, 1024, 1, 11, "RGBA8", "RGBA", "unorm", 8),
                TextureInfo("iris_custom_texture.foo", 4, "iris_custom_texture", "TEXTURE_2D",
                    16, 16, 1, 1, "RG8", "RG", "unorm", 8),
                TextureInfo("iris_custom_image.foo", 5, "iris_custom_image", "TEXTURE_3D",
                    16, 16, 8, 1, "R16F", "R", "float", 16),
                TextureInfo("gbuffers_terrain.normals", 6, "terrain_atlas", "TEXTURE_2D",
                    2048, 2048, 1, 12, "RGBA8", "RGBA", "unorm", 8),
            ))
            override fun resolveTexture(name: String): Int? = null
        })

        val textures = control.texturesJson()["textures"]!!.jsonArray
        assertEquals(listOf("colortex0.main", "colortex0.alt", "custom_texture.gbuffers.gtexture",
            "iris_custom_texture.foo", "iris_custom_image.foo", "gbuffers_terrain.normals"),
            textures.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("terrain_atlas", textures.last().jsonObject["category"]!!.jsonPrimitive.content)
        assertEquals("12", textures.last().jsonObject["mip_levels"]!!.jsonPrimitive.content)
        assertFalse(control.texturesJson().toString().contains("textureId"))

        val buffer = control.buffersJson()["buffers"]!!.jsonArray.single().jsonObject
        assertEquals("iris_ssbo_6", buffer["name"]!!.jsonPrimitive.content)
        assertEquals("4194304", buffer["size_bytes"]!!.jsonPrimitive.content)
        assertFalse(buffer.containsKey("binding_index"))
        assertFalse(buffer.containsKey("gl_id"))
    }
}
