package io.tracedelta.trace;

import java.time.Duration;

public interface TraceSpanWriter {

    void submit(TraceSpanEntity span);

    boolean flushAll(Duration timeout);

    TraceWriterStats stats();

    int droppedCountForRun(Long runId);
}
