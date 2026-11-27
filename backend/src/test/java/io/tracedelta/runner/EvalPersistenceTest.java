package io.tracedelta.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class EvalPersistenceTest {

    @Autowired
    private EvalRunMapper runMapper;

    @Autowired
    private EvalResultMapper resultMapper;

    @Autowired
    private EvalAttemptMapper attemptMapper;

    @Autowired
    private RunnerProperties runnerProperties;

    private EvalRunEntity insertRun(String name) {
        LocalDateTime now = LocalDateTime.now();
        EvalRunEntity run = new EvalRunEntity();
        run.setName(name);
        run.setRunType(EvalRunType.STANDARD.name());
        run.setStatus(EvalRunStatus.RUNNING.name());
        run.setDatasetId(1L);
        run.setDatasetVersionId(1L);
        run.setDatasetNameSnapshot("ds");
        run.setDatasetVersionNo(1);
        run.setTargetType("PROMPT");
        run.setProviderId(1L);
        run.setProviderNameSnapshot("mock-provider");
        run.setModelSnapshot("mock-good");
        run.setTargetConfigSnapshotJson("{}");
        run.setConcurrency(4);
        run.setMaxAttempts(3);
        run.setRequestTimeoutMs(30000L);
        run.setRateLimitPerSec(20.0);
        run.setRateLimitBurst(20);
        run.setRetryBaseDelayMs(100L);
        run.setRetryMaxDelayMs(1000L);
        run.setTotalCases(3);
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
        run.setCancelRequested(false);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
        return run;
    }

    private EvalResultEntity insertResult(Long runId, String caseKey, EvalResultStatus status, int attemptCount) {
        LocalDateTime now = LocalDateTime.now();
        EvalResultEntity result = new EvalResultEntity();
        result.setRunId(runId);
        result.setCaseKey(caseKey);
        result.setCaseHash("a".repeat(64));
        result.setCaseSnapshotJson("{\"caseKey\":\"" + caseKey + "\"}");
        result.setStatus(status.name());
        result.setScoreConclusion(EvalScoreConclusion.UNSCORED.name());
        result.setAttemptCount(attemptCount);
        result.setHasInterruptedUnknown(false);
        result.setInputTokens(0);
        result.setOutputTokens(0);
        result.setEstimatedCostUsd(BigDecimal.ZERO);
        result.setVersion(0);
        result.setCreatedAt(now);
        result.setUpdatedAt(now);
        resultMapper.insert(result);
        return result;
    }

    @Test
    void runnerPropertiesLoadsTestDefaults() {
        assertThat(runnerProperties.getGlobalMaxConcurrency()).isGreaterThan(0);
        assertThat(runnerProperties.isRedisRateLimitEnabled()).isFalse();
        assertThat(runnerProperties.isRecoveryOnStartup()).isFalse();
    }

    @Test
    void atomicClaimPendingSucceedsOnceAndRejectsSecondClaim() {
        EvalRunEntity run = insertRun("claim-test");
        EvalResultEntity result = insertResult(run.getId(), "k1", EvalResultStatus.PENDING, 0);

        LocalDateTime now = LocalDateTime.now();
        int first = resultMapper.claimPending(result.getId(), 3, now);
        int second = resultMapper.claimPending(result.getId(), 3, now);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);

        EvalResultEntity reloaded = resultMapper.selectById(result.getId());
        assertThat(reloaded.getStatus()).isEqualTo(EvalResultStatus.RUNNING.name());
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getVersion()).isEqualTo(1);
    }

    @Test
    void uniqueConstraintsRejectDuplicateRunCaseKeyAndDuplicateAttemptNo() {
        EvalRunEntity run = insertRun("unique-test");
        EvalResultEntity r1 = insertResult(run.getId(), "dup-k", EvalResultStatus.PENDING, 0);

        assertThatThrownBy(() -> insertResult(run.getId(), "dup-k", EvalResultStatus.PENDING, 0))
                .isInstanceOf(DataIntegrityViolationException.class);

        LocalDateTime now = LocalDateTime.now();
        EvalAttemptEntity a1 = new EvalAttemptEntity();
        a1.setRunId(run.getId());
        a1.setEvalResultId(r1.getId());
        a1.setCaseKey("dup-k");
        a1.setAttemptNo(1);
        a1.setStatus(EvalAttemptStatus.RUNNING.name());
        a1.setInputTokens(0);
        a1.setOutputTokens(0);
        a1.setEstimatedCostUsd(BigDecimal.ZERO);
        a1.setStartedAt(now);
        attemptMapper.insert(a1);

        EvalAttemptEntity a2 = new EvalAttemptEntity();
        a2.setRunId(run.getId());
        a2.setEvalResultId(r1.getId());
        a2.setCaseKey("dup-k");
        a2.setAttemptNo(1);
        a2.setStatus(EvalAttemptStatus.RUNNING.name());
        a2.setInputTokens(0);
        a2.setOutputTokens(0);
        a2.setEstimatedCostUsd(BigDecimal.ZERO);
        a2.setStartedAt(now);

        assertThatThrownBy(() -> attemptMapper.insert(a2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void skipPendingForRunOnlyAffectsPendingRows() {
        EvalRunEntity run = insertRun("skip-test");
        EvalResultEntity pending = insertResult(run.getId(), "p1", EvalResultStatus.PENDING, 0);
        EvalResultEntity running = insertResult(run.getId(), "r1", EvalResultStatus.RUNNING, 1);
        EvalResultEntity done = insertResult(run.getId(), "d1", EvalResultStatus.EXEC_SUCCEEDED, 1);

        int skipped = resultMapper.skipPendingForRun(
                run.getId(), "BUDGET_EXCEEDED", "Run budget limit reached", LocalDateTime.now());

        assertThat(skipped).isEqualTo(1);
        assertThat(resultMapper.selectById(pending.getId()).getStatus()).isEqualTo(EvalResultStatus.SKIPPED.name());
        assertThat(resultMapper.selectById(pending.getId()).getErrorType()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(resultMapper.selectById(running.getId()).getStatus()).isEqualTo(EvalResultStatus.RUNNING.name());
        assertThat(resultMapper.selectById(done.getId()).getStatus()).isEqualTo(EvalResultStatus.EXEC_SUCCEEDED.name());
    }

    @Test
    void markRunningAttemptsInterruptedTransitionsOnlyRunningAttempts() {
        EvalRunEntity run = insertRun("interrupt-test");
        EvalResultEntity r1 = insertResult(run.getId(), "i1", EvalResultStatus.RUNNING, 1);
        LocalDateTime now = LocalDateTime.now();

        EvalAttemptEntity runningAttempt = new EvalAttemptEntity();
        runningAttempt.setRunId(run.getId());
        runningAttempt.setEvalResultId(r1.getId());
        runningAttempt.setCaseKey("i1");
        runningAttempt.setAttemptNo(1);
        runningAttempt.setStatus(EvalAttemptStatus.RUNNING.name());
        runningAttempt.setInputTokens(0);
        runningAttempt.setOutputTokens(0);
        runningAttempt.setEstimatedCostUsd(BigDecimal.ZERO);
        runningAttempt.setStartedAt(now);
        attemptMapper.insert(runningAttempt);

        EvalAttemptEntity succeededAttempt = new EvalAttemptEntity();
        succeededAttempt.setRunId(run.getId());
        succeededAttempt.setEvalResultId(r1.getId());
        succeededAttempt.setCaseKey("i1");
        succeededAttempt.setAttemptNo(2);
        succeededAttempt.setStatus(EvalAttemptStatus.SUCCEEDED.name());
        succeededAttempt.setInputTokens(10);
        succeededAttempt.setOutputTokens(5);
        succeededAttempt.setEstimatedCostUsd(BigDecimal.ZERO);
        succeededAttempt.setStartedAt(now);
        succeededAttempt.setFinishedAt(now);
        attemptMapper.insert(succeededAttempt);

        int updated = attemptMapper.markRunningInterrupted(now, "Interrupted before completion");
        assertThat(updated).isGreaterThanOrEqualTo(1);

        EvalAttemptEntity reloadedRunning = attemptMapper.selectById(runningAttempt.getId());
        assertThat(reloadedRunning.getStatus()).isEqualTo(EvalAttemptStatus.INTERRUPTED_UNKNOWN.name());
        assertThat(reloadedRunning.getErrorType()).isEqualTo("INTERRUPTED_UNKNOWN");
        assertThat(reloadedRunning.getFinishedAt()).isNotNull();

        EvalAttemptEntity reloadedSucceeded = attemptMapper.selectById(succeededAttempt.getId());
        assertThat(reloadedSucceeded.getStatus()).isEqualTo(EvalAttemptStatus.SUCCEEDED.name());
    }
}
