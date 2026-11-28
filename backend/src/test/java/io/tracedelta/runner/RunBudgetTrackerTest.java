package io.tracedelta.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RunBudgetTrackerTest {

    @Test
    void tripsOnCostBudgetExceeded() {
        RunBudgetTracker tracker = new RunBudgetTracker(new BigDecimal("0.010000"), null, BigDecimal.ZERO, 0L);

        tracker.recordUsage(new BigDecimal("0.004000"), 10, 5);
        assertThat(tracker.isExceeded()).isFalse();
        assertThat(tracker.getExceededReason()).isNull();

        tracker.recordUsage(new BigDecimal("0.006000"), 10, 5);
        assertThat(tracker.isExceeded()).isTrue();
        assertThat(tracker.getExceededReason()).isEqualTo("COST_BUDGET_EXCEEDED");
        assertThat(tracker.getUsedCostUsd()).isEqualByComparingTo("0.010000");
        assertThat(tracker.getUsedTotalTokens()).isEqualTo(30L);
    }

    @Test
    void tripsOnTokenBudgetExceededIncludingInitialRestoredUsage() {
        RunBudgetTracker tracker = new RunBudgetTracker(null, 100L, BigDecimal.ZERO, 90L);
        assertThat(tracker.isExceeded()).isFalse();

        tracker.recordUsage(BigDecimal.ZERO, 8, 7);
        assertThat(tracker.isExceeded()).isTrue();
        assertThat(tracker.getExceededReason()).isEqualTo("TOKEN_BUDGET_EXCEEDED");
        assertThat(tracker.getUsedTotalTokens()).isEqualTo(105L);
    }

    @Test
    void restoresAlreadyExceededStateAtInitialization() {
        RunBudgetTracker tracker = new RunBudgetTracker(null, 50L, BigDecimal.ZERO, 55L);
        assertThat(tracker.isExceeded()).isTrue();
        assertThat(tracker.getExceededReason()).isEqualTo("TOKEN_BUDGET_EXCEEDED");
    }

    @Test
    void threadSafeUnderConcurrentVirtualThreads() throws Exception {
        RunBudgetTracker tracker = new RunBudgetTracker(new BigDecimal("1.00"), 1000L, BigDecimal.ZERO, 0L);

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                futures.add(exec.submit(() -> tracker.recordUsage(new BigDecimal("0.001"), 10, 10)));
            }
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        }

        assertThat(tracker.getUsedTotalTokens()).isEqualTo(640L);
        assertThat(tracker.getUsedCostUsd()).isEqualByComparingTo("0.032");
        assertThat(tracker.isExceeded()).isFalse();
    }
}
