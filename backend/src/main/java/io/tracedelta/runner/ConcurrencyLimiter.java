package io.tracedelta.runner;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/**
 * Enforces Level-1 (global) and Level-2 (per-run) concurrency limits.
 * Workers MUST acquire run permit BEFORE global permit, and release in reverse order,
 * preventing queued workers of a low-concurrency run from starving global permits of other runs.
 */
@Component
public class ConcurrencyLimiter {

    private static final long POLL_TIMEOUT_MS = 50L;

    private final Semaphore globalSemaphore;
    private final int globalMaxConcurrency;
    private final ConcurrentHashMap<Long, Semaphore> runSemaphores = new ConcurrentHashMap<>();

    public ConcurrencyLimiter(RunnerProperties properties) {
        this.globalMaxConcurrency = Math.max(1, properties.getGlobalMaxConcurrency());
        this.globalSemaphore = new Semaphore(this.globalMaxConcurrency, true);
    }

    public int getGlobalMaxConcurrency() {
        return globalMaxConcurrency;
    }

    public Semaphore getOrCreateRunSemaphore(Long runId, int runConcurrency) {
        int bounded = Math.max(1, Math.min(runConcurrency, globalMaxConcurrency));
        return runSemaphores.computeIfAbsent(runId, id -> new Semaphore(bounded, true));
    }

    public void removeRunSemaphore(Long runId) {
        runSemaphores.remove(runId);
    }

    public boolean acquireRunPermit(Semaphore runSemaphore, BooleanSupplier cancelled) {
        return acquireInterruptibly(runSemaphore, cancelled);
    }

    public boolean acquireGlobalPermit(BooleanSupplier cancelled) {
        return acquireInterruptibly(globalSemaphore, cancelled);
    }

    public void releaseGlobalPermit() {
        globalSemaphore.release();
    }

    public void releaseRunPermit(Semaphore runSemaphore) {
        runSemaphore.release();
    }

    private boolean acquireInterruptibly(Semaphore semaphore, BooleanSupplier cancelled) {
        while (true) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return false;
            }
            try {
                if (semaphore.tryAcquire(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
