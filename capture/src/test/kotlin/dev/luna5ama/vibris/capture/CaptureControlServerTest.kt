package dev.luna5ama.vibris.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CaptureControlServerTest {
    private data class Control(val host: String, val port: Int, val token: String)

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test
    fun servesAuthenticatedControlRequestsAndCleansUp() {
        val temp = createTempDirectory("vibris-control-server-test")
        val controlFile = temp.resolve("control.json")
        val manager = CaptureManager()
        val server = CaptureControlServer(manager, Executor(Runnable::run), Callable<Void> { null })
        var closed = false
        try {
            server.start(controlFile)
            val control = readControl(controlFile)
            assertTrue(control.host.isNotBlank())
            assertTrue(control.port in 1..65535)
            assertTrue(control.token.isNotBlank())

            send(control, "status", authorization = null).also {
                assertEquals(401, it.statusCode())
                assertEquals("Unauthorized", it.body())
            }
            send(control, "status", authorization = "bearer ${control.token}").also {
                assertEquals(401, it.statusCode())
                assertEquals("Unauthorized", it.body())
            }
            send(control, "status").also {
                assertEquals(200, it.statusCode())
                assertJsonValue(it.body(), "pending", "false")
                assertJsonValue(it.body(), "active", "false")
                assertJsonValue(it.body(), "saving", "false")
                assertJsonValue(it.body(), "lastOutputPath", "null")
                assertJsonValue(it.body(), "lastError", "null")
            }

            assertServerError(
                send(control, "capture_pass", "{}"),
                "Missing required field: pass"
            )
            assertServerError(
                send(control, "capture_multi", "{\"type\":\"invalid\"}"),
                "Unsupported capturemulti type: invalid"
            )

            val output = temp.resolve("pass")
            send(
                control,
                "capture_pass",
                "{\"pass\":\"lighting\",\"path\":${jsonString(output.toString())}}"
            ).also {
                assertEquals(200, it.statusCode())
                assertJsonValue(it.body(), "ok", "true")
                assertTrue(manager.status().pending)
            }
            server.close()
            closed = true
            assertFalse(controlFile.exists())
            assertFails { send(control, "status") }
        } finally {
            try {
                if (!closed) server.close()
            } finally {
                temp.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun awaitsTheSuppliedDispatcher() {
        val temp = createTempDirectory("vibris-control-dispatch-test")
        val controlFile = temp.resolve("control.json")
        val queued = LinkedBlockingQueue<Runnable>()
        val reloads = AtomicInteger()
        val server = CaptureControlServer(
            CaptureManager(),
            Executor { queued.put(it) },
            Callable<Void> {
                reloads.incrementAndGet()
                null
            }
        )
        try {
            server.start(controlFile)
            val control = readControl(controlFile)
            val future = sendAsync(control, "reload_shader", "{}")
            val dispatched = assertNotNull(queued.poll(5, TimeUnit.SECONDS))

            assertFalse(future.isDone)
            dispatched.run()
            val response = future.get(5, TimeUnit.SECONDS)
            assertEquals(200, response.statusCode())
            assertJsonValue(response.body(), "ok", "true")
            assertEquals(1, reloads.get())
        } finally {
            try {
                server.close()
            } finally {
                temp.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun bridgesLiveJsonRpcOverPythonSubprocess() {
        val probe = ProcessBuilder("py", "-3", "--version").redirectErrorStream(true).start()
        val probeExited = probe.waitFor(5, TimeUnit.SECONDS)
        if (!probeExited) probe.destroyForcibly()
        assertTrue(probeExited, "Python launcher did not answer within five seconds")
        val probeOutput = probe.inputStream.bufferedReader().readText()
        assertEquals(0, probe.exitValue(), probeOutput)

        val script = Path.of(System.getProperty("user.dir"), "..", "tools", "vibris_capture_mcp.py").normalize()
        assertTrue(script.exists(), "Missing Python bridge: $script")
        val temp = createTempDirectory("vibris-control-bridge-test")
        val controlFile = temp.resolve("control.json")
        val server = CaptureControlServer(CaptureManager(), Executor(Runnable::run), Callable<Void> { null })
        var bridge: Process? = null
        var bridgeInput: BufferedWriter? = null
        try {
            server.start(controlFile)
            bridge = ProcessBuilder(
                "py",
                "-3",
                script.toString(),
                "--control-file",
                controlFile.toString()
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val writer = bridge.outputStream.bufferedWriter()
            bridgeInput = writer
            val bridgeOutput = bridge.inputStream.bufferedReader()
            val exchange: (String) -> kotlinx.serialization.json.JsonObject = { request ->
                writer.write(request)
                writer.newLine()
                writer.flush()
                val response = CompletableFuture.supplyAsync { bridgeOutput.readLine() }.get(5, TimeUnit.SECONDS)
                Json.parseToJsonElement(assertNotNull(response)).jsonObject
            }

            val initialize = exchange("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
            assertEquals("2024-11-05", initialize["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)

            val tools = exchange("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
            assertEquals(
                listOf(
                    "reload_shader",
                    "capture_pass",
                    "capture_multi",
                    "status",
                    "schedule_screenshot",
                    "dump_ssbo",
                    "dump_texture"
                ),
                tools["result"]!!.jsonObject["tools"]!!.jsonArray.map {
                    it.jsonObject["name"]!!.jsonPrimitive.content
                }
            )

            val statusResponse = exchange(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"status","arguments":{}}}"""
            )
            val statusText = statusResponse["result"]!!.jsonObject["content"]!!.jsonArray.single()
                .jsonObject["text"]!!.jsonPrimitive.content
            val status = Json.parseToJsonElement(statusText).jsonObject
            assertFalse(status["pending"]!!.jsonPrimitive.boolean)
            assertFalse(status["active"]!!.jsonPrimitive.boolean)
            assertFalse(status["saving"]!!.jsonPrimitive.boolean)
            assertTrue(status["lastOutputPath"] is JsonNull)
            assertTrue(status["lastError"] is JsonNull)

            val unknown = exchange("""{"jsonrpc":"2.0","id":4,"method":"unknown"}""")
            assertEquals(-32601, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

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

    private fun readControl(path: Path): Control {
        val json = path.readText()
        return Control(stringField(json, "host"), intField(json, "port"), stringField(json, "token"))
    }

    private fun stringField(json: String, name: String): String =
        requireNotNull(Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(json)).groupValues[1]

    private fun intField(json: String, name: String): Int =
        requireNotNull(Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(\\d+)").find(json)).groupValues[1].toInt()

    private fun send(
        control: Control,
        endpoint: String,
        body: String? = null,
        authorization: String? = "Bearer ${control.token}"
    ): HttpResponse<String> = client.send(
        request(control, endpoint, body, authorization),
        HttpResponse.BodyHandlers.ofString()
    )

    private fun sendAsync(
        control: Control,
        endpoint: String,
        body: String
    ) = client.sendAsync(
        request(control, endpoint, body, "Bearer ${control.token}"),
        HttpResponse.BodyHandlers.ofString()
    )

    private fun request(
        control: Control,
        endpoint: String,
        body: String?,
        authorization: String?
    ): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create("http://${control.host}:${control.port}/$endpoint"))
            .timeout(Duration.ofSeconds(5))
        authorization?.let { builder.header("Authorization", it) }
        if (body == null) {
            builder.GET()
        } else {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        }
        return builder.build()
    }

    private fun assertServerError(response: HttpResponse<String>, message: String? = null) {
        assertEquals(500, response.statusCode())
        assertJsonValue(response.body(), "ok", "false")
        assertTrue(Regex("\\\"error\\\"\\s*:").containsMatchIn(response.body()))
        message?.let { assertTrue(response.body().contains(it)) }
    }

    private fun assertJsonValue(json: String, name: String, value: String) {
        assertTrue(Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*${Regex.escape(value)}").containsMatchIn(json))
    }

    private fun jsonString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}