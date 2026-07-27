package dev.luna5ama.vibris.capture

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

class CaptureControlServer(
    private val captureManager: CaptureManager,
    private val dispatcher: Executor,
    private val reloadShader: Callable<Void>
) : AutoCloseable {
    private var server: HttpServer? = null
    private var httpExecutor: ExecutorService? = null
    private var controlFile: Path? = null

    @Synchronized
    @JvmOverloads
    @Throws(IOException::class)
    fun start(controlFile: Path = Path.of("vibris-capture-control.json")) {
        if (server != null) return

        val token = UUID.randomUUID().toString()
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "Vibris Capture Control").apply { isDaemon = true }
        }
        var startedServer: HttpServer? = null
        try {
            startedServer = HttpServer.create(InetSocketAddress(HOST, 0), 0).apply {
                createContext("/status") { handleStatus(it, token) }
                createContext("/reload_shader") { handleReloadShader(it, token) }
                createContext("/capture_pass") { handleCapturePass(it, token) }
                createContext("/capture_multi") { handleCaptureMulti(it, token) }
                setExecutor(executor)
                start()
            }
            Files.writeString(
                controlFile,
                buildJsonObject {
                    put("host", HOST)
                    put("port", startedServer.address.port)
                    put("token", token)
                }.toString(),
                StandardCharsets.UTF_8
            )
            this.server = startedServer
            this.httpExecutor = executor
            this.controlFile = controlFile
        } catch (failure: Throwable) {
            startedServer?.stop(0)
            executor.shutdownNow()
            try {
                Files.deleteIfExists(controlFile)
            } catch (cleanupFailure: IOException) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        val server = server ?: return
        val executor = httpExecutor
        val controlFile = controlFile
        this.server = null
        this.httpExecutor = null
        this.controlFile = null

        server.stop(0)
        executor?.shutdownNow()
        if (controlFile != null) Files.deleteIfExists(controlFile)
    }

    private fun handleStatus(exchange: HttpExchange, token: String) {
        handle(exchange, token) {
            val status = captureManager.status()
            sendJson(exchange, 200, buildJsonObject {
                put("pending", status.pending)
                put("active", status.active)
                put("saving", status.saving)
                put("lastOutputPath", status.lastOutputPath?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("lastError", status.lastError?.let(::JsonPrimitive) ?: JsonNull)
            })
        }
    }

    private fun handleReloadShader(exchange: HttpExchange, token: String) {
        handle(exchange, token) {
            runOnDispatcher(reloadShader)
            sendOk(exchange)
        }
    }

    private fun handleCapturePass(exchange: HttpExchange, token: String) {
        handle(exchange, token) {
            val request = readJson(exchange)
            val pass = requireString(request, "pass")
            val path = optionalPath(request, "path", CaptureManager.defaultOutputPath(pass))
            runOnDispatcher(Callable<Void> {
                captureManager.prepareSingleCapture(path, pass)
                null
            })
            sendQueued(exchange, path)
        }
    }

    private fun handleCaptureMulti(exchange: HttpExchange, token: String) {
        handle(exchange, token) {
            val request = readJson(exchange)
            val type = requireString(request, "type")
            val path = optionalPath(request, "path", CaptureManager.defaultOutputPath(type))
            runOnDispatcher(Callable<Void> {
                captureManager.prepareMultiCapture(path, type)
                null
            })
            sendQueued(exchange, path)
        }
    }

    private fun handle(exchange: HttpExchange, token: String, action: () -> Unit) {
        try {
            if (!checkAuth(exchange, token)) return
            action()
        } catch (exception: Exception) {
            sendError(exchange, exception)
        }
    }

    private fun checkAuth(exchange: HttpExchange, token: String): Boolean {
        if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $token") {
            sendText(exchange, 401, "Unauthorized")
            return false
        }
        return true
    }

    private fun readJson(exchange: HttpExchange): JsonObject {
        val body = String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)
        return if (body.isBlank()) JsonObject(emptyMap()) else JSON.parseToJsonElement(body).jsonObject
    }

    private fun requireString(json: JsonObject, key: String): String {
        val value = json[key]
        if (value == null || value is JsonNull) throw IllegalArgumentException("Missing required field: $key")
        return value.jsonPrimitive.content
    }

    private fun optionalPath(json: JsonObject, key: String, defaultPath: Path): Path {
        val value = json[key]
        if (value == null || value is JsonNull || value.jsonPrimitive.content.isBlank()) return defaultPath
        return Path.of(value.jsonPrimitive.content)
    }

    private fun <T> runOnDispatcher(callable: Callable<T>): T {
        val task = FutureTask(callable)
        dispatcher.execute(task)
        try {
            return task.get()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: ExecutionException) {
            when (val cause = exception.cause) {
                is Exception -> throw cause
                is Error -> throw cause
                else -> throw exception
            }
        }
    }

    private fun sendOk(exchange: HttpExchange) =
        sendJson(exchange, 200, buildJsonObject { put("ok", true) })

    private fun sendQueued(exchange: HttpExchange, path: Path) =
        sendJson(exchange, 200, buildJsonObject {
            put("ok", true)
            put("path", path.toString())
        })

    private fun sendError(exchange: HttpExchange, exception: Exception) =
        sendJson(exchange, 500, buildJsonObject {
            put("ok", false)
            put("error", exception.message?.let(::JsonPrimitive) ?: JsonNull)
        })

    private fun sendJson(exchange: HttpExchange, status: Int, body: JsonObject) =
        sendText(exchange, status, body.toString())

    private fun sendText(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val HOST = "127.0.0.1"
        val JSON = Json
    }
}