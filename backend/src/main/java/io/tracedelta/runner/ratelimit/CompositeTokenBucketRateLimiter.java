package io.tracedelta.runner.ratelimit;

import io.tracedelta.runner.RunnerProperties;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class CompositeTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(CompositeTokenBucketRateLimiter.class);
    private static final long WARN_INTERVAL_MS = 30_000L;

    private final RunnerProperties properties;
    private final RedisTokenBucketRateLimiter redisLimiter;
    private final LocalTokenBucketRateLimiter localLimiter;
    private final AtomicLong lastWarnMs = new AtomicLong(0L);

    public CompositeTokenBucketRateLimiter(
            RunnerProperties properties,
            RedisTokenBucketRateLimiter redisLimiter,
            LocalTokenBucketRateLimiter localLimiter) {
        this.properties = properties;
        this.redisLimiter = redisLimiter;
        this.localLimiter = localLimiter;
    }

    @Override
    public RateLimitResult tryAcquire(String key, double refillPerSec, int burst) {
        if (!properties.isRedisRateLimitEnabled() || !redisLimiter.isAvailable()) {
            return localLimiter.tryAcquire(key, refillPerSec, burst);
        }
        try {
            return redisLimiter.tryAcquire(key, refillPerSec, burst);
        } catch (RuntimeException ex) {
            logThrottledWarning(key, ex);
            RateLimitResult local = localLimiter.tryAcquire(key, refillPerSec, burst);
            return new RateLimitResult(local.allowed(), local.waitMs(), true);
        }
    }

    private void logThrottledWarning(String key, RuntimeException ex) {
        long now = System.currentTimeMillis();
        long prev = lastWarnMs.get();
        if (now - prev >= WARN_INTERVAL_MS && lastWarnMs.compareAndSet(prev, now)) {
            log.warn(
                    "Redis rate limiter unavailable for key={}, falling back to local token bucket: {}",
                    key,
                    ex.getMessage());
        }
    }
}
