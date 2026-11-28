package io.tracedelta.trace;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Target-emitted span data before the runner attaches traceId, runId, evalResultId, attemptNo, and parentSpanId.
 */
public record SpanDraft(
        String spanName,
        SpanKind spanKind,
        SpanStatus status,
        long startTimeMs,
        long endTimeMs,
        Long ttftMs,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal estimatedCostUsd,
        String inputPayload,
        String outputPayload,
        Map<String, Object> attributes,
        String errorType,
        String errorMessage) {
}
