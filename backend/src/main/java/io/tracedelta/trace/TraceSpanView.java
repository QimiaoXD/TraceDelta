package io.tracedelta.trace;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TraceSpanView(
        Long id,
        String traceId,
        String spanId,
        String parentSpanId,
        Long runId,
        Long evalResultId,
        String caseKey,
        Integer attemptNo,
        String spanName,
        String spanKind,
        String status,
        Long startTimeMs,
        Long endTimeMs,
        Long durationMs,
        Long ttftMs,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal estimatedCostUsd,
        String inputPayload,
        String outputPayload,
        String attributesJson,
        String errorType,
        String errorMessage,
        LocalDateTime createdAt) {

    public static TraceSpanView fromEntity(TraceSpanEntity e) {
        return new TraceSpanView(
                e.getId(),
                e.getTraceId(),
                e.getSpanId(),
                e.getParentSpanId(),
                e.getRunId(),
                e.getEvalResultId(),
                e.getCaseKey(),
                e.getAttemptNo(),
                e.getSpanName(),
                e.getSpanKind(),
                e.getStatus(),
                e.getStartTimeMs(),
                e.getEndTimeMs(),
                e.getDurationMs(),
                e.getTtftMs(),
                e.getInputTokens(),
                e.getOutputTokens(),
                e.getEstimatedCostUsd(),
                e.getInputPayload(),
                e.getOutputPayload(),
                e.getAttributesJson(),
                e.getErrorType(),
                e.getErrorMessage(),
                e.getCreatedAt());
    }
}
