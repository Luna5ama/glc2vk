package dev.vibris.core;

import dev.vibris.api.VibrisRuntimeAdapter;
import dev.vibris.protocol.v1.ErrorCode;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VibrisBootstrap implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final VibrisControlService service;
    private final PendingSourceRoot pendingSources;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();

    private VibrisBootstrap(VibrisControlService service, PendingSourceRoot pendingSources, Listener listener) {
        this.service = service;
        this.pendingSources = pendingSources;
        this.listener = listener;
    }

    public static VibrisBootstrap start(Config config, VibrisRuntimeAdapter runtime) throws Failure {
        return start(config, runtime, VibrisBootstrap::startListener);
    }

    static VibrisBootstrap start(Config config, VibrisRuntimeAdapter runtime, ListenerFactory listenerFactory)
        throws Failure {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(listenerFactory, "listenerFactory");
        PendingSourceRoot pendingSources = new PendingSourceRoot(config.pendingShadersRoot);
        FixedShaderLink link = new FixedShaderLink(config.pendingShadersRoot, config.shaderpackRoot);
        VibrisControlService service = null;
        try {
            OwnedPathIdentity.createDirectoriesSafely(config.pendingShadersRoot);
            OwnedPathIdentity.createDirectoriesSafely(config.shaderpackRoot);
            OwnedPathIdentity.createDirectoriesSafely(config.artifactRoot);
            link.prepare();
            pendingSources.prepare();
            service = new VibrisControlService(
                config.pendingShadersRoot, config.artifactRoot, runtime, link);
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", config.port);
            Listener listener = listenerFactory.start(address, service);
            return new VibrisBootstrap(service, pendingSources, listener);
        } catch (ShaderLink.Failure exception) {
            Failure failure = new Failure(ErrorCode.SYMLINK_SWITCH_FAILED, exception.getMessage(), exception);
            closeFailedStart(service, runtime, failure);
            throw failure;
        } catch (PendingSourceRoot.Failure | IOException exception) {
            Failure failure = new Failure(ErrorCode.SERVER_NOT_READY, "Vibris startup cleanup failed.", exception);
            closeFailedStart(service, runtime, failure);
            throw failure;
        } catch (RuntimeException exception) {
            Failure failure = new Failure(ErrorCode.SERVER_NOT_READY, "Vibris startup failed.", exception);
            closeFailedStart(service, runtime, failure);
            throw failure;
        }
    }

    public int port() {
        return listener.port();
    }

    @Override
    public void close() throws Failure {
        if (!closed.compareAndSet(false, true)) return;
        Failure failure = null;
        try {
            listener.stopAdmission();
        } catch (Exception exception) {
            failure = new Failure(ErrorCode.INTERNAL_ERROR, "Failed to stop Vibris admission.", exception);
        }
        try {
            service.close();
        } catch (Exception exception) {
            failure = append(failure, "Failed to close the Vibris runtime.", exception);
        }
        try {
            listener.awaitTermination();
        } catch (Exception exception) {
            failure = append(failure, "Failed to finish Vibris listener shutdown.", exception);
        }
        try {
            pendingSources.clear();
        } catch (Exception exception) {
            failure = append(failure, "Failed to clear pending Vibris sources.", exception);
        }
        if (failure != null) throw failure;
    }

    private static Listener startListener(InetSocketAddress address, VibrisControlService service) throws IOException {
        Server server = NettyServerBuilder.forAddress(address).addService(service).build().start();
        return new GrpcListener(server);
    }

    private static void closeFailedStart(
        VibrisControlService service,
        VibrisRuntimeAdapter runtime,
        Failure failure
    ) {
        try {
            if (service != null) {
                service.close();
            } else {
                runtime.close();
            }
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static Failure append(Failure current, String message, Exception exception) {
        if (current == null) return new Failure(ErrorCode.INTERNAL_ERROR, message, exception);
        current.addSuppressed(exception);
        return current;
    }

    public record Config(int port, Path pendingShadersRoot, Path artifactRoot, Path shaderpackRoot) {
        public Config {
            if (port < 0 || port > 65_535) throw new IllegalArgumentException("port is outside 0..65535");
            pendingShadersRoot = normalize(pendingShadersRoot, "pendingShadersRoot");
            artifactRoot = normalize(artifactRoot, "artifactRoot");
            shaderpackRoot = normalize(shaderpackRoot, "shaderpackRoot");
        }

        private static Path normalize(Path path, String name) {
            return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
        }
    }

    @FunctionalInterface
    interface ListenerFactory {
        Listener start(InetSocketAddress address, VibrisControlService service) throws IOException;
    }

    interface Listener {
        int port();

        void stopAdmission() throws Exception;

        void awaitTermination() throws Exception;
    }

    private record GrpcListener(Server server) implements Listener {
        @Override
        public int port() {
            return server.getPort();
        }

        @Override
        public void stopAdmission() {
            server.shutdown();
        }

        @Override
        public void awaitTermination() throws InterruptedException {
            if (server.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) return;
            server.shutdownNow();
            server.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public static final class Failure extends Exception {
        private final ErrorCode code;

        Failure(ErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public ErrorCode code() {
            return code;
        }
    }
}