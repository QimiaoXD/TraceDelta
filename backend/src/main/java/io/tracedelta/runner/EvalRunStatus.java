package io.tracedelta.runner;

public enum EvalRunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    PARTIAL_FAILED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL_FAILED || this == FAILED || this == CANCELLED;
    }
}
