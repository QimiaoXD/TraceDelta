package io.tracedelta.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.target.EvalTarget;
import io.tracedelta.target.TargetConfigSnapshot;
import io.tracedelta.target.TargetRegistry;
import io.tracedelta.target.TargetType;
import io.tracedelta.trace.TraceSpanWriter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class EvalRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(EvalRunExecutor.class);
    private static final long PROGRESS_FLUSH_MIN_INTERVAL_MS = 200L;

    private final EvalRunMapper runMapper;
    private final EvalResultMapper resultMapper;
    private final EvalAttemptMapper attemptMapper;
    private final TargetRegistry targetRegistry;
    private final ConcurrencyLimiter concurrencyLimiter;
    private final EvalCaseWorker caseWorker;
    private final ExecutorService virtualThreadExecutor;
    private final ObjectMapper objectMapper;
    private final TraceSpanWriter traceSpanWriter;

    private final ConcurrentHashMap<Long, ActiveRunEntry> activeRuns = new ConcurrentHashMap<>();

    public EvalRunExecutor(
            EvalRunMapper runMapper,
            EvalResultMapper resultMapper,
            EvalAttemptMapper attemptMapper,
            TargetRegistry targetRegistry,
            ConcurrencyLimiter concurrencyLimiter,
            EvalCaseWorker caseWorker,
            @Qualifier("virtualThreadExecutor") ExecutorService virtualThreadExecutor,
            ObjectMapper objectMapper,
            TraceSpanWriter traceSpanWriter) {
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.attemptMapper = attemptMapper;
        this.targetRegistry = targetRegistry;
        this.concurrencyLimiter = concurrencyLimiter;
        this.caseWorker = caseWorker;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.objectMapper = objectMapper;
        this.traceSpanWriter = traceSpanWriter;
    }

    public CompletableFuture<Void> submitRun(Long runId) {
        ActiveRunEntry existing = activeRuns.get(runId);
        if (existing != null) {
            return existing.future();
        }

        EvalRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            return CompletableFuture.completedFuture(null);
        }

        RunBudgetTracker budgetTracker = buildBudgetTrackerFromDb(run);
        Semaphore runSem = concurrencyLimiter.getOrCreateRunSemaphore(runId, run.getConcurrency());
        RunControlHandle handle = new RunControlHandle(
                runId,
                Boolean.TRUE.equals(run.getCancelRequested()),
                Boolean.TRUE.equals(run.getRateLimitDegraded()),
                budgetTracker,
                runSem);

        CompletableFuture<Void> future = new CompletableFuture<>();
        ActiveRunEntry entry = new ActiveRunEntry(handle, future);
        ActiveRunEntry race = activeRuns.putIfAbsent(runId, entry);
        if (race != null) {
            return race.future();
        }

        virtualThreadExecutor.submit(() -> {
            Throwable engineError = null;
            try {
                executeRunLoop(runId, handle);
            } catch (Throwable t) {
                engineError = t;
                log.error("Engine failure while executing eval run {}", runId, t);
            } finally {
                try {
                    finalizeRun(runId, handle, engineError);
                } catch (Throwable finErr) {
                    log.error("Failed to finalize eval run {}", runId, finErr);
                } finally {
                    activeRuns.remove(runId);
                    concurrencyLimiter.removeRunSemaphore(runId);
                    future.complete(null);
                }
            }
        });

        return future;
    }

    public void requestCancel(Long runId) {
        ActiveRunEntry entry = activeRuns.get(runId);
        if (entry != null) {
            entry.handle().requestCancel();
        }
    }

    private void executeRunLoop(Long runId, RunControlHandle handle) throws Exception {
        EvalRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!EvalRunStatus.RUNNING.name().equals(run.getStatus())) {
            run.setStatus(EvalRunStatus.RUNNING.name());
            if (run.getStartedAt() == null) {
                run.setStartedAt(now);
            }
            run.setUpdatedAt(now);
            runMapper.updateById(run);
        }

        if (handle.isStopDispatching()) {
            return;
        }

        TargetConfigSnapshot targetConfig = objectMapper.readValue(
                run.getTargetConfigSnapshotJson(), TargetConfigSnapshot.class);
        EvalTarget target = targetRegistry.get(TargetType.valueOf(run.getTargetType()));

        List<EvalResultEntity> pendingCases = resultMapper.selectList(
                new LambdaQueryWrapper<EvalResultEntity>()
                        .eq(EvalResultEntity::getRunId, runId)
                        .eq(EvalResultEntity::getStatus, EvalResultStatus.PENDING.name())
                        .orderByAsc(EvalResultEntity::getId));

        if (pendingCases.isEmpty()) {
            return;
        }

        AtomicLong lastFlushMs = new AtomicLong(0L);
        List<CompletableFuture<Void>> workerFutures = new ArrayList<>(pendingCases.size());

        for (EvalResultEntity pending : pendingCases) {
            if (handle.isStopDispatching()) {
                break;
            }
            CompletableFuture<Void> cf = CompletableFuture.runAsync(() -> {
                if (handle.isStopDispatching()) {
                    return;
                }
                if (!concurrencyLimiter.acquireRunPermit(handle.runSemaphore(), handle::isStopDispatching)) {
                    return;
                }
                try {
                    boolean worked = caseWorker.executeCase(run, target, targetConfig, pending, handle);
                    if (worked) {
                        flushProgressThrottled(runId, handle, lastFlushMs);
                    }
                } finally {
                    concurrencyLimiter.releaseRunPermit(handle.runSemaphore());
                }
            }, virtualThreadExecutor);
            workerFutures.add(cf);
        }

        CompletableFuture.allOf(workerFutures.toArray(CompletableFuture[]::new)).join();
    }

    private void flushProgressThrottled(Long runId, RunControlHandle handle, AtomicLong lastFlushMs) {
        long nowMs = System.currentTimeMillis();
        long prev = lastFlushMs.get();
        if (nowMs - prev < PROGRESS_FLUSH_MIN_INTERVAL_MS || !lastFlushMs.compareAndSet(prev, nowMs)) {
            return;
        }
        try {
            EvalRunEntity run = runMapper.selectById(runId);
            if (run == null || EvalRunStatus.valueOf(run.getStatus()).isTerminal()) {
                return;
            }
            AggregateStats stats = computeAggregates(runId);
            applyAggregates(run, stats, handle);
            run.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(run);
        } catch (Exception ex) {
            log.debug("Progress flush skipped for run {}: {}", runId, ex.getMessage());
        }
    }

    void finalizeRun(Long runId, RunControlHandle handle, Throwable engineError) {
        // Best-effort bounded flush so immediate UI polls observe persisted TraceSpans and accurate drop flags
        traceSpanWriter.flushAll(Duration.ofSeconds(2));

        EvalRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }
        int writerDropped = traceSpanWriter.droppedCountForRun(runId);
        int existingDropped = run.getTraceDroppedCount() == null ? 0 : run.getTraceDroppedCount();
        int totalDropped = Math.max(existingDropped, writerDropped);
        run.setTraceDroppedCount(totalDropped);
        run.setTraceIncomplete(Boolean.TRUE.equals(run.getTraceIncomplete()) || totalDropped > 0);

        LocalDateTime now = LocalDateTime.now();
        boolean cancelled = Boolean.TRUE.equals(run.getCancelRequested()) || handle.isCancelRequested();
        boolean budgetExceeded = handle.isBudgetExceeded() || Boolean.TRUE.equals(run.getBudgetExceeded());
        String budgetReason = handle.budgetTracker().getExceededReason() != null
                ? handle.budgetTracker().getExceededReason()
                : run.getBudgetExceededReason();

        if (cancelled) {
            resultMapper.skipPendingForRun(runId, "CANCELLED", "Skipped because run was cancelled", now);
        } else if (budgetExceeded) {
            resultMapper.skipPendingForRun(
                    runId,
                    "BUDGET_EXCEEDED",
                    "Skipped due to run budget limit (" + budgetReason + ")",
                    now);
        } else if (engineError != null) {
            resultMapper.skipPendingForRun(runId, "ENGINE_ERROR", "Skipped due to engine error", now);
        }

        AggregateStats stats = computeAggregates(runId);
        applyAggregates(run, stats, handle);
        run.setCancelRequested(cancelled);
        run.setBudgetExceeded(budgetExceeded);
        run.setBudgetExceededReason(budgetReason);

        EvalRunStatus finalStatus;
        String statusReason;
        if (cancelled) {
            finalStatus = EvalRunStatus.CANCELLED;
            statusReason = "CANCELLED_BY_USER";
        } else if (engineError != null) {
            finalStatus = EvalRunStatus.FAILED;
            statusReason = "ENGINE_ERROR: " + engineError.getMessage();
        } else if (run.getTotalCases() > 0 && stats.failedCases == run.getTotalCases()) {
            finalStatus = EvalRunStatus.FAILED;
            statusReason = "ALL_CASES_FAILED";
        } else if (stats.failedCases > 0 || stats.skippedCases > 0) {
            finalStatus = EvalRunStatus.PARTIAL_FAILED;
            statusReason = budgetExceeded ? budgetReason : "PARTIAL_EXECUTION_FAILED";
        } else {
            finalStatus = EvalRunStatus.COMPLETED;
            statusReason = null;
        }

        run.setStatus(finalStatus.name());
        run.setStatusReason(truncateReason(statusReason));
        run.setFinishedAt(now);
        run.setUpdatedAt(now);
        runMapper.updateById(run);
    }

    private RunBudgetTracker buildBudgetTrackerFromDb(EvalRunEntity run) {
        List<EvalAttemptEntity> attempts = attemptMapper.selectList(
                new LambdaQueryWrapper<EvalAttemptEntity>().eq(EvalAttemptEntity::getRunId, run.getId()));
        BigDecimal initialCost = BigDecimal.ZERO;
        long initialTokens = 0L;
        for (EvalAttemptEntity a : attempts) {
            if (a.getEstimatedCostUsd() != null) {
                initialCost = initialCost.add(a.getEstimatedCostUsd());
            }
            initialTokens += (a.getInputTokens() == null ? 0L : a.getInputTokens())
                    + (a.getOutputTokens() == null ? 0L : a.getOutputTokens());
        }
        return new RunBudgetTracker(run.getMaxCostUsd(), run.getMaxTotalTokens(), initialCost, initialTokens);
    }

    private AggregateStats computeAggregates(Long runId) {
        List<EvalResultEntity> results = resultMapper.selectList(
                new LambdaQueryWrapper<EvalResultEntity>().eq(EvalResultEntity::getRunId, runId));
        List<EvalAttemptEntity> attempts = attemptMapper.selectList(
                new LambdaQueryWrapper<EvalAttemptEntity>().eq(EvalAttemptEntity::getRunId, runId));

        int succeeded = 0;
        int failed = 0;
        int skipped = 0;
        List<Long> succeededLatencies = new ArrayList<>();
        for (EvalResultEntity r : results) {
            if (EvalResultStatus.EXEC_SUCCEEDED.name().equals(r.getStatus())) {
                succeeded++;
                if (r.getLatencyMs() != null) {
                    succeededLatencies.add(r.getLatencyMs());
                }
            } else if (EvalResultStatus.EXEC_FAILED.name().equals(r.getStatus())) {
                failed++;
            } else if (EvalResultStatus.SKIPPED.name().equals(r.getStatus())) {
                skipped++;
            }
        }

        long totalIn = 0L;
        long totalOut = 0L;
        BigDecimal totalCost = BigDecimal.ZERO;
        int interruptedAttempts = 0;
        for (EvalAttemptEntity a : attempts) {
            if (a.getInputTokens() != null) {
                totalIn += a.getInputTokens();
            }
            if (a.getOutputTokens() != null) {
                totalOut += a.getOutputTokens();
            }
            if (a.getEstimatedCostUsd() != null) {
                totalCost = totalCost.add(a.getEstimatedCostUsd());
            }
            if (EvalAttemptStatus.INTERRUPTED_UNKNOWN.name().equals(a.getStatus())) {
                interruptedAttempts++;
            }
        }

        Long avgLatency = null;
        Long p95Latency = null;
        if (!succeededLatencies.isEmpty()) {
            Collections.sort(succeededLatencies);
            long sum = 0L;
            for (Long l : succeededLatencies) {
                sum += l;
            }
            avgLatency = Math.round((double) sum / succeededLatencies.size());
            int p95Idx = Math.max(0, (int) Math.ceil(0.95 * succeededLatencies.size()) - 1);
            p95Latency = succeededLatencies.get(p95Idx);
        }

        return new AggregateStats(
                succeeded + failed + skipped,
                succeeded,
                failed,
                skipped,
                totalIn,
                totalOut,
                totalCost,
                avgLatency,
                p95Latency,
                interruptedAttempts);
    }

    private void applyAggregates(EvalRunEntity run, AggregateStats stats, RunControlHandle handle) {
        run.setCompletedCases(stats.completedCases);
        run.setSucceededCases(stats.succeededCases);
        run.setFailedCases(stats.failedCases);
        run.setSkippedCases(stats.skippedCases);
        run.setTotalInputTokens(stats.totalInputTokens);
        run.setTotalOutputTokens(stats.totalOutputTokens);
        run.setTotalCostUsd(stats.totalCostUsd);
        run.setAvgLatencyMs(stats.avgLatencyMs);
        run.setP95LatencyMs(stats.p95LatencyMs);
        run.setInterruptedAttemptCount(stats.interruptedAttempts);
        if (handle.isRateLimitDegraded()) {
            run.setRateLimitDegraded(true);
        }
        if (handle.isBudgetExceeded()) {
            run.setBudgetExceeded(true);
            run.setBudgetExceededReason(handle.budgetTracker().getExceededReason());
        }
    }

    private static String truncateReason(String reason) {
        if (reason == null || reason.length() <= 250) {
            return reason;
        }
        return reason.substring(0, 250);
    }

    private record ActiveRunEntry(RunControlHandle handle, CompletableFuture<Void> future) {}

    private record AggregateStats(
            int completedCases,
            int succeededCases,
            int failedCases,
            int skippedCases,
            long totalInputTokens,
            long totalOutputTokens,
            BigDecimal totalCostUsd,
            Long avgLatencyMs,
            Long p95LatencyMs,
            int interruptedAttempts) {}
}
