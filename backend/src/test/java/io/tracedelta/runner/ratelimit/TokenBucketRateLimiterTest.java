package io.tracedelta.runner.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.tracedelta.runner.RunnerProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class TokenBucketRateLimiterTest {

    @Test
    void localTokenBucketEnforcesBurstAndRefillsOverTime() {
        AtomicLong fakeNowMs = new AtomicLong(1000L);
        LocalTokenBucketRateLimiter limiter = new LocalTokenBucketRateLimiter(fakeNowMs::get);

        assertThat(limiter.tryAcquire("provider:1", 2.0, 3).allowed()).isTrue();
        assertThat(limiter.tryAcquire("provider:1", 2.0, 3).allowed()).isTrue();
        assertThat(limiter.tryAcquire("provider:1", 2.0, 3).allowed()).isTrue();

        RateLimitResult blocked = limiter.tryAcquire("provider:1", 2.0, 3);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.waitMs()).isGreaterThanOrEqualTo(400L);

        // Advance by 500ms at 2 tokens/sec -> refills 1 token
        fakeNowMs.addAndGet(500L);
        assertThat(limiter.tryAcquire("provider:1", 2.0, 3).allowed()).isTrue();
        assertThat(limiter.tryAcquire("provider:1", 2.0, 3).allowed()).isFalse();
    }

    @Test
    void acquireBlockingAbortsImmediatelyWhenCancelled() {
        AtomicLong fakeNowMs = new AtomicLong(1000L);
        LocalTokenBucketRateLimiter limiter = new LocalTokenBucketRateLimiter(fakeNowMs::get);
        assertThat(limiter.tryAcquire("provider:2", 0.5, 1).allowed()).isTrue();

        AtomicBoolean cancelled = new AtomicBoolean(true);
        AtomicBoolean degraded = new AtomicBoolean(false);
        boolean acquired = limiter.acquireBlocking("provider:2", 0.5, 1, cancelled::get, degraded);

        assertThat(acquired).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void compositeUsesRedisLuaWhenAvailableAndFallsBackLocallyOnRedisFailure() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(List.of(1L, 0L));

        RunnerProperties props = new RunnerProperties();
        props.setRedisRateLimitEnabled(true);
        LocalTokenBucketRateLimiter local = new LocalTokenBucketRateLimiter(System::currentTimeMillis);
        RedisTokenBucketRateLimiter redis = new RedisTokenBucketRateLimiter(redisTemplate);
        CompositeTokenBucketRateLimiter composite = new CompositeTokenBucketRateLimiter(props, redis, local);

        RateLimitResult ok = composite.tryAcquire("provider:10", 10.0, 2);
        assertThat(ok.allowed()).isTrue();
        assertThat(ok.degraded()).isFalse();

        // Simulate Redis outage -> must seamlessly degrade to local bucket
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis down"));

        RateLimitResult deg1 = composite.tryAcquire("provider:10", 1.0, 1);
        assertThat(deg1.allowed()).isTrue();
        assertThat(deg1.degraded()).isTrue();

        // Local bucket still enforces burst limit during degradation
        RateLimitResult deg2 = composite.tryAcquire("provider:10", 1.0, 1);
        assertThat(deg2.allowed()).isFalse();
        assertThat(deg2.degraded()).isTrue();
    }
}
