package io.tracedelta.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrencyLimiterTest {

    @Test
    void enforcesRunAndGlobalConcurrencyLimits() throws Exception {
        RunnerProperties props = new RunnerProperties();
        props.setGlobalMaxConcurrency(4);
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(props);

        Semaphore run1 = limiter.getOrCreateRunSemaphore(1L, 2);
        Semaphore run2 = limiter.getOrCreateRunSemaphore(2L, 3);

        AtomicInteger activeRun1 = new AtomicInteger();
        AtomicInteger maxRun1 = new AtomicInteger();
        AtomicInteger activeGlobal = new AtomicInteger();
        AtomicInteger maxGlobal = new AtomicInteger();

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                boolean isRun1 = (i % 2 == 0);
                Semaphore runSem = isRun1 ? run1 : run2;
                futures.add(exec.submit(() -> {
                    if (!limiter.acquireRunPermit(runSem, () -> false)) {
                        return;
                    }
                    try {
                        if (!limiter.acquireGlobalPermit(() -> false)) {
                            return;
                        }
                        try {
                            int g = activeGlobal.incrementAndGet();
                            maxGlobal.accumulateAndGet(g, Math::max);
                            if (isRun1) {
                                int r1 = activeRun1.incrementAndGet();
                                maxRun1.accumulateAndGet(r1, Math::max);
                            }
                            Thread.sleep(30);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            if (isRun1) {
                                activeRun1.decrementAndGet();
                            }
                            activeGlobal.decrementAndGet();
                            limiter.releaseGlobalPermit();
                        }
                    } finally {
                        limiter.releaseRunPermit(runSem);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        }

        assertThat(maxRun1.get()).isLessThanOrEqualTo(2);
        assertThat(maxGlobal.get()).isLessThanOrEqualTo(4);
        limiter.removeRunSemaphore(1L);
        limiter.removeRunSemaphore(2L);
    }

    @Test
    void runAQueuedWorkersDoNotStarveRunBGlobalPermits() throws Exception {
        RunnerProperties props = new RunnerProperties();
        props.setGlobalMaxConcurrency(3);
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(props);

        Semaphore runA = limiter.getOrCreateRunSemaphore(10L, 1);
        Semaphore runB = limiter.getOrCreateRunSemaphore(20L, 2);

        CountDownLatch runAHolding = new CountDownLatch(1);
        CountDownLatch releaseRunA = new CountDownLatch(1);
        CountDownLatch runBBothEntered = new CountDownLatch(2);

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            // Launch 8 workers for Run A (concurrency=1): 1 holds runA+global, 7 wait on runA (NOT global!)
            List<Future<?>> runAFutures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                runAFutures.add(exec.submit(() -> {
                    limiter.acquireRunPermit(runA, () -> false);
                    try {
                        limiter.acquireGlobalPermit(() -> false);
                        try {
                            runAHolding.countDown();
                            releaseRunA.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            limiter.releaseGlobalPermit();
                        }
                    } finally {
                        limiter.releaseRunPermit(runA);
                    }
                }));
            }

            assertThat(runAHolding.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50); // Let other 7 Run A workers queue on runA semaphore

            // Run B (concurrency=2) MUST be able to acquire 2 global permits concurrently while Run A is blocked!
            CountDownLatch releaseRunB = new CountDownLatch(1);
            List<Future<?>> runBFutures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runBFutures.add(exec.submit(() -> {
                    limiter.acquireRunPermit(runB, () -> false);
                    try {
                        limiter.acquireGlobalPermit(() -> false);
                        try {
                            runBBothEntered.countDown();
                            releaseRunB.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            limiter.releaseGlobalPermit();
                        }
                    } finally {
                        limiter.releaseRunPermit(runB);
                    }
                }));
            }

            assertThat(runBBothEntered.await(2, TimeUnit.SECONDS)).isTrue();
            releaseRunB.countDown();
            releaseRunA.countDown();
            for (Future<?> f : runBFutures) {
                f.get(5, TimeUnit.SECONDS);
            }
            for (Future<?> f : runAFutures) {
                f.get(5, TimeUnit.SECONDS);
            }
        }
    }
}
