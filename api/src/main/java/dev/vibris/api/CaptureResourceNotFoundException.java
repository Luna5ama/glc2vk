package dev.vibris.api;

public final class CaptureResourceNotFoundException extends RuntimeException {
    public CaptureResourceNotFoundException(String logicalName) {
        super("Capture resource was not found: " + logicalName);
    }
}