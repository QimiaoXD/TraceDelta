package io.tracedelta.runner;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record EvalAttemptView(
        Long id,
        Long runId,
        Long evalResultId,
        String caseKey,
        Integer attemptNo,
        String status,
        Long latencyMs,
        Long ttftMs,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal estimatedCostUsd,
        String errorType,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    public static EvalAttemptView fromEntity(EvalAttemptEntity e) {
        return new EvalAttemptView(
                e.getId(),
                e.getRunId(),
                e.getEvalResultId(),
                e.getCaseKey(),
                e.getAttemptNo(),
                e.getStatus(),
                e.getLatencyMs(),
                e.getTtftMs(),
                e.getInputTokens(),
                e.getOutputTokens(),
                e.getEstimatedCostUsd(),
                e.getErrorType(),
                e.getErrorMessage(),
                e.getStartedAt(),
                e.getFinishedAt());
    }
}
