package io.tracedelta.trace;

import java.util.List;

public record TraceDetailView(
        String traceId,
        Long runId,
        Long evalResultId,
        String caseKey,
        Boolean traceIncomplete,
        Long startTimeMs,
        Long endTimeMs,
        Long totalDurationMs,
        List<TraceSpanView> spans) {
}
