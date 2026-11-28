package io.tracedelta.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tracedelta.runner")
public class RunnerProperties {

    private int globalMaxConcurrency = 64;
    private int defaultConcurrency = 8;
    private int defaultMaxAttempts = 3;
    private long defaultRequestTimeoutMs = 30_000L;
    private double defaultRateLimitPerSec = 20.0;
    private int defaultRateLimitBurst = 20;
    private long defaultRetryBaseDelayMs = 250L;
    private long defaultRetryMaxDelayMs = 4_000L;
    private boolean redisRateLimitEnabled = true;
    private boolean recoveryOnStartup = true;

    public int getGlobalMaxConcurrency() {
        return globalMaxConcurrency;
    }

    public void setGlobalMaxConcurrency(int globalMaxConcurrency) {
        this.globalMaxConcurrency = globalMaxConcurrency;
    }

    public int getDefaultConcurrency() {
        return defaultConcurrency;
    }

    public void setDefaultConcurrency(int defaultConcurrency) {
        this.defaultConcurrency = defaultConcurrency;
    }

    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    public long getDefaultRequestTimeoutMs() {
        return defaultRequestTimeoutMs;
    }

    public void setDefaultRequestTimeoutMs(long defaultRequestTimeoutMs) {
        this.defaultRequestTimeoutMs = defaultRequestTimeoutMs;
    }

    public double getDefaultRateLimitPerSec() {
        return defaultRateLimitPerSec;
    }

    public void setDefaultRateLimitPerSec(double defaultRateLimitPerSec) {
        this.defaultRateLimitPerSec = defaultRateLimitPerSec;
    }

    public int getDefaultRateLimitBurst() {
        return defaultRateLimitBurst;
    }

    public void setDefaultRateLimitBurst(int defaultRateLimitBurst) {
        this.defaultRateLimitBurst = defaultRateLimitBurst;
    }

    public long getDefaultRetryBaseDelayMs() {
        return defaultRetryBaseDelayMs;
    }

    public void setDefaultRetryBaseDelayMs(long defaultRetryBaseDelayMs) {
        this.defaultRetryBaseDelayMs = defaultRetryBaseDelayMs;
    }

    public long getDefaultRetryMaxDelayMs() {
        return defaultRetryMaxDelayMs;
    }

    public void setDefaultRetryMaxDelayMs(long defaultRetryMaxDelayMs) {
        this.defaultRetryMaxDelayMs = defaultRetryMaxDelayMs;
    }

    public boolean isRedisRateLimitEnabled() {
        return redisRateLimitEnabled;
    }

    public void setRedisRateLimitEnabled(boolean redisRateLimitEnabled) {
        this.redisRateLimitEnabled = redisRateLimitEnabled;
    }

    public boolean isRecoveryOnStartup() {
        return recoveryOnStartup;
    }

    public void setRecoveryOnStartup(boolean recoveryOnStartup) {
        this.recoveryOnStartup = recoveryOnStartup;
    }
}
