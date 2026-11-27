package io.tracedelta.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EvalRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(EvalRecoveryService.class);
    private static final String INTERRUPTED_ATTEMPT_MSG =
            "Process interrupted before attempt completion; upstream call may have been billed (At-Least-Once)";
    private static final String INTERRUPTED_EXHAUSTED_MSG =
            "Max attempts exhausted after process interruption (INTERRUPTED_UNKNOWN)";

    private final EvalRunMapper runMapper;
    private final EvalResultMapper resultMapper;
    private final EvalAttemptMapper attemptMapper;
    private final EvalRunExecutor runExecutor;
    private final TransactionTemplate transactionTemplate;

    public EvalRecoveryService(
            EvalRunMapper runMapper,
            EvalResultMapper resultMapper,
            EvalAttemptMapper attemptMapper,
            EvalRunExecutor runExecutor,
            TransactionTemplate transactionTemplate) {
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.attemptMapper = attemptMapper;
        this.runExecutor = runExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    public RecoverySummary recoverInterruptedRuns() {
        RecoveryPlan plan = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            int interruptedAttempts = attemptMapper.markRunningInterrupted(now, INTERRUPTED_ATTEMPT_MSG);

            List<EvalRunEntity> activeRuns = runMapper.selectList(
                    new LambdaQueryWrapper<EvalRunEntity>()
                            .in(EvalRunEntity::getStatus, List.of(EvalRunStatus.PENDING.name(), EvalRunStatus.RUNNING.name()))
                            .orderByAsc(EvalRunEntity::getId));

            int requeued = 0;
            int exhausted = 0;
            List<Long> runIdsToResume = new ArrayList<>();

            for (EvalRunEntity run : activeRuns) {
                List<EvalResultEntity> runningResults = resultMapper.selectList(
                        new LambdaQueryWrapper<EvalResultEntity>()
                                .eq(EvalResultEntity::getRunId, run.getId())
                                .eq(EvalResultEntity::getStatus, EvalResultStatus.RUNNING.name()));

                for (EvalResultEntity result : runningResults) {
                    result.setHasInterruptedUnknown(true);
                    result.setUpdatedAt(now);
                    int attemptsSoFar = result.getAttemptCount() == null ? 0 : result.getAttemptCount();
                    if (Boolean.TRUE.equals(run.getCancelRequested())) {
                        result.setStatus(EvalResultStatus.EXEC_FAILED.name());
                        result.setErrorType("CANCELLED");
                        result.setErrorMessage("Cancelled before crash recovery");
                        result.setFinishedAt(now);
                        exhausted++;
                    } else if (attemptsSoFar < run.getMaxAttempts()) {
                        result.setStatus(EvalResultStatus.PENDING.name());
                        requeued++;
                    } else {
                        result.setStatus(EvalResultStatus.EXEC_FAILED.name());
                        result.setErrorType("INTERRUPTED_UNKNOWN");
                        result.setErrorMessage(INTERRUPTED_EXHAUSTED_MSG);
                        result.setFinishedAt(now);
                        exhausted++;
                    }
                    resultMapper.updateById(result);
                }

                runIdsToResume.add(run.getId());
            }

            return new RecoveryPlan(interruptedAttempts, requeued, exhausted, runIdsToResume);
        });

        if (plan == null) {
            return new RecoverySummary(0, 0, 0, 0);
        }

        for (Long runId : plan.runIdsToResume()) {
            runExecutor.submitRun(runId);
        }

        RecoverySummary summary = new RecoverySummary(
                plan.interruptedAttempts(),
                plan.requeued(),
                plan.exhausted(),
                plan.runIdsToResume().size());

        if (summary.interruptedAttemptsMarked() > 0 || summary.runsResumed() > 0) {
            log.warn(
                    "Eval recovery completed: interruptedAttempts={}, requeued={}, exhausted={}, runsResumed={}",
                    summary.interruptedAttemptsMarked(),
                    summary.resultsRequeued(),
                    summary.resultsExhausted(),
                    summary.runsResumed());
        }
        return summary;
    }

    public record RecoverySummary(
            int interruptedAttemptsMarked,
            int resultsRequeued,
            int resultsExhausted,
            int runsResumed) {}

    private record RecoveryPlan(
            int interruptedAttempts,
            int requeued,
            int exhausted,
            List<Long> runIdsToResume) {}
}
