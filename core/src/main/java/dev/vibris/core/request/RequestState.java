package dev.vibris.core.request;

public enum RequestState {
    ACCEPTED(false),
    RUNNING(false),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true);

    private final boolean terminal;

    RequestState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }
}