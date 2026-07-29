package dev.vibris.api;

import java.util.Objects;

public record ContextApplyResult(boolean successful, SceneContext context, String message) {
    public ContextApplyResult {
        context = Objects.requireNonNull(context, "context");
        message = Objects.requireNonNull(message, "message");
    }

    public static ContextApplyResult success(SceneContext context) {
        return new ContextApplyResult(true, context, "");
    }

    public static ContextApplyResult failure(SceneContext context, String message) {
        return new ContextApplyResult(false, context, message);
    }
}