package dev.vibris.testruntime;

import java.nio.file.Path;

public final class FakeVibrisServerMain {
    private FakeVibrisServerMain() {
    }

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        FakeVibrisServer server = FakeVibrisServer.start(
            options.port(), options.pendingRoot(), options.artifactRoot());
        Thread shutdownHook = new Thread(() -> close(server), "Vibris Test Runtime Shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            server.awaitTermination();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
            }
            server.close();
        }
    }

    private static void close(FakeVibrisServer server) {
        try {
            server.close();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record Options(int port, Path workRoot, Path pendingRoot, Path artifactRoot) {
        private static Options parse(String[] arguments) {
            Integer port = null;
            Path workRoot = null;
            Path pendingRoot = null;
            Path artifactRoot = null;
            for (int index = 0; index < arguments.length; index++) {
                switch (arguments[index]) {
                    case "--port" -> port = Integer.parseInt(requireValue(arguments, ++index, "--port"));
                    case "--work-root" -> workRoot = Path.of(requireValue(arguments, ++index, "--work-root"));
                    case "--pending-root" -> pendingRoot = Path.of(requireValue(arguments, ++index, "--pending-root"));
                    case "--artifact-root" ->
                            artifactRoot = Path.of(requireValue(arguments, ++index, "--artifact-root"));
                    default -> throw new IllegalArgumentException("Unknown argument: " + arguments[index]);
                }
            }
            if (port == null) throw new IllegalArgumentException("Missing required argument: --port");
            if (workRoot == null) throw new IllegalArgumentException("Missing required argument: --work-root");
            Path root = workRoot.toAbsolutePath().normalize();
            Path resolvedPending = pendingRoot == null ? root.resolve("pending-shaders") : pendingRoot;
            Path resolvedArtifacts = artifactRoot == null ? root.resolve("artifacts") : artifactRoot;
            return new Options(port, root, resolvedPending, resolvedArtifacts);
        }

        private static String requireValue(String[] arguments, int index, String option) {
            if (index >= arguments.length) throw new IllegalArgumentException("Missing value for " + option);
            return arguments[index];
        }
    }
}