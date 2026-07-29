package dev.vibris.api;

import java.util.List;

public record ContextValidationResult(boolean valid, List<String> errors) {
    public ContextValidationResult {
        errors = List.copyOf(errors);
        if (valid && !errors.isEmpty()) throw new IllegalArgumentException("A valid context cannot have errors");
    }

    public static ContextValidationResult accepted() {
        return new ContextValidationResult(true, List.of());
    }

    public static ContextValidationResult invalid(String error) {
        return new ContextValidationResult(false, List.of(error));
    }
}