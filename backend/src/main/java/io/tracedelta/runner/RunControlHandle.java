package io.tracedelta.runner;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RunControlHandle {

    private final Long runId;
    private final AtomicBoolean cancelRequested;
    private final AtomicBoolean rateLimitDegraded;
    private final RunBudgetTracker budgetTracker;
    private final Semaphore runSemaphore;

    public RunControlHandle(
            Long runId,
            boolean initiallyCancelRequested,
            boolean initiallyDegraded,
            RunBudgetTracker budgetTracker,
            Semaphore runSemaphore) {
        this.runId = runId;
        this.cancelRequested = new AtomicBoolean(initiallyCancelRequested);
        this.rateLimitDegraded = new AtomicBoolean(initiallyDegraded);
        this.budgetTracker = budgetTracker;
        this.runSemaphore = runSemaphore;
    }

    public Long runId() {
        return runId;
    }

    public boolean isCancelRequested() {
        return cancelRequested.get();
    }

    public void requestCancel() {
        cancelRequested.set(true);
    }

    public boolean isBudgetExceeded() {
        return budgetTracker.isExceeded();
    }

    public boolean isStopDispatching() {
        return cancelRequested.get() || budgetTracker.isExceeded();
    }

    public AtomicBoolean rateLimitDegradedFlag() {
        return rateLimitDegraded;
    }

    public boolean isRateLimitDegraded() {
        return rateLimitDegraded.get();
    }

    public RunBudgetTracker budgetTracker() {
        return budgetTracker;
    }

    public Semaphore runSemaphore() {
        return runSemaphore;
    }
}
