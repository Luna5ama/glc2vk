package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShaderDebugMcpTest {
    @Test
    fun bridgesShaderDebugToolsAndResourcesOverLiveMcp() {
        val probe = ProcessBuilder("py", "-3", "--version").redirectErrorStream(true).start()
        assertTrue(probe.waitFor(5, TimeUnit.SECONDS), "Python launcher did not answer within five seconds")
        assertEquals(0, probe.exitValue(), probe.inputStream.bufferedReader().readText())

        val script = Path.of(System.getProperty("user.dir"), "src", "main", "python", "vibris_capture_mcp.py")
        assertTrue(script.exists(), "Missing Python bridge: $script")
        val temp = createTempDirectory("vibris-shader-debug-mcp")
        val controlFile = temp.resolve("control.json")
        val host = FakeHost(temp)
        val shaderControl = ShaderDebugControl(host, FakeDumper)
        val server = CaptureControlServer(
            CaptureManager(),
            Executor(Runnable::run),
            Callable<Void> { null },
            shaderControl
        )
        var bridge: Process? = null
        var bridgeInput: BufferedWriter? = null
        try {
            server.start(controlFile)
            assertUnauthorized(controlFile)

            bridge = ProcessBuilder(
                "py",
                "-3",
                script.toString(),
                "--control-file",
                controlFile.toString()
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val writer = bridge.outputStream.bufferedWriter()
            bridgeInput = writer
            val reader = bridge.inputStream.bufferedReader()
            var requestId = 0

            fun exchange(method: String, params: String = "{}"): JsonObject {
                requestId++
                writer.write("""{"jsonrpc":"2.0","id":$requestId,"method":"$method","params":$params}""")
                writer.newLine()
                writer.flush()
                val line = CompletableFuture.supplyAsync(reader::readLine).get(5, TimeUnit.SECONDS)
                return Json.parseToJsonElement(assertNotNull(line)).jsonObject
            }

            fun callTool(name: String, arguments: String = "{}"): JsonObject {
                val response = exchange("tools/call", """{"name":"$name","arguments":$arguments}""")
                val text = response["result"]!!.jsonObject["content"]!!.jsonArray.single()
                    .jsonObject["text"]!!.jsonPrimitive.content
                return Json.parseToJsonElement(text).jsonObject
            }

            fun readResource(uri: String): JsonObject {
                val response = exchange("resources/read", """{"uri":"$uri"}""")
                val item = response["result"]!!.jsonObject["contents"]!!.jsonArray.single().jsonObject
                assertEquals(uri, item["uri"]!!.jsonPrimitive.content)
                assertEquals("application/json", item["mimeType"]!!.jsonPrimitive.content)
                return Json.parseToJsonElement(item["text"]!!.jsonPrimitive.content).jsonObject
            }

            val resources = exchange("resources/list")["result"]!!.jsonObject["resources"]!!.jsonArray
            assertEquals(7, resources.size)

            val shaderStatus = readResource("vibris://shader/status")
            assertEquals("Complementary", shaderStatus["shaderpack"]!!.jsonPrimitive.content)
            assertTrue(shaderStatus["pack_loaded"]!!.jsonPrimitive.boolean)

            val reload = callTool("reload_shader")
            assertTrue(reload["success"]!!.jsonPrimitive.boolean)
            assertEquals(1, host.reloadCount)

            shaderControl.recordError("ShaderCompileException", "composite.csh", "compile failed", "trace", 1234)
            val error = readResource("vibris://shader/errors")["errors"]!!.jsonArray.single().jsonObject
            assertEquals("composite.csh", error["filename"]!!.jsonPrimitive.content)
            assertEquals(1234, error["timestamp"]!!.jsonPrimitive.int)

            val scheduled = callTool("schedule_screenshot", """{"frames":1}""")
            assertTrue(scheduled["scheduled"]!!.jsonPrimitive.boolean)
            shaderControl.tickScreenshot()
            assertEquals(
                host.screenshotPath.toString(),
                readResource("vibris://shader/screenshot-result")["path"]!!.jsonPrimitive.content
            )

            assertTrue(readResource("vibris://shader/metrics")["gpuTimings"]!!.jsonObject.isEmpty())

            val buffer = readResource("vibris://shader/storage-buffers")["buffers"]!!
                .jsonArray.single().jsonObject
            assertEquals(7, buffer["index"]!!.jsonPrimitive.int)
            assertEquals(17, callTool("dump_ssbo", """{"index":7}""")["bufferId"]!!.jsonPrimitive.int)

            val textures = readResource("vibris://shader/textures")
            assertEquals(
                "colortex0",
                textures["colortex"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content
            )
            assertEquals("normals", textures["custom"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
            val texture = callTool("dump_texture", """{"name":"normals","raw":true}""")
            assertEquals(12, texture["textureId"]!!.jsonPrimitive.int)
            assertEquals("normals", texture["name"]!!.jsonPrimitive.content)
            assertTrue(texture["path"]!!.jsonPrimitive.content.endsWith("texture_dumps\\normals.bin"))

            val patched = readResource("vibris://shader/patched-shaders")
            assertTrue(patched["debugEnabled"]!!.jsonPrimitive.boolean)
            assertEquals(
                setOf("a.csh", "b.csh"),
                patched["files"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            )

            writer.close()
            bridgeInput = null
            assertTrue(bridge.waitFor(5, TimeUnit.SECONDS), "Python bridge did not exit after stdin closed")
            assertEquals(0, bridge.exitValue())
        } finally {
            bridgeInput?.close()
            bridge?.let {
                if (it.isAlive) {
                    it.destroy()
                    if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly()
                }
            }
            try {
                server.close()
            } finally {
                temp.toFile().deleteRecursively()
            }
        }
    }

    private fun assertUnauthorized(controlFile: Path) {
        val control = Json.parseToJsonElement(controlFile.toFile().readText()).jsonObject
        val uri = URI.create(
            "http://" + control["host"]!!.jsonPrimitive.content + ":" +
                control["port"]!!.jsonPrimitive.int + "/shader/status"
        )
        val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
            HttpRequest.newBuilder(uri).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(401, response.statusCode())
        assertEquals("Unauthorized", response.body())
    }

    private class FakeHost(private val root: Path) : ShaderDebugHost {
        var reloadCount = 0
        val screenshotPath: Path = root.resolve("screenshots").resolve("shot.png")

        init {
            root.resolve("patched_shaders").createDirectories()
            root.resolve("patched_shaders").resolve("a.csh").createFile()
            root.resolve("patched_shaders").resolve("b.csh").createFile()
        }

        override fun shaderPackName() = "Complementary"

        override fun reloadShaders() {
            reloadCount++
        }

        override fun captureScreenshot(onSaved: Consumer<Path>) = onSaved.accept(screenshotPath)
        override fun gameDirectory() = root
        override fun debugShadersEnabled() = true
        override fun storageBuffers() = listOf(StorageBufferInfo(7, 17))

        override fun textureCatalog() = TextureCatalog(
            listOf(TextureInfo("colortex0", 11, 1920, 1080)),
            listOf(TextureInfo("normals", 12, null, null))
        )

        override fun resolveTexture(name: String): Int? = if (name == "normals") 12 else null
    }

    private object FakeDumper : ShaderDebugResourceDumper {
        override fun dumpStorageBuffer(buffer: StorageBufferInfo, output: Path): JsonObject = buildJsonObject {
            put("success", true)
            put("path", output.toAbsolutePath().toString())
            put("bufferId", buffer.glId)
            put("totalBytes", 4)
        }

        override fun dumpTexture(textureId: Int, output: Path, raw: Boolean): JsonObject = buildJsonObject {
            put("success", true)
            put("path", output.toString())
            put("textureId", textureId)
            put("width", 1)
            put("height", 1)
        }
    }
}