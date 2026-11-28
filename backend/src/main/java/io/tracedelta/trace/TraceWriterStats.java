package io.tracedelta.trace;

public record TraceWriterStats(
        int queueSize,
        int queueCapacity,
        long enqueuedCount,
        long flushedCount,
        long syncFallbackCount,
        long droppedCount,
        long batchCount) {
}
