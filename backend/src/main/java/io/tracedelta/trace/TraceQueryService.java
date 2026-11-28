package io.tracedelta.trace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.tracedelta.common.BizException;
import io.tracedelta.common.ErrorCode;
import io.tracedelta.runner.EvalResultEntity;
import io.tracedelta.runner.EvalResultMapper;
import io.tracedelta.runner.EvalRunEntity;
import io.tracedelta.runner.EvalRunMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TraceQueryService {

    private final TraceSpanMapper traceSpanMapper;
    private final EvalResultMapper evalResultMapper;
    private final EvalRunMapper evalRunMapper;
    private final TraceSpanWriter traceSpanWriter;

    public TraceQueryService(
            TraceSpanMapper traceSpanMapper,
            EvalResultMapper evalResultMapper,
            EvalRunMapper evalRunMapper,
            TraceSpanWriter traceSpanWriter) {
        this.traceSpanMapper = traceSpanMapper;
        this.evalResultMapper = evalResultMapper;
        this.evalRunMapper = evalRunMapper;
        this.traceSpanWriter = traceSpanWriter;
    }

    public TraceDetailView getTraceForResult(Long runId, Long resultId) {
        EvalRunEntity run = evalRunMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测任务不存在: " + runId);
        }
        EvalResultEntity result = evalResultMapper.selectById(resultId);
        if (result == null || !runId.equals(result.getRunId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测用例结果不存在: " + resultId);
        }
        String traceId = result.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            return new TraceDetailView(
                    null,
                    runId,
                    resultId,
                    result.getCaseKey(),
                    Boolean.TRUE.equals(run.getTraceIncomplete()),
                    null,
                    null,
                    0L,
                    List.of());
        }
        List<TraceSpanEntity> spans = traceSpanMapper.findByTraceIdOrdered(traceId);
        return buildDetailView(traceId, run, result, spans);
    }

    public TraceDetailView getTraceByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new BizException(ErrorCode.NOT_FOUND, "Trace 不存在");
        }
        EvalResultEntity result = evalResultMapper.selectOne(
                new LambdaQueryWrapper<EvalResultEntity>().eq(EvalResultEntity::getTraceId, traceId));
        List<TraceSpanEntity> spans = traceSpanMapper.findByTraceIdOrdered(traceId);
        if (result == null && spans.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "Trace 不存在: " + traceId);
        }
        Long runId = result != null ? result.getRunId() : spans.get(0).getRunId();
        EvalRunEntity run = runId == null ? null : evalRunMapper.selectById(runId);
        return buildDetailView(traceId, run, result, spans);
    }

    public TraceWriterStatsView getWriterStats() {
        return TraceWriterStatsView.fromStats(traceSpanWriter.stats());
    }

    private TraceDetailView buildDetailView(
            String traceId,
            EvalRunEntity run,
            EvalResultEntity result,
            List<TraceSpanEntity> spans) {
        Long runId = result != null ? result.getRunId() : (spans.isEmpty() ? null : spans.get(0).getRunId());
        Long resultId = result != null ? result.getId() : (spans.isEmpty() ? null : spans.get(0).getEvalResultId());
        String caseKey = result != null ? result.getCaseKey() : (spans.isEmpty() ? null : spans.get(0).getCaseKey());
        boolean incomplete = run != null && Boolean.TRUE.equals(run.getTraceIncomplete());

        Long minStart = null;
        Long maxEnd = null;
        for (TraceSpanEntity s : spans) {
            if (s.getStartTimeMs() != null && (minStart == null || s.getStartTimeMs() < minStart)) {
                minStart = s.getStartTimeMs();
            }
            if (s.getEndTimeMs() != null && (maxEnd == null || s.getEndTimeMs() > maxEnd)) {
                maxEnd = s.getEndTimeMs();
            }
        }
        long totalDuration = (minStart != null && maxEnd != null) ? Math.max(0L, maxEnd - minStart) : 0L;
        List<TraceSpanView> spanViews = spans.stream().map(TraceSpanView::fromEntity).toList();
        return new TraceDetailView(
                traceId,
                runId,
                resultId,
                caseKey,
                incomplete,
                minStart,
                maxEnd,
                totalDuration,
                spanViews);
    }
}
