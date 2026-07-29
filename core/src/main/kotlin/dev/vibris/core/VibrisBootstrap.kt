package dev.vibris.core

import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v1.ErrorCode
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import java.util.Objects
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VibrisBootstrap private constructor(
    private val service: VibrisControlService,
    private val pendingSources: PendingSourceRoot,
    private val listener: Listener,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun port(): Int = listener.port()

    @Throws(Failure::class)
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        var failure: Failure? = null
        try {
            listener.stopAdmission()
        } catch (exception: Exception) {
            failure = Failure(ErrorCode.INTERNAL_ERROR, "Failed to stop Vibris admission.", exception)
        }
        try {
            service.close()
        } catch (exception: Exception) {
            failure = append(failure, "Failed to close the Vibris runtime.", exception)
        }
        try {
            listener.awaitTermination()
        } catch (exception: Exception) {
            failure = append(failure, "Failed to finish Vibris listener shutdown.", exception)
        }
        try {
            pendingSources.clear()
        } catch (exception: Exception) {
            failure = append(failure, "Failed to clear pending Vibris sources.", exception)
        }
        if (failure != null) {
            throw failure
        }
    }

    class Config(
        val port: Int,
        pendingShadersRoot: Path?,
        artifactRoot: Path?,
        shaderpackRoot: Path?,
    ) {
        val pendingShadersRoot: Path
        val artifactRoot: Path
        val shaderpackRoot: Path

        init {
            if (port < 0 || port > 65_535) {
                throw IllegalArgumentException("port is outside 0..65535")
            }
            this.pendingShadersRoot = normalize(pendingShadersRoot, "pendingShadersRoot")
            this.artifactRoot = normalize(artifactRoot, "artifactRoot")
            this.shaderpackRoot = normalize(shaderpackRoot, "shaderpackRoot")
        }

        fun port(): Int = port

        fun pendingShadersRoot(): Path = pendingShadersRoot

        fun artifactRoot(): Path = artifactRoot

        fun shaderpackRoot(): Path = shaderpackRoot

        override fun equals(other: Any?): Boolean {
            return this === other ||
                other is Config &&
                port == other.port &&
                pendingShadersRoot == other.pendingShadersRoot &&
                artifactRoot == other.artifactRoot &&
                shaderpackRoot == other.shaderpackRoot
        }

        override fun hashCode(): Int {
            var result = port
            result = 31 * result + pendingShadersRoot.hashCode()
            result = 31 * result + artifactRoot.hashCode()
            result = 31 * result + shaderpackRoot.hashCode()
            return result
        }

        override fun toString(): String {
            return "Config[port=$port, pendingShadersRoot=$pendingShadersRoot, " +
                "artifactRoot=$artifactRoot, shaderpackRoot=$shaderpackRoot]"
        }

        companion object {
            private fun normalize(path: Path?, name: String): Path {
                return Objects.requireNonNull(path, name)!!.toAbsolutePath().normalize()
            }
        }
    }

    fun interface ListenerFactory {
        @Throws(IOException::class)
        fun start(address: InetSocketAddress, service: VibrisControlService): Listener
    }

    interface Listener {
        fun port(): Int

        @Throws(Exception::class)
        fun stopAdmission()

        @Throws(Exception::class)
        fun awaitTermination()
    }

    class Failure internal constructor(
        private val codeValue: ErrorCode,
        message: String?,
        cause: Throwable,
    ) : Exception(message, cause) {
        fun code(): ErrorCode = codeValue
    }

    private class GrpcListener(private val server: Server) : Listener {
        override fun port(): Int = server.port

        override fun stopAdmission() {
            server.shutdown()
        }

        @Throws(InterruptedException::class)
        override fun awaitTermination() {
            if (server.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                return
            }
            server.shutdownNow()
            server.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        private val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(5)

        @JvmStatic
        @Throws(Failure::class)
        fun start(config: Config?, runtime: VibrisRuntimeAdapter?): VibrisBootstrap {
            return start(config, runtime, ListenerFactory(::startListener))
        }

        @JvmStatic
        @Throws(Failure::class)
        fun start(
            config: Config?,
            runtime: VibrisRuntimeAdapter?,
            listenerFactory: ListenerFactory?,
        ): VibrisBootstrap {
            val actualConfig = Objects.requireNonNull(config, "config")!!
            val actualRuntime = Objects.requireNonNull(runtime, "runtime")!!
            val actualFactory = Objects.requireNonNull(listenerFactory, "listenerFactory")!!
            val pendingSources = PendingSourceRoot(actualConfig.pendingShadersRoot)
            val link = FixedShaderLink(actualConfig.pendingShadersRoot, actualConfig.shaderpackRoot)
            var service: VibrisControlService? = null
            try {
                OwnedPathIdentity.createDirectoriesSafely(actualConfig.pendingShadersRoot)
                OwnedPathIdentity.createDirectoriesSafely(actualConfig.shaderpackRoot)
                OwnedPathIdentity.createDirectoriesSafely(actualConfig.artifactRoot)
                link.prepare()
                pendingSources.prepare()
                service = VibrisControlService(
                    actualConfig.pendingShadersRoot,
                    actualConfig.artifactRoot,
                    actualRuntime,
                    link,
                )
                val address = InetSocketAddress("127.0.0.1", actualConfig.port)
                val listener = actualFactory.start(address, service)
                return VibrisBootstrap(service, pendingSources, listener)
            } catch (exception: ShaderLink.Failure) {
                val failure = Failure(ErrorCode.SYMLINK_SWITCH_FAILED, exception.message, exception)
                closeFailedStart(service, actualRuntime, failure)
                throw failure
            } catch (exception: PendingSourceRoot.Failure) {
                val failure = Failure(
                    ErrorCode.SERVER_NOT_READY,
                    "Vibris startup cleanup failed.",
                    exception,
                )
                closeFailedStart(service, actualRuntime, failure)
                throw failure
            } catch (exception: IOException) {
                val failure = Failure(
                    ErrorCode.SERVER_NOT_READY,
                    "Vibris startup cleanup failed.",
                    exception,
                )
                closeFailedStart(service, actualRuntime, failure)
                throw failure
            } catch (exception: RuntimeException) {
                val failure = Failure(ErrorCode.SERVER_NOT_READY, "Vibris startup failed.", exception)
                closeFailedStart(service, actualRuntime, failure)
                throw failure
            }
        }

        @Throws(IOException::class)
        private fun startListener(address: InetSocketAddress, service: VibrisControlService): Listener {
            val server = NettyServerBuilder.forAddress(address).addService(service).build().start()
            return GrpcListener(server)
        }

        private fun closeFailedStart(
            service: VibrisControlService?,
            runtime: VibrisRuntimeAdapter,
            failure: Failure,
        ) {
            try {
                if (service != null) {
                    service.close()
                } else {
                    runtime.close()
                }
            } catch (closeFailure: Exception) {
                failure.addSuppressed(closeFailure)
            }
        }

        private fun append(current: Failure?, message: String, exception: Exception): Failure {
            if (current == null) {
                return Failure(ErrorCode.INTERNAL_ERROR, message, exception)
            }
            current.addSuppressed(exception)
            return current
        }
    }
}