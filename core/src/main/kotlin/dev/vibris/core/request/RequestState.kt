package dev.vibris.core.request

enum class RequestState(private val terminalValue: Boolean) {
    ACCEPTED(false),
    RUNNING(false),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true);

    fun terminal(): Boolean = terminalValue
}