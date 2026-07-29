package dev.vibris.api

class CaptureResourceNotFoundException(logicalName: String) :
    RuntimeException("Capture resource was not found: $logicalName")