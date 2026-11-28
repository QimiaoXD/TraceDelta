-- Atomic Token Bucket Rate Limiter (Redis Lua)
-- KEYS[1]: bucket key (e.g. td:rl:provider:1)
-- ARGV[1]: capacity (burst, integer >= 1)
-- ARGV[2]: refill_per_sec (number > 0)
-- ARGV[3]: now_ms (current epoch millis)
-- Returns {allowed (1 or 0), wait_ms}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_sec = tonumber(ARGV[2])
local now_ms = tonumber(ARGV[3])

local data = redis.call('HMGET', key, 'tokens', 'last_ts')
local tokens = tonumber(data[1])
local last_ts = tonumber(data[2])

if tokens == nil or last_ts == nil then
    tokens = capacity
    last_ts = now_ms
else
    local elapsed_ms = now_ms - last_ts
    if elapsed_ms > 0 then
        local refill = (elapsed_ms / 1000.0) * refill_per_sec
        tokens = math.min(capacity, tokens + refill)
        last_ts = now_ms
    end
end

local ttl_ms = math.max(60000, math.ceil((capacity / refill_per_sec) * 2000))

if tokens >= 1.0 then
    tokens = tokens - 1.0
    redis.call('HMSET', key, 'tokens', tostring(tokens), 'last_ts', tostring(last_ts))
    redis.call('PEXPIRE', key, ttl_ms)
    return {1, 0}
else
    redis.call('HMSET', key, 'tokens', tostring(tokens), 'last_ts', tostring(last_ts))
    redis.call('PEXPIRE', key, ttl_ms)
    local missing = 1.0 - tokens
    local wait_ms = math.max(1, math.ceil((missing / refill_per_sec) * 1000.0))
    return {0, wait_ms}
end
