package io.tracedelta.trace;

import io.tracedelta.runner.EvalRunMapper;
import jakarta.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class BoundedAsyncTraceSpanWriter implements TraceSpanWriter, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BoundedAsyncTraceSpanWriter.class);

    private static final String INSERT_SQL = """
            INSERT INTO trace_span (
                trace_id, span_id, parent_span_id, run_id, eval_result_id, case_key, attempt_no,
                span_name, span_kind, status, start_time_ms, end_time_ms, duration_ms,
                ttft_ms, input_tokens, output_tokens, estimated_cost_usd,
                input_payload, output_payload, attributes_json, error_type, error_message, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    @FunctionalInterface
    interface BatchPersister {
        void persistBatch(List<TraceSpanEntity> spans);
    }

    private final TraceWriterProperties properties;
    private final SensitiveDataSanitizer sanitizer;
    private final EvalRunMapper evalRunMapper;
    private final BatchPersister batchPersister;
    private final ArrayBlockingQueue<TraceSpanEntity> queue;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private final Object idleMonitor = new Object();

    private final AtomicLong enqueuedCount = new AtomicLong();
    private final AtomicLong flushedCount = new AtomicLong();
    private final AtomicLong syncFallbackCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong batchCount = new AtomicLong();
    private final ConcurrentHashMap<Long, AtomicInteger> droppedByRun = new ConcurrentHashMap<>();

    private final Thread workerThread;

    @Autowired
    public BoundedAsyncTraceSpanWriter(
            TraceWriterProperties properties,
            SensitiveDataSanitizer sanitizer,
            EvalRunMapper evalRunMapper,
            JdbcTemplate jdbcTemplate) {
        this(properties, sanitizer, evalRunMapper, batch -> jdbcBatchInsert(jdbcTemplate, batch), true);
    }

    BoundedAsyncTraceSpanWriter(
            TraceWriterProperties properties,
            SensitiveDataSanitizer sanitizer,
            EvalRunMapper evalRunMapper,
            BatchPersister batchPersister,
            boolean startWorker) {
        this.properties = properties;
        this.sanitizer = sanitizer;
        this.evalRunMapper = evalRunMapper;
        this.batchPersister = batchPersister;
        int capacity = Math.max(1, properties.getQueueCapacity());
        this.queue = new ArrayBlockingQueue<>(capacity);
        if (startWorker) {
            this.workerThread = new Thread(this::flushLoop, "tracedelta-trace-flusher");
            this.workerThread.setDaemon(true);
            this.workerThread.start();
        } else {
            this.workerThread = null;
        }
    }

    @Override
    public void submit(TraceSpanEntity span) {
        if (span == null) {
            return;
        }
        try {
            if (span.getCreatedAt() == null) {
                span.setCreatedAt(LocalDateTime.now());
            }
            if (span.getDurationMs() == null && span.getStartTimeMs() != null && span.getEndTimeMs() != null) {
                span.setDurationMs(Math.max(0L, span.getEndTimeMs() - span.getStartTimeMs()));
            }
            TraceSpanEntity sanitized = sanitizer.sanitizeSpan(span, properties.getMaxPayloadChars());

            if (running.get() && queue.offer(sanitized)) {
                enqueuedCount.incrementAndGet();
                return;
            }

            if (properties.isSyncFallbackOnFull()) {
                syncFallbackCount.incrementAndGet();
                try {
                    batchPersister.persistBatch(List.of(sanitized));
                    flushedCount.incrementAndGet();
                    batchCount.incrementAndGet();
                    return;
                } catch (Exception ex) {
                    log.warn("TraceSpan sync fallback persist failed for runId={}: {}", sanitized.getRunId(), ex.getMessage());
                }
            }
            recordDrop(sanitized.getRunId());
        } catch (Throwable ex) {
            log.warn("Unexpected error while submitting TraceSpan for runId={}: {}", span.getRunId(), ex.getMessage());
            recordDrop(span.getRunId());
        }
    }

    @Override
    public boolean flushAll(Duration timeout) {
        if (workerThread == null) {
            drainRemainingSynchronously();
            return queue.isEmpty();
        }
        long deadlineNanos = System.nanoTime() + Math.max(1L, timeout.toMillis()) * 1_000_000L;
        synchronized (idleMonitor) {
            while (!queue.isEmpty() || flushing.get()) {
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0L) {
                    return false;
                }
                try {
                    idleMonitor.wait(Math.min(remainingMs, 50L));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public TraceWriterStats stats() {
        return new TraceWriterStats(
                queue.size(),
                properties.getQueueCapacity(),
                enqueuedCount.get(),
                flushedCount.get(),
                syncFallbackCount.get(),
                droppedCount.get(),
                batchCount.get());
    }

    @Override
    public int droppedCountForRun(Long runId) {
        if (runId == null) {
            return 0;
        }
        AtomicInteger counter = droppedByRun.get(runId);
        return counter == null ? 0 : counter.get();
    }

    @PreDestroy
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(2000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        drainRemainingSynchronously();
    }

    private void flushLoop() {
        int batchSize = Math.max(1, properties.getBatchSize());
        long intervalMs = Math.max(10L, properties.getFlushIntervalMs());
        List<TraceSpanEntity> batch = new ArrayList<>(batchSize);

        while (running.get() || !queue.isEmpty()) {
            try {
                TraceSpanEntity first = queue.poll(intervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    notifyIfIdle();
                    continue;
                }
                flushing.set(true);
                try {
                    batch.clear();
                    batch.add(first);
                    queue.drainTo(batch, batchSize - 1);
                    persistWithIsolation(batch);
                } finally {
                    flushing.set(false);
                    notifyIfIdle();
                }
            } catch (InterruptedException ex) {
                if (!running.get()) {
                    break;
                }
            } catch (Throwable ex) {
                log.warn("Unexpected error in trace flusher loop: {}", ex.getMessage());
            }
        }
    }

    private void drainRemainingSynchronously() {
        int batchSize = Math.max(1, properties.getBatchSize());
        List<TraceSpanEntity> batch = new ArrayList<>(batchSize);
        while (!queue.isEmpty()) {
            batch.clear();
            queue.drainTo(batch, batchSize);
            if (!batch.isEmpty()) {
                persistWithIsolation(batch);
            }
        }
        notifyIfIdle();
    }

    private void persistWithIsolation(List<TraceSpanEntity> batch) {
        try {
            batchPersister.persistBatch(batch);
            flushedCount.addAndGet(batch.size());
            batchCount.incrementAndGet();
            return;
        } catch (Exception ex) {
            log.warn("Batch TraceSpan insert failed (size={}), retrying individually: {}", batch.size(), ex.getMessage());
        }

        // Isolate single bad row so healthy sibling spans are still persisted
        for (TraceSpanEntity single : batch) {
            try {
                batchPersister.persistBatch(List.of(single));
                flushedCount.incrementAndGet();
                batchCount.incrementAndGet();
            } catch (Exception singleEx) {
                log.warn("Single TraceSpan persist failed for runId={} spanId={}: {}",
                        single.getRunId(), single.getSpanId(), singleEx.getMessage());
                recordDrop(single.getRunId());
            }
        }
    }

    private void recordDrop(Long runId) {
        droppedCount.incrementAndGet();
        if (runId != null) {
            droppedByRun.computeIfAbsent(runId, k -> new AtomicInteger()).incrementAndGet();
            try {
                evalRunMapper.incrementTraceDropped(runId, 1, LocalDateTime.now());
            } catch (Exception ex) {
                log.debug("Unable to update trace_incomplete on eval_run {}: {}", runId, ex.getMessage());
            }
        }
    }

    private void notifyIfIdle() {
        if (queue.isEmpty() && !flushing.get()) {
            synchronized (idleMonitor) {
                idleMonitor.notifyAll();
            }
        }
    }

    private static void jdbcBatchInsert(JdbcTemplate jdbcTemplate, List<TraceSpanEntity> spans) {
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TraceSpanEntity s = spans.get(i);
                ps.setString(1, s.getTraceId());
                ps.setString(2, s.getSpanId());
                if (s.getParentSpanId() != null) {
                    ps.setString(3, s.getParentSpanId());
                } else {
                    ps.setNull(3, Types.CHAR);
                }
                ps.setLong(4, s.getRunId());
                ps.setLong(5, s.getEvalResultId());
                ps.setString(6, s.getCaseKey());
                ps.setInt(7, s.getAttemptNo() == null ? 0 : s.getAttemptNo());
                ps.setString(8, s.getSpanName());
                ps.setString(9, s.getSpanKind());
                ps.setString(10, s.getStatus());
                ps.setLong(11, s.getStartTimeMs());
                ps.setLong(12, s.getEndTimeMs());
                ps.setLong(13, s.getDurationMs());
                setNullableLong(ps, 14, s.getTtftMs());
                setNullableInt(ps, 15, s.getInputTokens());
                setNullableInt(ps, 16, s.getOutputTokens());
                if (s.getEstimatedCostUsd() != null) {
                    ps.setBigDecimal(17, s.getEstimatedCostUsd());
                } else {
                    ps.setNull(17, Types.DECIMAL);
                }
                setNullableString(ps, 18, s.getInputPayload());
                setNullableString(ps, 19, s.getOutputPayload());
                setNullableString(ps, 20, s.getAttributesJson());
                setNullableString(ps, 21, s.getErrorType());
                setNullableString(ps, 22, s.getErrorMessage());
                LocalDateTime created = s.getCreatedAt() == null ? LocalDateTime.now() : s.getCreatedAt();
                ps.setTimestamp(23, Timestamp.valueOf(created));
            }

            @Override
            public int getBatchSize() {
                return spans.size();
            }
        });
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long val) throws SQLException {
        if (val != null) {
            ps.setLong(idx, val);
        } else {
            ps.setNull(idx, Types.BIGINT);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer val) throws SQLException {
        if (val != null) {
            ps.setInt(idx, val);
        } else {
            ps.setNull(idx, Types.INTEGER);
        }
    }

    private static void setNullableString(PreparedStatement ps, int idx, String val) throws SQLException {
        if (val != null) {
            ps.setString(idx, val);
        } else {
            ps.setNull(idx, Types.VARCHAR);
        }
    }
}
