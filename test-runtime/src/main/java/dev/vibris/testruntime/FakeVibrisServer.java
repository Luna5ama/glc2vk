package dev.vibris.testruntime;

import dev.vibris.core.VibrisControlService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FakeVibrisServer implements AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final Server server;
    private final VibrisControlService service;
    private final FakeRuntimeProbe probe;
    private final AtomicBoolean closed = new AtomicBoolean();

    private FakeVibrisServer(Server server, VibrisControlService service, FakeRuntimeProbe probe) {
        this.server = server;
        this.service = service;
        this.probe = probe;
    }

    public static FakeVibrisServer start(int port, Path workRoot) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Port must be between 0 and 65535");
        Path root = workRoot.toAbsolutePath().normalize();
        return start(port, root.resolve("pending-shaders"), root.resolve("artifacts"));
    }

    public static FakeVibrisServer start(int port, Path pendingShadersRoot, Path artifactRoot) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Port must be between 0 and 65535");
        pendingShadersRoot = pendingShadersRoot.toAbsolutePath().normalize();
        artifactRoot = artifactRoot.toAbsolutePath().normalize();
        Files.createDirectories(pendingShadersRoot);
        Files.createDirectories(artifactRoot);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        FakeRuntimeAdapter runtime = new FakeRuntimeAdapter();
        VibrisControlService service = new VibrisControlService(pendingShadersRoot, artifactRoot, runtime);
        try {
            Server server = NettyServerBuilder.forAddress(new InetSocketAddress(loopback, port))
                .addService(service)
                .build()
                .start();
            return new FakeVibrisServer(server, service, new FakeRuntimeProbe(runtime, service.probe()));
        } catch (IOException | RuntimeException exception) {
            service.close();
            throw exception;
        }
    }

    public int port() {
        return server.getPort();
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    public boolean isTerminated() {
        return server.isTerminated();
    }

    public FakeRuntimeProbe phaseThreeProbe() {
        return probe;
    }

    @Override
    public void close() throws InterruptedException {
        if (!closed.compareAndSet(false, true)) return;
        server.shutdown();
        if (!server.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            server.shutdownNow();
            server.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        service.close();
    }
}