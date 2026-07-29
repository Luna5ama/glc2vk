package dev.vibris.testruntime

import dev.vibris.core.VibrisControlService
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FakeVibrisServer private constructor(
    private val server: Server,
    private val service: VibrisControlService,
    private val probe: FakeRuntimeProbe,
) : AutoCloseable {
    fun port(): Int = server.port

    @Throws(InterruptedException::class)
    fun awaitTermination() {
        server.awaitTermination()
    }

    fun isTerminated(): Boolean = server.isTerminated

    fun phaseThreeProbe(): FakeRuntimeProbe = probe

    @Throws(InterruptedException::class)
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        server.shutdown()
        if (!server.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            server.shutdownNow()
            server.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        }
        service.close()
    }

    private val closed = AtomicBoolean()

    companion object {
        private val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(5)

        @JvmStatic
        @Throws(IOException::class)
        fun start(port: Int, workRoot: Path): FakeVibrisServer {
            requirePort(port)
            val root = workRoot.toAbsolutePath().normalize()
            return start(port, root.resolve("pending-shaders"), root.resolve("artifacts"))
        }

        @JvmStatic
        @Throws(IOException::class)
        fun start(port: Int, pendingShadersRoot: Path, artifactRoot: Path): FakeVibrisServer {
            requirePort(port)
            val pending = pendingShadersRoot.toAbsolutePath().normalize()
            val artifacts = artifactRoot.toAbsolutePath().normalize()
            Files.createDirectories(pending)
            Files.createDirectories(artifacts)
            val loopback = InetAddress.getByName("127.0.0.1")
            val runtime = FakeRuntimeAdapter()
            val service = VibrisControlService(pending, artifacts, runtime)
            try {
                val server = NettyServerBuilder.forAddress(InetSocketAddress(loopback, port))
                    .addService(service)
                    .build()
                    .start()
                return FakeVibrisServer(server, service, FakeRuntimeProbe(runtime, service.probe()))
            } catch (exception: IOException) {
                service.close()
                throw exception
            } catch (exception: RuntimeException) {
                service.close()
                throw exception
            }
        }

        private fun requirePort(port: Int) {
            require(port in 0..65_535) { "Port must be between 0 and 65535" }
        }
    }
}