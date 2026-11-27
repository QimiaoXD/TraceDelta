package io.tracedelta.runner;

import io.tracedelta.provider.llm.LlmErrorType;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.LongUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {

    private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile("status\\s+(\\d{3})");
    private static final long SLEEP_SLICE_MS = 25L;

    private final LongUnaryOperator jitterGenerator;

    @Autowired
    public RetryPolicy() {
        this(bound -> bound <= 0 ? 0L : ThreadLocalRandom.current().nextLong(bound + 1));
    }

    public RetryPolicy(LongUnaryOperator jitterGenerator) {
        this.jitterGenerator = jitterGenerator;
    }

    public boolean isRetryable(LlmErrorType errorType, String errorMessage) {
        if (errorType == null || errorType == LlmErrorType.NONE || errorType == LlmErrorType.MALFORMED_RESPONSE) {
            return false;
        }
        if (errorType == LlmErrorType.RATE_LIMITED || errorType == LlmErrorType.TIMEOUT) {
            return true;
        }
        if (errorType == LlmErrorType.HTTP_ERROR) {
            if (errorMessage != null) {
                Matcher matcher = HTTP_STATUS_PATTERN.matcher(errorMessage);
                if (matcher.find()) {
                    int status = Integer.parseInt(matcher.group(1));
                    // 4xx client errors (other than 429 which is RATE_LIMITED) are not retryable
                    if (status >= 400 && status < 500 && status != 429) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    public long computeDelayMs(int attemptNo, long baseDelayMs, long maxDelayMs) {
        long safeBase = Math.max(0L, baseDelayMs);
        long safeMax = Math.max(safeBase, maxDelayMs);
        int shift = Math.max(0, Math.min(attemptNo - 1, 20));
        long expDelay = safeBase * (1L << shift);
        long capped = Math.min(safeMax, expDelay);
        long jitterBound = safeBase / 2L;
        long jitter = jitterBound > 0 ? Math.max(0L, jitterGenerator.applyAsLong(jitterBound)) : 0L;
        return capped + jitter;
    }

    public boolean sleepBackoff(long delayMs, BooleanSupplier cancelled) {
        if (delayMs <= 0L) {
            return !cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted();
        }
        long deadlineNanos = System.nanoTime() + delayMs * 1_000_000L;
        while (true) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return false;
            }
            long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0L) {
                return true;
            }
            long slice = Math.min(remainingMs, SLEEP_SLICE_MS);
            try {
                Thread.sleep(Math.max(1L, slice));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
