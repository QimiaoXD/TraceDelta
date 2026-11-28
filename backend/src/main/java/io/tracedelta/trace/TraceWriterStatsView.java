package io.tracedelta.trace;

public record TraceWriterStatsView(
        int queueSize,
        int queueCapacity,
        long enqueuedCount,
        long flushedCount,
        long syncFallbackCount,
        long droppedCount,
        long batchCount) {

    public static TraceWriterStatsView fromStats(TraceWriterStats s) {
        return new TraceWriterStatsView(
                s.queueSize(),
                s.queueCapacity(),
                s.enqueuedCount(),
                s.flushedCount(),
                s.syncFallbackCount(),
                s.droppedCount(),
                s.batchCount());
    }
}
