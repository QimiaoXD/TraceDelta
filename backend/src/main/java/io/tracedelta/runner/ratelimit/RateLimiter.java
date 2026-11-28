package io.tracedelta.runner.ratelimit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public interface RateLimiter {

    RateLimitResult tryAcquire(String key, double refillPerSec, int burst);

    default boolean acquireBlocking(
            String key,
            double refillPerSec,
            int burst,
            BooleanSupplier cancelled,
            AtomicBoolean degradedFlag) {
        while (true) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return false;
            }
            RateLimitResult result = tryAcquire(key, refillPerSec, burst);
            if (result.degraded() && degradedFlag != null) {
                degradedFlag.set(true);
            }
            if (result.allowed()) {
                return true;
            }
            long sleepMs = Math.max(1L, Math.min(result.waitMs(), 100L));
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
