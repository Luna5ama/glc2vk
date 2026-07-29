package dev.vibris.api;

import java.util.List;
import java.util.Objects;

public record ReloadResult(boolean successful, List<Diagnostic> diagnostics) {
    public ReloadResult {
        diagnostics = List.copyOf(diagnostics);
    }

    public static ReloadResult success(List<Diagnostic> diagnostics) {
        return new ReloadResult(true, diagnostics);
    }

    public static ReloadResult failure(List<Diagnostic> diagnostics) {
        return new ReloadResult(false, diagnostics);
    }

    public record Diagnostic(Severity severity, String source, int line, String message) {
        public Diagnostic {
            severity = Objects.requireNonNull(severity, "severity");
            source = Objects.requireNonNull(source, "source");
            message = Objects.requireNonNull(message, "message");
            if (line < 0) throw new IllegalArgumentException("line must not be negative");
        }
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}