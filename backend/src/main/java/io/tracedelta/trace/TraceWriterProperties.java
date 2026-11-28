package io.tracedelta.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tracedelta.trace")
public class TraceWriterProperties {

    private int queueCapacity = 4096;
    private int batchSize = 100;
    private long flushIntervalMs = 500L;
    private boolean syncFallbackOnFull = true;
    private int maxPayloadChars = 32768;

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    public boolean isSyncFallbackOnFull() {
        return syncFallbackOnFull;
    }

    public void setSyncFallbackOnFull(boolean syncFallbackOnFull) {
        this.syncFallbackOnFull = syncFallbackOnFull;
    }

    public int getMaxPayloadChars() {
        return maxPayloadChars;
    }

    public void setMaxPayloadChars(int maxPayloadChars) {
        this.maxPayloadChars = maxPayloadChars;
    }
}
