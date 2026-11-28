package io.tracedelta.trace;

import io.tracedelta.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TraceController {

    private final TraceQueryService traceQueryService;

    public TraceController(TraceQueryService traceQueryService) {
        this.traceQueryService = traceQueryService;
    }

    @GetMapping("/api/runs/{runId}/results/{resultId}/trace")
    public ApiResponse<TraceDetailView> getTraceForResult(
            @PathVariable Long runId,
            @PathVariable Long resultId) {
        return ApiResponse.ok(traceQueryService.getTraceForResult(runId, resultId));
    }

    @GetMapping("/api/traces/writer-stats")
    public ApiResponse<TraceWriterStatsView> writerStats() {
        return ApiResponse.ok(traceQueryService.getWriterStats());
    }

    @GetMapping("/api/traces/{traceId}")
    public ApiResponse<TraceDetailView> getTraceById(@PathVariable String traceId) {
        return ApiResponse.ok(traceQueryService.getTraceByTraceId(traceId));
    }
}
