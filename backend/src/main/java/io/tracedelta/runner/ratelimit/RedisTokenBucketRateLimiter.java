package io.tracedelta.runner.ratelimit;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

@Component
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "td:rl:";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> script;

    @Autowired
    public RedisTokenBucketRateLimiter(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(redisTemplateProvider.getIfAvailable());
    }

    public RedisTokenBucketRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/token_bucket.lua")));
        this.script.setResultType(List.class);
    }

    public boolean isAvailable() {
        return redisTemplate != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitResult tryAcquire(String key, double refillPerSec, int burst) {
        if (redisTemplate == null) {
            throw new IllegalStateException("StringRedisTemplate is not configured");
        }
        int capacity = Math.max(1, burst);
        double rate = Math.max(0.01, refillPerSec);
        long nowMs = System.currentTimeMillis();

        List<Object> result = redisTemplate.execute(
                script,
                List.of(KEY_PREFIX + key),
                String.valueOf(capacity),
                String.valueOf(rate),
                String.valueOf(nowMs));

        if (result == null || result.size() < 2) {
            throw new IllegalStateException("Unexpected Redis token bucket response");
        }
        long allowed = toLong(result.get(0));
        long waitMs = toLong(result.get(1));
        return new RateLimitResult(allowed == 1L, waitMs, false);
    }

    private long toLong(Object val) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(val));
    }
}
