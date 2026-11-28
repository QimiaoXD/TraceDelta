package io.tracedelta.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.tracedelta.provider.llm.LlmErrorType;
import io.tracedelta.runner.EvalResultEntity;
import io.tracedelta.runner.EvalResultMapper;
import io.tracedelta.runner.EvalResultStatus;
import io.tracedelta.runner.EvalRunEntity;
import io.tracedelta.runner.EvalRunMapper;
import io.tracedelta.runner.EvalRunStatus;
import io.tracedelta.runner.EvalRunType;
import io.tracedelta.runner.EvalScoreConclusion;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TracePersistenceTest {

    @Autowired
    private TraceSpanMapper traceSpanMapper;

    @Autowired
    private EvalRunMapper evalRunMapper;

    @Autowired
    private EvalResultMapper evalResultMapper;

    @Autowired
    private TraceWriterProperties traceWriterProperties;

    @Test
    void generatesValidOtelHexIdsAndLoadsTraceProperties() {
        String traceId = TraceIdGenerator.newTraceId();
        String spanId = TraceIdGenerator.newSpanId();

        assertThat(traceId).matches("^[0-9a-f]{32}$");
        assertThat(spanId).matches("^[0-9a-f]{16}$");
        assertThat(TraceIdGenerator.newTraceId()).isNotEqualTo(traceId);
        assertThat(traceWriterProperties.getQueueCapacity()).isGreaterThan(0);
        assertThat(traceWriterProperties.getBatchSize()).isGreaterThan(0);
        assertThat(LlmErrorType.SSRF_BLOCKED).isNotNull();
    }

    @Test
    void insertsQueriesOrdersAndDeletesTraceSpansByRunId() {
        EvalRunEntity run1 = insertHttpRun("trace-persist-run-1");
        EvalRunEntity run2 = insertHttpRun("trace-persist-run-2");
        String traceId1 = TraceIdGenerator.newTraceId();
        String traceId2 = TraceIdGenerator.newTraceId();
        EvalResultEntity result1 = insertResult(run1.getId(), "k1", traceId1);
        EvalResultEntity result2 = insertResult(run2.getId(), "k1", traceId2);

        String rootSpanId = TraceIdGenerator.newSpanId();
        String childSpanId = TraceIdGenerator.newSpanId();
        long baseMs = System.currentTimeMillis();

        // Insert child first, then root with earlier start time to prove ordering
        TraceSpanEntity child = buildSpan(
                traceId1, childSpanId, rootSpanId, run1.getId(), result1.getId(),
                "k1", 1, "gen_ai.chat", SpanKind.LLM_CALL, SpanStatus.OK,
                baseMs + 20, baseMs + 120);
        TraceSpanEntity root = buildSpan(
                traceId1, rootSpanId, null, run1.getId(), result1.getId(),
                "k1", 0, "eval.case", SpanKind.CASE_ROOT, SpanStatus.OK,
                baseMs, baseMs + 130);
        TraceSpanEntity otherRunSpan = buildSpan(
                traceId2, TraceIdGenerator.newSpanId(), null, run2.getId(), result2.getId(),
                "k1", 0, "eval.case", SpanKind.CASE_ROOT, SpanStatus.OK,
                baseMs, baseMs + 50);

        traceSpanMapper.insert(child);
        traceSpanMapper.insert(root);
        traceSpanMapper.insert(otherRunSpan);

        List<TraceSpanEntity> ordered = traceSpanMapper.findByTraceIdOrdered(traceId1);
        assertThat(ordered).hasSize(2);
        assertThat(ordered.get(0).getSpanId()).isEqualTo(rootSpanId);
        assertThat(ordered.get(1).getSpanId()).isEqualTo(childSpanId);
        assertThat(ordered.get(1).getParentSpanId()).isEqualTo(rootSpanId);

        int deleted = traceSpanMapper.deleteByRunId(run1.getId());
        assertThat(deleted).isEqualTo(2);
        assertThat(traceSpanMapper.findByTraceIdOrdered(traceId1)).isEmpty();
        assertThat(traceSpanMapper.findByTraceIdOrdered(traceId2)).hasSize(1);
    }

    @Test
    void incrementsTraceDroppedAndSupportsNullableProviderId() {
        EvalRunEntity httpRun = insertHttpRun("trace-drop-run");
        assertThat(httpRun.getProviderId()).isNull();
        assertThat(httpRun.getHttpTargetId()).isEqualTo(42L);

        int updated = evalRunMapper.incrementTraceDropped(httpRun.getId(), 3, LocalDateTime.now());
        assertThat(updated).isEqualTo(1);
        evalRunMapper.incrementTraceDropped(httpRun.getId(), 2, LocalDateTime.now());

        EvalRunEntity reloaded = evalRunMapper.selectById(httpRun.getId());
        assertThat(reloaded.getTraceIncomplete()).isTrue();
        assertThat(reloaded.getTraceDroppedCount()).isEqualTo(5);
    }

    private EvalRunEntity insertHttpRun(String name) {
        LocalDateTime now = LocalDateTime.now();
        EvalRunEntity run = new EvalRunEntity();
        run.setName(name);
        run.setRunType(EvalRunType.STANDARD.name());
        run.setStatus(EvalRunStatus.RUNNING.name());
        run.setDatasetId(1L);
        run.setDatasetVersionId(1L);
        run.setDatasetNameSnapshot("ds");
        run.setDatasetVersionNo(1);
        run.setTargetType("HTTP");
        run.setProviderId(null);
        run.setHttpTargetId(42L);
        run.setProviderNameSnapshot("external-qa-api");
        run.setModelSnapshot("POST api.example.com/qa");
        run.setTargetConfigSnapshotJson("{}");
        run.setConcurrency(4);
        run.setMaxAttempts(3);
        run.setRequestTimeoutMs(30000L);
        run.setRateLimitPerSec(20.0);
        run.setRateLimitBurst(20);
        run.setRetryBaseDelayMs(100L);
        run.setRetryMaxDelayMs(1000L);
        run.setTotalCases(1);
        run.setCompletedCases(0);
        run.setSucceededCases(0);
        run.setFailedCases(0);
        run.setSkippedCases(0);
        run.setTotalInputTokens(0L);
        run.setTotalOutputTokens(0L);
        run.setTotalCostUsd(BigDecimal.ZERO);
        run.setBudgetExceeded(false);
        run.setInterruptedAttemptCount(0);
        run.setRateLimitDegraded(false);
        run.setCancelRequested(false);
        run.setTraceIncomplete(false);
        run.setTraceDroppedCount(0);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        evalRunMapper.insert(run);
        return run;
    }

    private EvalResultEntity insertResult(Long runId, String caseKey, String traceId) {
        LocalDateTime now = LocalDateTime.now();
        EvalResultEntity result = new EvalResultEntity();
        result.setRunId(runId);
        result.setCaseKey(caseKey);
        result.setCaseHash("b".repeat(64));
        result.setCaseSnapshotJson("{}");
        result.setTraceId(traceId);
        result.setStatus(EvalResultStatus.PENDING.name());
        result.setScoreConclusion(EvalScoreConclusion.UNSCORED.name());
        result.setAttemptCount(0);
        result.setHasInterruptedUnknown(false);
        result.setInputTokens(0);
        result.setOutputTokens(0);
        result.setEstimatedCostUsd(BigDecimal.ZERO);
        result.setVersion(0);
        result.setCreatedAt(now);
        result.setUpdatedAt(now);
        evalResultMapper.insert(result);
        return result;
    }

    private TraceSpanEntity buildSpan(
            String traceId,
            String spanId,
            String parentSpanId,
            Long runId,
            Long resultId,
            String caseKey,
            int attemptNo,
            String name,
            SpanKind kind,
            SpanStatus status,
            long startMs,
            long endMs) {
        TraceSpanEntity span = new TraceSpanEntity();
        span.setTraceId(traceId);
        span.setSpanId(spanId);
        span.setParentSpanId(parentSpanId);
        span.setRunId(runId);
        span.setEvalResultId(resultId);
        span.setCaseKey(caseKey);
        span.setAttemptNo(attemptNo);
        span.setSpanName(name);
        span.setSpanKind(kind.name());
        span.setStatus(status.name());
        span.setStartTimeMs(startMs);
        span.setEndTimeMs(endMs);
        span.setDurationMs(Math.max(0L, endMs - startMs));
        span.setCreatedAt(LocalDateTime.now());
        return span;
    }
}
