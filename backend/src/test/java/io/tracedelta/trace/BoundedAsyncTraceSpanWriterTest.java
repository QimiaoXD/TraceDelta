package io.tracedelta.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracedelta.runner.EvalRunMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class BoundedAsyncTraceSpanWriterTest {

    private final SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new ObjectMapper());

    @Test
    void flushesBatchesAsynchronouslyAndTracksStats() {
        TraceWriterProperties props = new TraceWriterProperties();
        props.setQueueCapacity(512);
        props.setBatchSize(50);
        props.setFlushIntervalMs(30);
        props.setSyncFallbackOnFull(true);

        List<TraceSpanEntity> persisted = Collections.synchronizedList(new ArrayList<>());
        EvalRunMapper runMapper = mock(EvalRunMapper.class);

        try (BoundedAsyncTraceSpanWriter writer = new BoundedAsyncTraceSpanWriter(
                props, sanitizer, runMapper, batch -> persisted.addAll(batch), true)) {
            for (int i = 0; i < 230; i++) {
                writer.submit(sampleSpan(1L, "k-" + i, "Bearer secret-" + i));
            }

            assertThat(writer.flushAll(Duration.ofSeconds(3))).isTrue();
            assertThat(persisted).hasSize(230);
            assertThat(persisted.get(0).getInputPayload()).doesNotContain("secret-0").contains("Bearer [REDACTED]");

            TraceWriterStats stats = writer.stats();
            assertThat(stats.enqueuedCount()).isEqualTo(230);
            assertThat(stats.flushedCount()).isEqualTo(230);
            assertThat(stats.droppedCount()).isZero();
            assertThat(stats.batchCount()).isGreaterThanOrEqualTo(5);
        }
    }

    @Test
    void fallsBackSynchronouslyWhenBoundedQueueIsFull() {
        TraceWriterProperties props = new TraceWriterProperties();
        props.setQueueCapacity(2);
        props.setBatchSize(10);
        props.setFlushIntervalMs(200);
        props.setSyncFallbackOnFull(true);

        List<TraceSpanEntity> persisted = Collections.synchronizedList(new ArrayList<>());
        EvalRunMapper runMapper = mock(EvalRunMapper.class);

        // Background worker disabled -> queue fills after 2 items, remaining 3 must use sync fallback
        try (BoundedAsyncTraceSpanWriter writer = new BoundedAsyncTraceSpanWriter(
                props, sanitizer, runMapper, batch -> persisted.addAll(batch), false)) {
            for (int i = 0; i < 5; i++) {
                writer.submit(sampleSpan(7L, "k-" + i, "ok"));
            }

            TraceWriterStats beforeClose = writer.stats();
            assertThat(beforeClose.queueSize()).isEqualTo(2);
            assertThat(beforeClose.syncFallbackCount()).isEqualTo(3);
            assertThat(beforeClose.droppedCount()).isZero();
            assertThat(persisted).hasSize(3);
        }
        // close() drains the remaining 2 queued spans
        assertThat(persisted).hasSize(5);
    }

    @Test
    void dropsAndMarksRunIncompleteWithoutThrowingWhenQueueFullAndDbFails() {
        TraceWriterProperties props = new TraceWriterProperties();
        props.setQueueCapacity(2);
        props.setBatchSize(10);
        props.setFlushIntervalMs(200);
        props.setSyncFallbackOnFull(true);

        EvalRunMapper runMapper = mock(EvalRunMapper.class);
        AtomicInteger attempts = new AtomicInteger();

        try (BoundedAsyncTraceSpanWriter writer = new BoundedAsyncTraceSpanWriter(
                props,
                sanitizer,
                runMapper,
                batch -> {
                    attempts.incrementAndGet();
                    throw new DataAccessResourceFailureException("Simulated DB outage");
                },
                false)) {
            for (int i = 0; i < 5; i++) {
                writer.submit(sampleSpan(99L, "k-" + i, "payload"));
            }

            assertThat(writer.stats().droppedCount()).isEqualTo(3);
            assertThat(writer.droppedCountForRun(99L)).isEqualTo(3);
            assertThat(attempts.get()).isEqualTo(3);
            verify(runMapper, atLeastOnce()).incrementTraceDropped(eq(99L), eq(1), any(LocalDateTime.class));
        }
    }

    @Test
    void singleBadRowInBatchDoesNotDropHealthySiblingSpans() throws Exception {
        TraceWriterProperties props = new TraceWriterProperties();
        props.setQueueCapacity(64);
        props.setBatchSize(10);
        props.setFlushIntervalMs(25);

        List<TraceSpanEntity> persisted = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);
        EvalRunMapper runMapper = mock(EvalRunMapper.class);

        try (BoundedAsyncTraceSpanWriter writer = new BoundedAsyncTraceSpanWriter(
                props,
                sanitizer,
                runMapper,
                batch -> {
                    if (batch.size() > 1) {
                        throw new DataAccessResourceFailureException("Batch failed because of poison row");
                    }
                    TraceSpanEntity single = batch.get(0);
                    if ("poison".equals(single.getCaseKey())) {
                        throw new DataAccessResourceFailureException("Poison row rejected");
                    }
                    persisted.add(single);
                    latch.countDown();
                },
                true)) {
            writer.submit(sampleSpan(12L, "good-1", "ok"));
            writer.submit(sampleSpan(12L, "poison", "bad"));
            writer.submit(sampleSpan(12L, "good-2", "ok"));

            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(writer.flushAll(Duration.ofSeconds(2))).isTrue();
            assertThat(persisted).extracting(TraceSpanEntity::getCaseKey)
                    .containsExactlyInAnyOrder("good-1", "good-2");
            assertThat(writer.droppedCountForRun(12L)).isEqualTo(1);
            verify(runMapper, atLeastOnce()).incrementTraceDropped(eq(12L), eq(1), any(LocalDateTime.class));
        }
    }

    private TraceSpanEntity sampleSpan(Long runId, String caseKey, String input) {
        TraceSpanEntity span = new TraceSpanEntity();
        span.setTraceId(TraceIdGenerator.newTraceId());
        span.setSpanId(TraceIdGenerator.newSpanId());
        span.setRunId(runId);
        span.setEvalResultId(100L);
        span.setCaseKey(caseKey);
        span.setAttemptNo(1);
        span.setSpanName("gen_ai.chat");
        span.setSpanKind(SpanKind.LLM_CALL.name());
        span.setStatus(SpanStatus.OK.name());
        long now = System.currentTimeMillis();
        span.setStartTimeMs(now);
        span.setEndTimeMs(now + 10);
        span.setDurationMs(10L);
        span.setInputPayload(input);
        span.setCreatedAt(LocalDateTime.now());
        return span;
    }
}
