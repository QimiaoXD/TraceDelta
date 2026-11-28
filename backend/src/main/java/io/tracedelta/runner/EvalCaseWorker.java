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
import io.tracedelta.trace.SpanDraft;
import io.tracedelta.trace.SpanKind;
import io.tracedelta.trace.SpanStatus;
import io.tracedelta.trace.TraceIdGenerator;
import io.tracedelta.trace.TraceSpanEntity;
import io.tracedelta.trace.TraceSpanWriter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final TraceSpanWriter traceSpanWriter;

    public EvalCaseWorker(
            EvalResultMapper resultMapper,
            EvalAttemptMapper attemptMapper,
            ConcurrencyLimiter concurrencyLimiter,
            RateLimiter rateLimiter,
            RetryPolicy retryPolicy,
            ObjectMapper objectMapper,
            TraceSpanWriter traceSpanWriter) {
        this.resultMapper = resultMapper;
        this.attemptMapper = attemptMapper;
        this.concurrencyLimiter = concurrencyLimiter;
        this.rateLimiter = rateLimiter;
        this.retryPolicy = retryPolicy;
        this.objectMapper = objectMapper;
        this.traceSpanWriter = traceSpanWriter;
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
        String traceId = ensureTraceId(result);
        String caseRootSpanId = TraceIdGenerator.newSpanId();
        long caseStartMs = System.currentTimeMillis();

        CaseSnapshot caseSnapshot;
        try {
            caseSnapshot = objectMapper.readValue(result.getCaseSnapshotJson(), CaseSnapshot.class);
        } catch (Exception ex) {
            markResultFailed(result, "SNAPSHOT_CORRUPT", "Failed to parse frozen case snapshot: " + ex.getMessage());
            emitCaseRootSpan(traceId, caseRootSpanId, run, result, caseStartMs, false, "SNAPSHOT_CORRUPT", ex.getMessage());
            return true;
        }

        String rateLimitKey = target.rateLimitKey(targetConfig);
        Duration requestTimeout = Duration.ofMillis(run.getRequestTimeoutMs());
        int attemptNo = result.getAttemptCount();
        boolean firstAttemptExecuted = false;

        while (attemptNo <= run.getMaxAttempts()) {
            if (!concurrencyLimiter.acquireGlobalPermit(handle::isCancelRequested)) {
                markInterruptedByCancel(result, firstAttemptExecuted);
                emitCaseRootSpan(traceId, caseRootSpanId, run, result, caseStartMs, false, "CANCELLED", "Cancelled waiting for global permit");
                return true;
            }

            LlmResponse response;
            List<SpanDraft> childSpans = List.of();
            EvalAttemptEntity attempt = null;
            String attemptSpanId = TraceIdGenerator.newSpanId();
            long attemptStartMs = System.currentTimeMillis();
            try {
                boolean gotToken = rateLimiter.acquireBlocking(
                        rateLimitKey,
                        run.getRateLimitPerSec(),
                        run.getRateLimitBurst(),
                        handle::isCancelRequested,
                        handle.rateLimitDegradedFlag());
                if (!gotToken) {
                    markInterruptedByCancel(result, firstAttemptExecuted);
                    emitCaseRootSpan(traceId, caseRootSpanId, run, result, caseStartMs, false, "CANCELLED", "Cancelled waiting for rate limit token");
                    return true;
                }

                attemptStartMs = System.currentTimeMillis();
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
                childSpans = execResult.spans();
            } catch (Exception ex) {
                log.warn("Unexpected target error: runId={} caseKey={} attempt={}", run.getId(), result.getCaseKey(), attemptNo, ex);
                response = LlmResponse.failure(0L, LlmErrorType.HTTP_ERROR, "Target execution error: " + ex.getMessage());
            } finally {
                concurrencyLimiter.releaseGlobalPermit();
            }

            long attemptEndMs = Math.max(attemptStartMs, System.currentTimeMillis());
            finalizeAttempt(attempt, response);
            emitAttemptAndChildSpans(traceId, caseRootSpanId, attemptSpanId, run, result, attemptNo, attemptStartMs, attemptEndMs, response, childSpans);

            handle.budgetTracker().recordUsage(
                    response.estimatedCost(),
                    response.inputTokens(),
                    response.outputTokens());

            if (response.errorType() == LlmErrorType.NONE) {
                markResultSucceeded(result, response);
                emitCaseRootSpan(traceId, caseRootSpanId, run, result, caseStartMs, true, null, null);
                return true;
            }

            boolean retryable = retryPolicy.isRetryable(response.errorType(), response.errorMessage());
            boolean hasRemainingAttempts = attemptNo < run.getMaxAttempts();
            if (!retryable || !hasRemainingAttempts || handle.isStopDispatching()) {
                markResultFailed(result, response.errorType().name(), response.errorMessage());
                emitCaseRootSpan(traceId, caseRootSpanId, run, result, caseStartMs, false, response.errorType().name(), response.errorMessage());
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
                emitCaseRootSpan(traceId, caseRootSpanId, run, result, caseStartMs, false,
                        handle.isCancelRequested() ? "CANCELLED" : "BUDGET_EXCEEDED",
                        "Retry aborted before next attempt");
                return true;
            }

            int incremented = resultMapper.incrementAttemptCount(
                    result.getId(), run.getMaxAttempts(), LocalDateTime.now());
            if (incremented == 0) {
                markResultFailed(result, response.errorType().name(), response.errorMessage());
                emitCaseRootSpan(traceId, caseRootSpanId, run, result, caseStartMs, false, response.errorType().name(), response.errorMessage());
                return true;
            }
            attemptNo++;
            result.setAttemptCount(attemptNo);
        }

        markResultFailed(result, "MAX_ATTEMPTS_EXHAUSTED", "Max attempts exhausted");
        emitCaseRootSpan(traceId, caseRootSpanId, run, result, caseStartMs, false, "MAX_ATTEMPTS_EXHAUSTED", "Max attempts exhausted");
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

    private String ensureTraceId(EvalResultEntity result) {
        if (result.getTraceId() != null && !result.getTraceId().isBlank()) {
            return result.getTraceId();
        }
        String generated = TraceIdGenerator.newTraceId();
        result.setTraceId(generated);
        resultMapper.updateById(result);
        return generated;
    }

    private void emitAttemptAndChildSpans(
            String traceId,
            String caseRootSpanId,
            String attemptSpanId,
            EvalRunEntity run,
            EvalResultEntity result,
            int attemptNo,
            long attemptStartMs,
            long attemptEndMs,
            LlmResponse response,
            List<SpanDraft> childSpans) {
        boolean ok = response.errorType() == LlmErrorType.NONE;
        TraceSpanEntity attemptSpan = new TraceSpanEntity();
        attemptSpan.setTraceId(traceId);
        attemptSpan.setSpanId(attemptSpanId);
        attemptSpan.setParentSpanId(caseRootSpanId);
        attemptSpan.setRunId(run.getId());
        attemptSpan.setEvalResultId(result.getId());
        attemptSpan.setCaseKey(result.getCaseKey());
        attemptSpan.setAttemptNo(attemptNo);
        attemptSpan.setSpanName("eval.attempt #" + attemptNo);
        attemptSpan.setSpanKind(SpanKind.ATTEMPT.name());
        attemptSpan.setStatus(ok ? SpanStatus.OK.name() : SpanStatus.ERROR.name());
        attemptSpan.setStartTimeMs(attemptStartMs);
        attemptSpan.setEndTimeMs(attemptEndMs);
        attemptSpan.setDurationMs(Math.max(0L, attemptEndMs - attemptStartMs));
        attemptSpan.setTtftMs(response.ttftMs());
        attemptSpan.setInputTokens(response.inputTokens());
        attemptSpan.setOutputTokens(response.outputTokens());
        attemptSpan.setEstimatedCostUsd(response.estimatedCost() == null ? BigDecimal.ZERO : response.estimatedCost());
        attemptSpan.setErrorType(ok ? null : response.errorType().name());
        attemptSpan.setErrorMessage(ok ? null : truncate(response.errorMessage()));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("tracedelta.attempt_no", attemptNo);
        attrs.put("tracedelta.target_type", run.getTargetType());
        attemptSpan.setAttributesJson(writeJsonQuietly(attrs));
        attemptSpan.setCreatedAt(LocalDateTime.now());
        traceSpanWriter.submit(attemptSpan);

        for (SpanDraft draft : childSpans) {
            TraceSpanEntity child = new TraceSpanEntity();
            child.setTraceId(traceId);
            child.setSpanId(TraceIdGenerator.newSpanId());
            child.setParentSpanId(attemptSpanId);
            child.setRunId(run.getId());
            child.setEvalResultId(result.getId());
            child.setCaseKey(result.getCaseKey());
            child.setAttemptNo(attemptNo);
            child.setSpanName(draft.spanName());
            child.setSpanKind(draft.spanKind().name());
            child.setStatus(draft.status().name());
            child.setStartTimeMs(draft.startTimeMs());
            child.setEndTimeMs(draft.endTimeMs());
            child.setDurationMs(Math.max(0L, draft.endTimeMs() - draft.startTimeMs()));
            child.setTtftMs(draft.ttftMs());
            child.setInputTokens(draft.inputTokens());
            child.setOutputTokens(draft.outputTokens());
            child.setEstimatedCostUsd(draft.estimatedCostUsd());
            child.setInputPayload(draft.inputPayload());
            child.setOutputPayload(draft.outputPayload());
            child.setAttributesJson(draft.attributes() == null ? null : writeJsonQuietly(draft.attributes()));
            child.setErrorType(draft.errorType());
            child.setErrorMessage(truncate(draft.errorMessage()));
            child.setCreatedAt(LocalDateTime.now());
            traceSpanWriter.submit(child);
        }
    }

    private void emitCaseRootSpan(
            String traceId,
            String caseRootSpanId,
            EvalRunEntity run,
            EvalResultEntity result,
            long caseStartMs,
            boolean succeeded,
            String errorType,
            String errorMessage) {
        long caseEndMs = Math.max(caseStartMs, System.currentTimeMillis());
        TraceSpanEntity root = new TraceSpanEntity();
        root.setTraceId(traceId);
        root.setSpanId(caseRootSpanId);
        root.setParentSpanId(null);
        root.setRunId(run.getId());
        root.setEvalResultId(result.getId());
        root.setCaseKey(result.getCaseKey());
        root.setAttemptNo(0);
        root.setSpanName("eval.case");
        root.setSpanKind(SpanKind.CASE_ROOT.name());
        root.setStatus(succeeded ? SpanStatus.OK.name() : SpanStatus.ERROR.name());
        root.setStartTimeMs(caseStartMs);
        root.setEndTimeMs(caseEndMs);
        root.setDurationMs(Math.max(0L, caseEndMs - caseStartMs));
        root.setInputTokens(result.getInputTokens());
        root.setOutputTokens(result.getOutputTokens());
        root.setEstimatedCostUsd(result.getEstimatedCostUsd());
        root.setOutputPayload(result.getOutputText());
        root.setErrorType(errorType);
        root.setErrorMessage(truncate(errorMessage));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("tracedelta.case_key", result.getCaseKey());
        attrs.put("tracedelta.case_hash", result.getCaseHash());
        attrs.put("tracedelta.target_type", run.getTargetType());
        attrs.put("tracedelta.attempt_count", result.getAttemptCount());
        root.setAttributesJson(writeJsonQuietly(attrs));
        root.setCreatedAt(LocalDateTime.now());
        traceSpanWriter.submit(root);
    }

    private String writeJsonQuietly(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String msg) {
        if (msg == null || msg.length() <= MAX_ERROR_MESSAGE_LEN) {
            return msg;
        }
        return msg.substring(0, MAX_ERROR_MESSAGE_LEN) + "...";
    }
}
