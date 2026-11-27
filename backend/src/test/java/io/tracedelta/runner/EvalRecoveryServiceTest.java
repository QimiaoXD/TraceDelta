package io.tracedelta.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.provider.ProviderType;
import io.tracedelta.target.CaseSnapshot;
import io.tracedelta.target.TargetConfigSnapshot;
import io.tracedelta.target.TargetType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class EvalRecoveryServiceTest {

    @Autowired
    private EvalRecoveryService recoveryService;

    @Autowired
    private EvalRunService evalRunService;

    @Autowired
    private EvalRunMapper runMapper;

    @Autowired
    private EvalResultMapper resultMapper;

    @Autowired
    private EvalAttemptMapper attemptMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private EvalRunEntity seedCrashedRun(String name, int totalCases, Long maxTotalTokens, boolean cancelRequested)
            throws Exception {
        LocalDateTime now = LocalDateTime.now().minusMinutes(2);
        TargetConfigSnapshot snapshot = new TargetConfigSnapshot(
                TargetType.PROMPT,
                501L,
                "mock-recovery",
                ProviderType.MOCK,
                null,
                "mock-good",
                null,
                "{\"profile\":\"mock-good\",\"seed\":11,\"latencyMs\":0}",
                null,
                "{{question}}");

        EvalRunEntity run = new EvalRunEntity();
        run.setName(name);
        run.setRunType(EvalRunType.STANDARD.name());
        run.setStatus(EvalRunStatus.RUNNING.name());
        run.setDatasetId(1L);
        run.setDatasetVersionId(1L);
        run.setDatasetNameSnapshot("recovery-ds");
        run.setDatasetVersionNo(1);
        run.setTargetType(TargetType.PROMPT.name());
        run.setProviderId(501L);
        run.setProviderNameSnapshot("mock-recovery");
        run.setModelSnapshot("mock-good");
        run.setTargetConfigSnapshotJson(objectMapper.writeValueAsString(snapshot));
        run.setConcurrency(4);
        run.setMaxAttempts(3);
        run.setRequestTimeoutMs(10_000L);
        run.setRateLimitPerSec(1000.0);
        run.setRateLimitBurst(1000);
        run.setMaxTotalTokens(maxTotalTokens);
        run.setRetryBaseDelayMs(20L);
        run.setRetryMaxDelayMs(100L);
        run.setTotalCases(totalCases);
        run.setCompletedCases(0);
        run.setSucceededCases(0);
        run.setFailedCases(0);
        run.setSkippedCases(0);
        run.setTotalInputTokens(0L);
        run.setTotalOutputTokens(0L);
        run.setTotalCostUsd(BigDecimal.ZERO);
        run.setBudgetExceeded(false);
        run.setInterruptedAttemptCount(0);
        run.setRateLimitDegraded(false);
        run.setCancelRequested(cancelRequested);
        run.setCreatedAt(now);
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
        return run;
    }

    private EvalResultEntity seedResult(Long runId, String caseKey, EvalResultStatus status, int attemptCount)
            throws Exception {
        LocalDateTime now = LocalDateTime.now().minusMinutes(2);
        CaseSnapshot cs = new CaseSnapshot(
                caseKey, "b".repeat(64), "{\"question\":\"Question " + caseKey + "\"}", null, "Ans-" + caseKey);
        EvalResultEntity r = new EvalResultEntity();
        r.setRunId(runId);
        r.setCaseKey(caseKey);
        r.setCaseHash(cs.caseHash());
        r.setCaseSnapshotJson(objectMapper.writeValueAsString(cs));
        r.setStatus(status.name());
        r.setScoreConclusion(EvalScoreConclusion.UNSCORED.name());
        r.setAttemptCount(attemptCount);
        r.setHasInterruptedUnknown(false);
        r.setInputTokens(0);
        r.setOutputTokens(0);
        r.setEstimatedCostUsd(BigDecimal.ZERO);
        r.setVersion(0);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        if (status == EvalResultStatus.EXEC_SUCCEEDED) {
            r.setOutputText("Ans-" + caseKey);
            r.setLatencyMs(5L);
            r.setFinishedAt(now);
        }
        resultMapper.insert(r);
        return r;
    }

    private void seedAttempt(
            Long runId, Long resultId, String caseKey, int attemptNo, EvalAttemptStatus status, int inTok, int outTok) {
        LocalDateTime now = LocalDateTime.now().minusMinutes(2);
        EvalAttemptEntity a = new EvalAttemptEntity();
        a.setRunId(runId);
        a.setEvalResultId(resultId);
        a.setCaseKey(caseKey);
        a.setAttemptNo(attemptNo);
        a.setStatus(status.name());
        a.setInputTokens(inTok);
        a.setOutputTokens(outTok);
        a.setEstimatedCostUsd(BigDecimal.ZERO);
        a.setStartedAt(now);
        if (status != EvalAttemptStatus.RUNNING) {
            a.setFinishedAt(now);
        }
        attemptMapper.insert(a);
    }

    private EvalRunDetailView awaitTerminal(Long runId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            EvalRunDetailView detail = evalRunService.getRunDetail(runId);
            if (EvalRunStatus.valueOf(detail.status()).isTerminal()) {
                return detail;
            }
            Thread.sleep(25);
        }
        return evalRunService.getRunDetail(runId);
    }

    @Test
    void recoversInterruptedRunWithoutReexecutingSucceededCases() throws Exception {
        EvalRunEntity run = seedCrashedRun("crash-recovery-mix", 4, null, false);

        // Case 1: already EXEC_SUCCEEDED -> MUST NEVER re-run
        EvalResultEntity c1 = seedResult(run.getId(), "rec-1", EvalResultStatus.EXEC_SUCCEEDED, 1);
        seedAttempt(run.getId(), c1.getId(), "rec-1", 1, EvalAttemptStatus.SUCCEEDED, 10, 5);

        // Case 2: RUNNING at attempt 1 -> attempt 1 marked INTERRUPTED_UNKNOWN, requeued & succeeds on attempt 2
        EvalResultEntity c2 = seedResult(run.getId(), "rec-2", EvalResultStatus.RUNNING, 1);
        seedAttempt(run.getId(), c2.getId(), "rec-2", 1, EvalAttemptStatus.RUNNING, 0, 0);

        // Case 3: RUNNING at attempt 3 (maxAttempts=3) -> attempt 3 marked INTERRUPTED_UNKNOWN, directly EXEC_FAILED
        EvalResultEntity c3 = seedResult(run.getId(), "rec-3", EvalResultStatus.RUNNING, 3);
        seedAttempt(run.getId(), c3.getId(), "rec-3", 1, EvalAttemptStatus.FAILED, 0, 0);
        seedAttempt(run.getId(), c3.getId(), "rec-3", 2, EvalAttemptStatus.TIMEOUT, 0, 0);
        seedAttempt(run.getId(), c3.getId(), "rec-3", 3, EvalAttemptStatus.RUNNING, 0, 0);

        // Case 4: PENDING -> runs normally as attempt 1 -> EXEC_SUCCEEDED
        EvalResultEntity c4 = seedResult(run.getId(), "rec-4", EvalResultStatus.PENDING, 0);

        EvalRecoveryService.RecoverySummary summary = recoveryService.recoverInterruptedRuns();
        assertThat(summary.interruptedAttemptsMarked()).isGreaterThanOrEqualTo(2);
        assertThat(summary.resultsRequeued()).isGreaterThanOrEqualTo(1);
        assertThat(summary.resultsExhausted()).isGreaterThanOrEqualTo(1);
        assertThat(summary.runsResumed()).isGreaterThanOrEqualTo(1);

        EvalRunDetailView finished = awaitTerminal(run.getId(), Duration.ofSeconds(10));
        assertThat(finished.status()).isEqualTo(EvalRunStatus.PARTIAL_FAILED.name());
        assertThat(finished.succeededCases()).isEqualTo(3);
        assertThat(finished.failedCases()).isEqualTo(1);
        assertThat(finished.interruptedAttemptCount()).isEqualTo(2);

        // Verify Case 1 never re-ran
        EvalResultEntity c1After = resultMapper.selectById(c1.getId());
        assertThat(c1After.getAttemptCount()).isEqualTo(1);
        assertThat(evalRunService.listAttempts(run.getId(), c1.getId())).hasSize(1);

        // Verify Case 2 retried as attempt 2 with hasInterruptedUnknown=true
        EvalResultEntity c2After = resultMapper.selectById(c2.getId());
        assertThat(c2After.getStatus()).isEqualTo(EvalResultStatus.EXEC_SUCCEEDED.name());
        assertThat(c2After.getAttemptCount()).isEqualTo(2);
        assertThat(c2After.getHasInterruptedUnknown()).isTrue();
        List<EvalAttemptView> c2Attempts = evalRunService.listAttempts(run.getId(), c2.getId());
        assertThat(c2Attempts).hasSize(2);
        assertThat(c2Attempts.get(0).status()).isEqualTo(EvalAttemptStatus.INTERRUPTED_UNKNOWN.name());
        assertThat(c2Attempts.get(1).status()).isEqualTo(EvalAttemptStatus.SUCCEEDED.name());

        // Verify Case 3 exhausted directly with no 4th attempt
        EvalResultEntity c3After = resultMapper.selectById(c3.getId());
        assertThat(c3After.getStatus()).isEqualTo(EvalResultStatus.EXEC_FAILED.name());
        assertThat(c3After.getAttemptCount()).isEqualTo(3);
        assertThat(c3After.getHasInterruptedUnknown()).isTrue();
        assertThat(c3After.getErrorType()).isEqualTo("INTERRUPTED_UNKNOWN");
        assertThat(evalRunService.listAttempts(run.getId(), c3.getId())).hasSize(3);

        // Verify Case 4 completed cleanly
        EvalResultEntity c4After = resultMapper.selectById(c4.getId());
        assertThat(c4After.getStatus()).isEqualTo(EvalResultStatus.EXEC_SUCCEEDED.name());
        assertThat(c4After.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void recoveryPreservesPreCrashSpentBudgetAndSkipsRemainingPendingCases() throws Exception {
        EvalRunEntity run = seedCrashedRun("crash-recovery-budget", 3, 50L, false);

        // Already spent 55 tokens before crash (>= maxTotalTokens 50)
        EvalResultEntity c1 = seedResult(run.getId(), "b-1", EvalResultStatus.EXEC_SUCCEEDED, 1);
        seedAttempt(run.getId(), c1.getId(), "b-1", 1, EvalAttemptStatus.SUCCEEDED, 30, 25);

        EvalResultEntity c2 = seedResult(run.getId(), "b-2", EvalResultStatus.PENDING, 0);
        EvalResultEntity c3 = seedResult(run.getId(), "b-3", EvalResultStatus.PENDING, 0);

        recoveryService.recoverInterruptedRuns();

        EvalRunDetailView finished = awaitTerminal(run.getId(), Duration.ofSeconds(10));
        assertThat(finished.status()).isEqualTo(EvalRunStatus.PARTIAL_FAILED.name());
        assertThat(finished.budgetExceeded()).isTrue();
        assertThat(finished.budgetExceededReason()).isEqualTo(RunBudgetTracker.REASON_TOKENS);
        assertThat(finished.succeededCases()).isEqualTo(1);
        assertThat(finished.skippedCases()).isEqualTo(2);
        assertThat(resultMapper.selectById(c2.getId()).getAttemptCount()).isEqualTo(0);
        assertThat(resultMapper.selectById(c3.getId()).getAttemptCount()).isEqualTo(0);
    }

    @Test
    void recoveryFinalizesCancelledBeforeCrashRunWithoutDispatchingPendingCases() throws Exception {
        EvalRunEntity run = seedCrashedRun("crash-recovery-cancelled", 2, null, true);
        EvalResultEntity c1 = seedResult(run.getId(), "can-1", EvalResultStatus.PENDING, 0);
        EvalResultEntity c2 = seedResult(run.getId(), "can-2", EvalResultStatus.PENDING, 0);

        recoveryService.recoverInterruptedRuns();

        EvalRunDetailView finished = awaitTerminal(run.getId(), Duration.ofSeconds(10));
        assertThat(finished.status()).isEqualTo(EvalRunStatus.CANCELLED.name());
        assertThat(finished.skippedCases()).isEqualTo(2);
        assertThat(resultMapper.selectById(c1.getId()).getAttemptCount()).isEqualTo(0);
        assertThat(resultMapper.selectById(c2.getId()).getAttemptCount()).isEqualTo(0);
    }
}
