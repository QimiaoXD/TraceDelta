package io.tracedelta.runner.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LocalTokenBucketRateLimiter implements RateLimiter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier clockMillis;

    @Autowired
    public LocalTokenBucketRateLimiter() {
        this(System::currentTimeMillis);
    }

    public LocalTokenBucketRateLimiter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    @Override
    public RateLimitResult tryAcquire(String key, double refillPerSec, int burst) {
        int capacity = Math.max(1, burst);
        double rate = Math.max(0.01, refillPerSec);
        long nowMs = clockMillis.getAsLong();

        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, nowMs));
        synchronized (bucket) {
            if (nowMs > bucket.lastTsMs) {
                double elapsedSec = (nowMs - bucket.lastTsMs) / 1000.0;
                bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSec * rate);
                bucket.lastTsMs = nowMs;
            } else if (bucket.tokens > capacity) {
                bucket.tokens = capacity;
            }

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return new RateLimitResult(true, 0L, false);
            }
            double missing = 1.0 - bucket.tokens;
            long waitMs = Math.max(1L, (long) Math.ceil((missing / rate) * 1000.0));
            return new RateLimitResult(false, waitMs, false);
        }
    }

    private static final class Bucket {
        double tokens;
        long lastTsMs;

        Bucket(double tokens, long lastTsMs) {
            this.tokens = tokens;
            this.lastTsMs = lastTsMs;
        }
    }
}
