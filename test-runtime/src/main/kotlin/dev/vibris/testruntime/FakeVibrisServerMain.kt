package dev.vibris.testruntime

import dev.vibris.core.VibrisBootstrap
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path

object FakeVibrisServerMain {
    @JvmStatic
    @Throws(Exception::class)
    fun main(arguments: Array<String>) {
        val options = Options.parse(arguments)
        val shaderpackRoot = options.shaderpackRoot
        if (shaderpackRoot != null) {
            runBootstrap(options, shaderpackRoot)
            return
        }
        val server = FakeVibrisServer.start(options.port, options.pendingRoot, options.artifactRoot)
        val shutdownHook = Thread(
            { close(server) },
            "Vibris Test Runtime Shutdown",
        )
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            if (options.probeControlStdin) {
                awaitProbeShutdown()
                server.close()
                ProbeJson.write(
                    server.phaseThreeProbe().snapshot(),
                    server.phaseThreeProbe().maxConcurrentRuntimeOperations(),
                    System.out,
                )
            } else {
                server.awaitTermination()
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (_: IllegalStateException) {
            }
            server.close()
        }
    }

    @Throws(Exception::class)
    private fun runBootstrap(options: Options, shaderpackRoot: Path) {
        val runtime = FakeRuntimeAdapter()
        try {
            VibrisBootstrap.start(
                VibrisBootstrap.Config(
                    options.port,
                    options.pendingRoot,
                    options.artifactRoot,
                    shaderpackRoot,
                ),
                runtime,
            ).use {
                awaitProbeShutdown()
                System.out.println("{\"type\":\"ShutdownComplete\",\"code\":\"OK\"}")
            }
        } catch (failure: VibrisBootstrap.Failure) {
            System.out.println(
                "{\"type\":\"StartupFailed\",\"code\":\"" + failure.code().name + "\"}",
            )
        }
    }

    @Throws(Exception::class)
    private fun awaitProbeShutdown() {
        val input = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            var line = input.readLine() ?: return
            if (line.isNotEmpty() && line[0] == '\uFEFF') {
                line = line.substring(1)
            }
            if (line == "shutdown") {
                return
            }
            if (line.isNotBlank()) {
                throw IllegalArgumentException("Unknown probe control command: $line")
            }
        }
    }

    private fun close(server: FakeVibrisServer) {
        try {
            server.close()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @JvmRecord
    private data class Options(
        val port: Int,
        val workRoot: Path,
        val pendingRoot: Path,
        val artifactRoot: Path,
        val shaderpackRoot: Path?,
        val probeControlStdin: Boolean,
    ) {
        companion object {
            fun parse(arguments: Array<String>): Options {
                var port: Int? = null
                var workRoot: Path? = null
                var pendingRoot: Path? = null
                var artifactRoot: Path? = null
                var shaderpackRoot: Path? = null
                var probeControlStdin = false
                var index = 0
                while (index < arguments.size) {
                    when (arguments[index]) {
                        "--port" -> {
                            index++
                            port = requireValue(arguments, index, "--port").toInt()
                        }
                        "--work-root" -> {
                            index++
                            workRoot = Path.of(requireValue(arguments, index, "--work-root"))
                        }
                        "--pending-root" -> {
                            index++
                            pendingRoot = Path.of(requireValue(arguments, index, "--pending-root"))
                        }
                        "--artifact-root" -> {
                            index++
                            artifactRoot = Path.of(requireValue(arguments, index, "--artifact-root"))
                        }
                        "--shaderpack-root" -> {
                            index++
                            shaderpackRoot = Path.of(requireValue(arguments, index, "--shaderpack-root"))
                        }
                        "--probe-control-stdin" -> probeControlStdin = true
                        else -> throw IllegalArgumentException("Unknown argument: ${arguments[index]}")
                    }
                    index++
                }
                val actualPort = port
                    ?: throw IllegalArgumentException("Missing required argument: --port")
                val actualWorkRoot = workRoot
                    ?: throw IllegalArgumentException("Missing required argument: --work-root")
                val root = actualWorkRoot.toAbsolutePath().normalize()
                val resolvedPending = pendingRoot ?: root.resolve("pending-shaders")
                val resolvedArtifacts = artifactRoot ?: root.resolve("artifacts")
                if (shaderpackRoot != null && !probeControlStdin) {
                    throw IllegalArgumentException("--shaderpack-root requires --probe-control-stdin")
                }
                return Options(
                    actualPort,
                    root,
                    resolvedPending,
                    resolvedArtifacts,
                    shaderpackRoot,
                    probeControlStdin,
                )
            }

            private fun requireValue(
                arguments: Array<String>,
                index: Int,
                option: String,
            ): String {
                if (index >= arguments.size) {
                    throw IllegalArgumentException("Missing value for $option")
                }
                return arguments[index]
            }
        }
    }
}