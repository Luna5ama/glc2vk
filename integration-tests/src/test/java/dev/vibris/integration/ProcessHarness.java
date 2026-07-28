package dev.vibris.integration;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class ProcessHarness {
    private ProcessHarness() {
    }

    static Result run(Duration timeout, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyTree(process);
                throw new AssertionError("Process timed out after " + timeout + ": " + String.join(" ", command));
            }
            return new Result(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        } finally {
            if (process.isAlive()) destroyTree(process);
        }
    }

    private static void destroyTree(Process process) throws Exception {
        Process killer = new ProcessBuilder(
            "taskkill.exe", "/PID", Long.toString(process.pid()), "/T", "/F"
        ).redirectErrorStream(true).start();
        killer.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        killer.waitFor(5, TimeUnit.SECONDS);
        process.waitFor(5, TimeUnit.SECONDS);
        if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
    }

    record Result(int exitCode, String output) {
    }
}