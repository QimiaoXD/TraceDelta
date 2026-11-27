package io.tracedelta.runner.ratelimit;

public record RateLimitResult(boolean allowed, long waitMs, boolean degraded) {
}
