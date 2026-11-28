package io.tracedelta.runner;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record EvalResultView(
        Long id,
        Long runId,
        String caseKey,
        String caseHash,
        String status,
        String scoreConclusion,
        Integer attemptCount,
        Boolean hasInterruptedUnknown,
        String outputText,
        Long latencyMs,
        Long ttftMs,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal estimatedCostUsd,
        String errorType,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    public static EvalResultView fromEntity(EvalResultEntity e) {
        return new EvalResultView(
                e.getId(),
                e.getRunId(),
                e.getCaseKey(),
                e.getCaseHash(),
                e.getStatus(),
                e.getScoreConclusion(),
                e.getAttemptCount(),
                e.getHasInterruptedUnknown(),
                e.getOutputText(),
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
