package io.tracedelta.runner;

import java.math.BigDecimal;

/**
 * Thread-safe Run-level budget circuit breaker.
 * Tracks cumulative cost (USD) and tokens across all attempts (including pre-crash usage loaded on recovery).
 */
public class RunBudgetTracker {

    public static final String REASON_COST = "COST_BUDGET_EXCEEDED";
    public static final String REASON_TOKENS = "TOKEN_BUDGET_EXCEEDED";

    private final BigDecimal maxCostUsd;
    private final Long maxTotalTokens;

    private BigDecimal usedCostUsd;
    private long usedTotalTokens;
    private volatile boolean exceeded;
    private volatile String exceededReason;

    public RunBudgetTracker(
            BigDecimal maxCostUsd,
            Long maxTotalTokens,
            BigDecimal initialCostUsd,
            long initialTotalTokens) {
        this.maxCostUsd = maxCostUsd;
        this.maxTotalTokens = maxTotalTokens;
        this.usedCostUsd = initialCostUsd == null ? BigDecimal.ZERO : initialCostUsd;
        this.usedTotalTokens = Math.max(0L, initialTotalTokens);
        evaluateLocked();
    }

    public synchronized void recordUsage(BigDecimal costUsd, int inputTokens, int outputTokens) {
        if (costUsd != null && costUsd.signum() > 0) {
            usedCostUsd = usedCostUsd.add(costUsd);
        }
        long deltaTokens = Math.max(0L, (long) inputTokens) + Math.max(0L, (long) outputTokens);
        usedTotalTokens += deltaTokens;
        evaluateLocked();
    }

    private void evaluateLocked() {
        if (exceeded) {
            return;
        }
        if (maxCostUsd != null && usedCostUsd.compareTo(maxCostUsd) >= 0) {
            exceeded = true;
            exceededReason = REASON_COST;
        } else if (maxTotalTokens != null && usedTotalTokens >= maxTotalTokens) {
            exceeded = true;
            exceededReason = REASON_TOKENS;
        }
    }

    public boolean isExceeded() {
        return exceeded;
    }

    public String getExceededReason() {
        return exceededReason;
    }

    public synchronized BigDecimal getUsedCostUsd() {
        return usedCostUsd;
    }

    public synchronized long getUsedTotalTokens() {
        return usedTotalTokens;
    }
}
