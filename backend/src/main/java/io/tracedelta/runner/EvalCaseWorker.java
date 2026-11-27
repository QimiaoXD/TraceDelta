package io.tracedelta.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.provider.llm.LlmErrorType;
import io.tracedelta.provider.llm.LlmResponse;
import io.tracedelta.runner.ratelimit.RateLimiter;
import io.tracedelta.target.CaseSnapshot;
import io.tracedelta.target.EvalTarget;
import io.tracedelta.target.TargetConfigSnapshot;
import io.tracedelta.target.TargetExecutionContext;
import io.tracedelta.target.TargetExecutionResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EvalCaseWorker {

    private static final Logger log = LoggerFactory.getLogger(EvalCaseWorker.class);
    private static final int MAX_ERROR_MESSAGE_LEN = 980;

    private final EvalResultMapper resultMapper;
    private final EvalAttemptMapper attemptMapper;
    private final ConcurrencyLimiter concurrencyLimiter;
    private final RateLimiter rateLimiter;
    private final RetryPolicy retryPolicy;
    private final ObjectMapper objectMapper;

    public EvalCaseWorker(
            EvalResultMapper resultMapper,
            EvalAttemptMapper attemptMapper,
            ConcurrencyLimiter concurrencyLimiter,
            RateLimiter rateLimiter,
            RetryPolicy retryPolicy,
            ObjectMapper objectMapper) {
        this.resultMapper = resultMapper;
        this.attemptMapper = attemptMapper;
        this.concurrencyLimiter = concurrencyLimiter;
        this.rateLimiter = rateLimiter;
        this.retryPolicy = retryPolicy;
        this.objectMapper = objectMapper;
    }

    public boolean executeCase(
            EvalRunEntity run,
            EvalTarget target,
            TargetConfigSnapshot targetConfig,
            EvalResultEntity pendingResult,
            RunControlHandle handle) {
        if (handle.isStopDispatching()) {
            return false;
        }

        LocalDateTime claimTime = LocalDateTime.now();
        int claimed = resultMapper.claimPending(pendingResult.getId(), run.getMaxAttempts(), claimTime);
        if (claimed == 0) {
            return false;
        }

        EvalResultEntity result = resultMapper.selectById(pendingResult.getId());
        if (result == null) {
            return false;
        }

        CaseSnapshot caseSnapshot;
        try {
            caseSnapshot = objectMapper.readValue(result.getCaseSnapshotJson(), CaseSnapshot.class);
        } catch (Exception ex) {
            markResultFailed(result, "SNAPSHOT_CORRUPT", "Failed to parse frozen case snapshot: " + ex.getMessage());
            return true;
        }

        String rateLimitKey = target.rateLimitKey(targetConfig);
        Duration requestTimeout = Duration.ofMillis(run.getRequestTimeoutMs());
        int attemptNo = result.getAttemptCount();
        boolean firstAttemptExecuted = false;

        while (attemptNo <= run.getMaxAttempts()) {
            if (!concurrencyLimiter.acquireGlobalPermit(handle::isCancelRequested)) {
                markInterruptedByCancel(result, firstAttemptExecuted);
                return true;
            }

            LlmResponse response;
            EvalAttemptEntity attempt = null;
            try {
                boolean gotToken = rateLimiter.acquireBlocking(
                        rateLimitKey,
                        run.getRateLimitPerSec(),
                        run.getRateLimitBurst(),
                        handle::isCancelRequested,
                        handle.rateLimitDegradedFlag());
                if (!gotToken) {
                    markInterruptedByCancel(result, firstAttemptExecuted);
                    return true;
                }

                attempt = new EvalAttemptEntity();
                attempt.setRunId(run.getId());
                attempt.setEvalResultId(result.getId());
                attempt.setCaseKey(result.getCaseKey());
                attempt.setAttemptNo(attemptNo);
                attempt.setStatus(EvalAttemptStatus.RUNNING.name());
                attempt.setInputTokens(0);
                attempt.setOutputTokens(0);
                attempt.setEstimatedCostUsd(BigDecimal.ZERO);
                attempt.setStartedAt(LocalDateTime.now());
                attemptMapper.insert(attempt);
                firstAttemptExecuted = true;

                TargetExecutionResult execResult = target.execute(
                        new TargetExecutionContext(targetConfig, caseSnapshot, attemptNo, requestTimeout));
                response = execResult.response();
            } catch (Exception ex) {
                log.warn("Unexpected target error: runId={} caseKey={} attempt={}", run.getId(), result.getCaseKey(), attemptNo, ex);
                response = LlmResponse.failure(0L, LlmErrorType.HTTP_ERROR, "Target execution error: " + ex.getMessage());
            } finally {
                concurrencyLimiter.releaseGlobalPermit();
            }

            finalizeAttempt(attempt, response);
            handle.budgetTracker().recordUsage(
                    response.estimatedCost(),
                    response.inputTokens(),
                    response.outputTokens());

            if (response.errorType() == LlmErrorType.NONE) {
                markResultSucceeded(result, response);
                return true;
            }

            boolean retryable = retryPolicy.isRetryable(response.errorType(), response.errorMessage());
            boolean hasRemainingAttempts = attemptNo < run.getMaxAttempts();
            if (!retryable || !hasRemainingAttempts || handle.isStopDispatching()) {
                markResultFailed(result, response.errorType().name(), response.errorMessage());
                return true;
            }

            long delayMs = retryPolicy.computeDelayMs(
                    attemptNo, run.getRetryBaseDelayMs(), run.getRetryMaxDelayMs());
            boolean slept = retryPolicy.sleepBackoff(delayMs, handle::isStopDispatching);
            if (!slept) {
                markResultFailed(
                        result,
                        handle.isCancelRequested() ? "CANCELLED" : "BUDGET_EXCEEDED",
                        "Retry aborted before next attempt");
                return true;
            }

            int incremented = resultMapper.incrementAttemptCount(
                    result.getId(), run.getMaxAttempts(), LocalDateTime.now());
            if (incremented == 0) {
                markResultFailed(result, response.errorType().name(), response.errorMessage());
                return true;
            }
            attemptNo++;
            result.setAttemptCount(attemptNo);
        }

        markResultFailed(result, "MAX_ATTEMPTS_EXHAUSTED", "Max attempts exhausted");
        return true;
    }

    private void finalizeAttempt(EvalAttemptEntity attempt, LlmResponse response) {
        if (attempt == null) {
            return;
        }
        attempt.setLatencyMs(response.latencyMs());
        attempt.setTtftMs(response.ttftMs());
        attempt.setInputTokens(response.inputTokens());
        attempt.setOutputTokens(response.outputTokens());
        attempt.setEstimatedCostUsd(response.estimatedCost() == null ? BigDecimal.ZERO : response.estimatedCost());
        if (response.errorType() == LlmErrorType.NONE) {
            attempt.setStatus(EvalAttemptStatus.SUCCEEDED.name());
            attempt.setErrorType(null);
            attempt.setErrorMessage(null);
        } else if (response.errorType() == LlmErrorType.TIMEOUT) {
            attempt.setStatus(EvalAttemptStatus.TIMEOUT.name());
            attempt.setErrorType(response.errorType().name());
            attempt.setErrorMessage(truncate(response.errorMessage()));
        } else {
            attempt.setStatus(EvalAttemptStatus.FAILED.name());
            attempt.setErrorType(response.errorType().name());
            attempt.setErrorMessage(truncate(response.errorMessage()));
        }
        attempt.setFinishedAt(LocalDateTime.now());
        attemptMapper.updateById(attempt);
    }

    private void markResultSucceeded(EvalResultEntity result, LlmResponse response) {
        LocalDateTime now = LocalDateTime.now();
        result.setStatus(EvalResultStatus.EXEC_SUCCEEDED.name());
        result.setOutputText(response.content());
        result.setLatencyMs(response.latencyMs());
        result.setTtftMs(response.ttftMs());
        result.setInputTokens(response.inputTokens());
        result.setOutputTokens(response.outputTokens());
        result.setEstimatedCostUsd(response.estimatedCost() == null ? BigDecimal.ZERO : response.estimatedCost());
        result.setErrorType(null);
        result.setErrorMessage(null);
        result.setFinishedAt(now);
        result.setUpdatedAt(now);
        resultMapper.updateById(result);
    }

    private void markResultFailed(EvalResultEntity result, String errorType, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        result.setStatus(EvalResultStatus.EXEC_FAILED.name());
        result.setErrorType(errorType);
        result.setErrorMessage(truncate(errorMessage));
        result.setFinishedAt(now);
        result.setUpdatedAt(now);
        resultMapper.updateById(result);
    }

    private void markInterruptedByCancel(EvalResultEntity result, boolean firstAttemptExecuted) {
        LocalDateTime now = LocalDateTime.now();
        if (!firstAttemptExecuted) {
            result.setStatus(EvalResultStatus.SKIPPED.name());
            result.setAttemptCount(Math.max(0, result.getAttemptCount() - 1));
            result.setErrorType("CANCELLED");
            result.setErrorMessage("Cancelled before target invocation");
        } else {
            result.setStatus(EvalResultStatus.EXEC_FAILED.name());
            result.setErrorType("CANCELLED");
            result.setErrorMessage("Cancelled before retry");
        }
        result.setFinishedAt(now);
        result.setUpdatedAt(now);
        resultMapper.updateById(result);
    }

    private static String truncate(String msg) {
        if (msg == null || msg.length() <= MAX_ERROR_MESSAGE_LEN) {
            return msg;
        }
        return msg.substring(0, MAX_ERROR_MESSAGE_LEN) + "...";
    }
}
