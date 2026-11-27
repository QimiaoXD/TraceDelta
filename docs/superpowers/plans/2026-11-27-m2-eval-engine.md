# TraceDelta M2（高并发评测调度引擎与评测看板）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build TraceDelta's recoverable, rate-limited, budget-guarded concurrent evaluation engine (Java 21 virtual threads + 3-level concurrency/rate-limit + `EvalAttempt` state machine & crash recovery + budget circuit breaker) and its monochrome Vue 3 evaluation console (`RunsView` & `RunDetailView`).

**Architecture:** Pure Java 21 modular monolith under `io.tracedelta.target` (strategy-pattern target adapters starting with `PromptTarget`) and `io.tracedelta.runner` (concurrency limiter, Redis Lua token bucket with automatic local fallback, retry/backoff policy, budget circuit breaker, virtual-thread run executor, startup crash recovery, and REST API). State is persisted to MySQL 8 / H2 via Flyway (`V4__init_eval_run.sql`) and MyBatis-Plus with unique constraints and atomic `UPDATE ... WHERE status='PENDING'` claiming; Vue 3 frontend polls `GET /api/runs` and `GET /api/runs/{id}` every 2 seconds while runs are active and stops automatically on terminal states.

**Tech Stack:** Java 21, Spring Boot 3.4.3, MyBatis-Plus 3.5.10.1, Flyway, MySQL 8 / H2 (`MODE=MySQL`), Spring Data Redis (Lettuce + Lua script) with in-memory fallback, JUnit 5 + Mockito + MockMvc, Vue 3 + TypeScript + Vite + Pinia + Vue Router + Element Plus.

**Spec:** `docs/superpowers/specs/2026-11-26-tracedelta-design.md` (Sections 2, 3.1, 4.1, 4.2, 4.4, 4.6, 4.10, 4.11, 7) + `DESIGN.md` + `PRODUCT.md`

## Global Constraints

- Pure Java modular monolith (`io.tracedelta.*`), no microservices, no ClickHouse/ES/external vector DB.
- Never store or return plaintext or ciphertext API keys in `eval_run` snapshots, API responses, or logs.
- Flyway migrations (`V4__init_eval_run.sql`) must run cleanly on both MySQL 8 and H2 (`MODE=MySQL;DATABASE_TO_LOWER=TRUE`).
- Strict TDD per task: write failing test (RED) → minimal implementation (GREEN) → run `mvn test` (and `npm run build` for frontend) → commit with bilingual English/Simplified-Chinese commit message and push to `feat/m2-eval-engine`.
- Honest consistency disclosure: guarantee already `EXEC_SUCCEEDED` cases are never re-scheduled and results are idempotently written; explicitly surface At-Least-Once `INTERRUPTED_UNKNOWN` recovery retries and possible duplicate upstream billing (never claim external LLM Exactly-Once).
- Frontend follows `DESIGN.md` Monochrome Precision Console: Zinc neutrals, 1px borders, `tabular-nums` mono metadata, Light/Dark/System themes, WCAG 2.2 AA contrast, icon+text status indicators, and zero fabricated or unimplemented M3–M5 UI affordances.
- Local macOS Apple Silicon environment: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"`, `PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"`, Maven `/opt/homebrew/bin/mvn`.

## Review Focus

1. **Cross-Run Starvation & Deadlock:** A Run with low `concurrency` (e.g. 2) and many queued cases must NOT hold global concurrency permits while waiting on its own run limit or sleeping during retry backoff; workers acquire `runSemaphore` BEFORE `globalSemaphore` and release `globalSemaphore` before backoff sleep (pinned in Task 3 & Task 5 tests).
2. **Budget Circuit Breaker Race & Post-Restart Preservation:** Multiple virtual threads finishing concurrently near `maxCostUsd` / `maxTotalTokens` must trip the breaker cleanly, allow in-flight calls to finish naturally, mark remaining `PENDING` cases `SKIPPED (BUDGET_EXCEEDED)`, and reload already-spent cost/tokens from DB on crash recovery so restarts never reset the budget (pinned in Task 4, Task 5 & Task 6 tests).
3. **Redis Outage Mid-Run Degradation:** When Redis is down or throws `DataAccessException` / `RedisConnectionFailureException`, `CompositeTokenBucketRateLimiter` must degrade seamlessly to `LocalTokenBucketRateLimiter` without failing cases and mark `rate_limit_degraded = true` on `eval_run` (pinned in Task 3 & Task 5 tests).
4. **Snapshot Secret Hygiene & Deleted Provider Safety:** `target_config_snapshot_json` must never persist API keys; if an `OPENAI_COMPATIBLE` provider is deleted before execution or recovery, the case fails cleanly as non-retryable `EXEC_FAILED` instead of crashing the executor loop (pinned in Task 2 & Task 7 tests).
5. **Cancellation vs. In-Flight Completion Race:** Cancelling a Run while virtual threads are executing must never leave `eval_run` stuck in `RUNNING` or overwrite `CANCELLED` with `COMPLETED`/`PARTIAL_FAILED`, and cancelling an already terminal Run must return `409 INVALID_STATE` (pinned in Task 5, Task 6 & Task 7 tests).

---

## File Structure Overview

### Backend (`backend/src/main/...`)
- `pom.xml` — add `spring-boot-starter-data-redis`
- `resources/application.yml` & `test/resources/application-test.yml` — Redis connection & `tracedelta.runner.*` properties
- `resources/db/migration/V4__init_eval_run.sql` — `eval_run`, `eval_result`, `eval_attempt` tables, unique keys, indexes
- `resources/lua/token_bucket.lua` — atomic Redis token bucket script
- `java/io/tracedelta/common/ErrorCode.java` — add `INVALID_STATE(409)`
- `java/io/tracedelta/target/`
  - `TargetType.java` — enum (`PROMPT`)
  - `TargetConfigSnapshot.java` — sanitized immutable snapshot record (no API key)
  - `CaseSnapshot.java` — frozen dataset case snapshot record
  - `TargetExecutionContext.java` & `TargetExecutionResult.java`
  - `PromptTemplateRenderer.java` — `{{var}}` rendering + multi-turn message assembly
  - `EvalTarget.java` — strategy interface
  - `PromptTarget.java` — `PROMPT` target implementation delegating to `LlmClientRouter`
  - `TargetRegistry.java` — resolves `TargetType -> EvalTarget`
- `java/io/tracedelta/runner/`
  - `RunnerProperties.java` — `@ConfigurationProperties("tracedelta.runner")`
  - `EvalRunStatus.java`, `EvalRunType.java`, `EvalResultStatus.java`, `EvalScoreConclusion.java`, `EvalAttemptStatus.java`
  - `EvalRunEntity.java`, `EvalResultEntity.java`, `EvalAttemptEntity.java`
  - `EvalRunMapper.java`, `EvalResultMapper.java`, `EvalAttemptMapper.java`
  - `ratelimit/RateLimitResult.java`, `ratelimit/RateLimiter.java`, `ratelimit/LocalTokenBucketRateLimiter.java`, `ratelimit/RedisTokenBucketRateLimiter.java`, `ratelimit/CompositeTokenBucketRateLimiter.java`
  - `ConcurrencyLimiter.java` — Level 1 Global Semaphore + Level 2 Run Semaphore
  - `RetryPolicy.java` — retryable error classification + exponential backoff with jitter
  - `RunBudgetTracker.java` — Run-level cost & token budget circuit breaker
  - `RunControlHandle.java` — active run cancellation & budget coordination handle
  - `EvalCaseWorker.java` — single-case attempt loop (claim, rate limit, target call, attempt persistence, retry, result persistence)
  - `EvalRunExecutor.java` — virtual-thread run scheduler, semaphore orchestration, finalization & P95/cost aggregation
  - `EvalRecoveryService.java` & `EvalRecoveryBootstrap.java` — crash recovery scanning `RUNNING` attempts/results/runs
  - `CreateEvalRunRequest.java`, `EvalRunSummaryView.java`, `EvalRunDetailView.java`, `EvalResultView.java`, `EvalAttemptView.java`
  - `EvalRunService.java` — run creation with snapshot freezing, queries, cancellation
  - `EvalRunController.java` — `/api/runs` REST endpoints

### Frontend (`frontend/src/...`)
- `api/run.ts` — TypeScript interfaces & Axios calls for `/api/runs`
- `router/index.ts` — add `/runs` and `/runs/:id` routes
- `layouts/MainLayout.vue` — add `评测任务` sidebar nav item and `/runs/:id` breadcrumb
- `views/RunsView.vue` — runs table, status filter, 2s polling for active runs, create-run modal
- `views/RunDetailView.vue` — live run dashboard, 2s polling, cancel action, honest recovery/budget/degrade banners, frozen snapshot viewer, results table, and `EvalAttempt` timeline drawer
- `DESIGN.md` & `PRODUCT.md` — sync documented implemented capabilities to M1 + M2

---

### Task 1: Schema Migration (`V4`), Domain Enums, Entities, Mappers & Redis/Runner Config

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application-test.yml`
- Modify: `backend/src/main/java/io/tracedelta/common/ErrorCode.java`
- Create: `backend/src/main/resources/db/migration/V4__init_eval_run.sql`
- Create: `backend/src/main/java/io/tracedelta/runner/RunnerProperties.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalRunStatus.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalRunType.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalResultStatus.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalScoreConclusion.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalAttemptStatus.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalRunEntity.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalResultEntity.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalAttemptEntity.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalRunMapper.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalResultMapper.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalAttemptMapper.java`
- Test: `backend/src/test/java/io/tracedelta/runner/EvalPersistenceTest.java`

**Interfaces:**
- Consumes: `io.tracedelta.common.MyBatisConfig`, Flyway migrations `V1`–`V3`.
- Produces:
  - `ErrorCode.INVALID_STATE(409)`
  - `RunnerProperties` (`globalMaxConcurrency=64`, `defaultConcurrency=8`, `defaultMaxAttempts=3`, `defaultRequestTimeoutMs=30000`, `defaultRateLimitPerSec=20.0`, `defaultRateLimitBurst=20`, `defaultRetryBaseDelayMs=250`, `defaultRetryMaxDelayMs=4000`, `redisRateLimitEnabled=true`, `recoveryOnStartup=true`)
  - `EvalRunStatus`: `PENDING`, `RUNNING`, `COMPLETED`, `PARTIAL_FAILED`, `FAILED`, `CANCELLED` (with `isTerminal()`)
  - `EvalResultStatus`: `PENDING`, `RUNNING`, `EXEC_SUCCEEDED`, `EXEC_FAILED`, `SKIPPED`
  - `EvalAttemptStatus`: `RUNNING`, `SUCCEEDED`, `FAILED`, `TIMEOUT`, `INTERRUPTED_UNKNOWN`
  - `EvalResultMapper`:
    - `int claimPending(@Param("id") Long id, @Param("maxAttempts") int maxAttempts, @Param("now") LocalDateTime now)`
    - `int incrementAttemptCount(@Param("id") Long id, @Param("maxAttempts") int maxAttempts, @Param("now") LocalDateTime now)`
    - `int skipPendingForRun(@Param("runId") Long runId, @Param("errorType") String errorType, @Param("errorMessage") String errorMessage, @Param("now") LocalDateTime now)`
  - `EvalAttemptMapper`:
    - `int markRunningInterrupted(@Param("now") LocalDateTime now, @Param("message") String message)`

- [x] **Step 1: Write the failing test `EvalPersistenceTest.java`**

```java
@SpringBootTest
@ActiveProfiles("test")
class EvalPersistenceTest {
    @Autowired private EvalRunMapper runMapper;
    @Autowired private EvalResultMapper resultMapper;
    @Autowired private EvalAttemptMapper attemptMapper;

    @Test
    void atomicClaimPendingSucceedsOnceAndRejectsSecondClaim() {
        // Insert run + PENDING result (attemptCount=0)
        // First claimPending(id, 3, now) returns 1 -> status becomes RUNNING, attemptCount=1, version=1
        // Second claimPending(id, 3, now) returns 0
    }

    @Test
    void uniqueConstraintsRejectDuplicateRunCaseKeyAndDuplicateAttemptNo() {
        // Inserting duplicate (run_id, case_key) throws DataIntegrityViolationException
        // Inserting duplicate (eval_result_id, attempt_no) throws DataIntegrityViolationException
    }

    @Test
    void skipPendingForRunOnlyAffectsPendingRows() {
        // Run with 1 PENDING, 1 RUNNING, 1 EXEC_SUCCEEDED
        // skipPendingForRun(...) returns 1; RUNNING and EXEC_SUCCEEDED remain unchanged
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test -Dtest=EvalPersistenceTest`
Expected: FAIL (compilation error — classes not yet created).

- [x] **Step 3: Add Redis dependency, `RunnerProperties`, `ErrorCode.INVALID_STATE`, `V4__init_eval_run.sql`, enums, entities, and mappers**

Key schema rules in `V4__init_eval_run.sql`:
- `eval_run` columns: `id`, `name`, `run_type`, `status`, `status_reason`, `dataset_id`, `dataset_version_id`, `dataset_name_snapshot`, `dataset_version_no`, `target_type`, `provider_id`, `provider_name_snapshot`, `model_snapshot`, `target_config_snapshot_json`, `concurrency`, `max_attempts`, `request_timeout_ms`, `rate_limit_per_sec`, `rate_limit_burst`, `max_cost_usd DECIMAL(14,6)`, `max_total_tokens BIGINT`, `retry_base_delay_ms`, `retry_max_delay_ms`, `total_cases`, `completed_cases`, `succeeded_cases`, `failed_cases`, `skipped_cases`, `total_input_tokens BIGINT`, `total_output_tokens BIGINT`, `total_cost_usd DECIMAL(14,6)`, `avg_latency_ms BIGINT`, `p95_latency_ms BIGINT`, `budget_exceeded BOOLEAN`, `budget_exceeded_reason`, `interrupted_attempt_count INT`, `rate_limit_degraded BOOLEAN`, `cancel_requested BOOLEAN`, `created_at`, `started_at`, `finished_at`, `updated_at`.
- `eval_result` columns: `id`, `run_id`, `dataset_case_id`, `case_key`, `case_hash CHAR(64)`, `case_snapshot_json LONGTEXT`, `status`, `score_conclusion`, `attempt_count INT`, `has_interrupted_unknown BOOLEAN`, `output_text LONGTEXT`, `latency_ms BIGINT`, `ttft_ms BIGINT`, `input_tokens INT`, `output_tokens INT`, `estimated_cost_usd DECIMAL(14,6)`, `error_type`, `error_message VARCHAR(1000)`, `version INT`, `started_at`, `finished_at`, `created_at`, `updated_at`, `CONSTRAINT uk_eval_result_run_case UNIQUE (run_id, case_key)`.
- `eval_attempt` columns: `id`, `run_id`, `eval_result_id`, `case_key`, `attempt_no INT`, `status`, `latency_ms BIGINT`, `ttft_ms BIGINT`, `input_tokens INT`, `output_tokens INT`, `estimated_cost_usd DECIMAL(14,6)`, `error_type`, `error_message VARCHAR(1000)`, `started_at DATETIME NOT NULL`, `finished_at DATETIME NULL`, `CONSTRAINT uk_eval_attempt_result_no UNIQUE (eval_result_id, attempt_no)`.
- Separate `CREATE INDEX` statements for H2 + MySQL 8 compatibility: `idx_eval_run_status`, `idx_eval_result_run_status`, `idx_eval_attempt_status`, `idx_eval_attempt_run`.
- In `application.yml`: add `spring.data.redis.host: ${TRACEDELTA_REDIS_HOST:127.0.0.1}`, `port: ${TRACEDELTA_REDIS_PORT:6379}`, `timeout: 500ms`, and `tracedelta.runner.*` defaults.
- In `application-test.yml`: set `tracedelta.runner.redis-rate-limit-enabled: false` and `tracedelta.runner.recovery-on-startup: false` so unit/integration tests run hermetically without requiring a live Redis server.

- [x] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: PASS (all M1 tests + `EvalPersistenceTest` green).

- [x] **Step 5: Commit and push**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml backend/src/test/resources/application-test.yml backend/src/main/resources/db/migration/V4__init_eval_run.sql backend/src/main/java/io/tracedelta/common/ErrorCode.java backend/src/main/java/io/tracedelta/runner/ backend/src/test/java/io/tracedelta/runner/EvalPersistenceTest.java
git commit -m "feat(runner): add eval run/result/attempt schema, entities, and atomic mappers / 新增评测任务表结构、实体与原子抢占 Mapper"
git push -u origin feat/m2-eval-engine
```

---

### Task 2: Target Abstraction (`io.tracedelta.target`) — `PromptTemplateRenderer`, `PromptTarget`, `TargetRegistry`

**Files:**
- Create: `backend/src/main/java/io/tracedelta/target/TargetType.java`
- Create: `backend/src/main/java/io/tracedelta/target/TargetConfigSnapshot.java`
- Create: `backend/src/main/java/io/tracedelta/target/CaseSnapshot.java`
- Create: `backend/src/main/java/io/tracedelta/target/TargetExecutionContext.java`
- Create: `backend/src/main/java/io/tracedelta/target/TargetExecutionResult.java`
- Create: `backend/src/main/java/io/tracedelta/target/PromptTemplateRenderer.java`
- Create: `backend/src/main/java/io/tracedelta/target/EvalTarget.java`
- Create: `backend/src/main/java/io/tracedelta/target/PromptTarget.java`
- Create: `backend/src/main/java/io/tracedelta/target/TargetRegistry.java`
- Test: `backend/src/test/java/io/tracedelta/target/PromptTemplateRendererTest.java`
- Test: `backend/src/test/java/io/tracedelta/target/PromptTargetTest.java`

**Interfaces:**
- Consumes: `io.tracedelta.provider.ProviderService`, `ProviderConfigEntity`, `ProviderType`, `io.tracedelta.provider.llm.*`.
- Produces:
  - `TargetType`: `PROMPT`
  - `TargetConfigSnapshot(TargetType targetType, Long providerId, String providerName, ProviderType providerType, String baseUrl, String model, String pricingJson, String mockConfigJson, String systemPrompt, String userPromptTemplate)` — explicitly excludes API key!
  - `CaseSnapshot(String caseKey, String caseHash, String inputJson, String messagesJson, String referenceAnswer)`
  - `EvalTarget`:
    - `TargetType type()`
    - `String rateLimitKey(TargetConfigSnapshot config)` -> `"provider:" + config.providerId()`
    - `TargetExecutionResult execute(TargetExecutionContext context)`
  - `TargetRegistry.get(TargetType type)` -> `EvalTarget`

- [x] **Step 1: Write failing tests `PromptTemplateRendererTest.java` & `PromptTargetTest.java`**

Tests must assert:
- `PromptTemplateRenderer`:
  - Substitutes `{{question}}` and multiple named variables `{{context}}` from `inputJson` (whitespace tolerant `{{ question }}`).
  - Defaults blank template to `{{question}}`.
  - When `messagesJson` is present on case, preserves multi-turn messages and prepends `systemPrompt` if configured.
  - Missing placeholder variables remain empty string without throwing.
- `PromptTarget`:
  - Uses frozen `TargetConfigSnapshot` (`mockConfigJson`, `pricingJson`, `model`) so later provider edits do not alter Mock execution, and passes `caseKey`, `attemptNo`, and `referenceAnswer` metadata to `LlmClientRouter`.
  - For `OPENAI_COMPATIBLE`, fetches decrypted key via `ProviderService`; if provider was deleted (`BizException(NOT_FOUND)`), returns `TargetExecutionResult` with non-retryable `LlmErrorType.MALFORMED_RESPONSE` (or `HTTP_ERROR` with `"Provider deleted"`) instead of throwing uncaught exception (Review Focus #4).
  - Serializing `TargetConfigSnapshot` to JSON contains neither `apiKey` nor `apiKeyCiphertext`.

- [x] **Step 2: Run tests to verify failure**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test -Dtest=PromptTemplateRendererTest,PromptTargetTest`
Expected: FAIL (classes not found).

- [x] **Step 3: Implement `io.tracedelta.target.*`**

Implement `PromptTemplateRenderer`, `TargetConfigSnapshot`, `CaseSnapshot`, `TargetExecutionContext`, `TargetExecutionResult`, `EvalTarget`, `PromptTarget`, `TargetRegistry`.

- [x] **Step 4: Run tests to verify pass**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: PASS.

- [x] **Step 5: Commit and push**

```bash
git add backend/src/main/java/io/tracedelta/target/ backend/src/test/java/io/tracedelta/target/
git commit -m "feat(target): add EvalTarget strategy, PromptTemplateRenderer, and PromptTarget / 新增被测目标策略抽象与 PromptTarget 实现"
git push origin feat/m2-eval-engine
```

---

### Task 3: Three-Level Concurrency Control & Redis Lua Token Bucket (with Local Fallback)

**Files:**
- Create: `backend/src/main/resources/lua/token_bucket.lua`
- Create: `backend/src/main/java/io/tracedelta/runner/ratelimit/RateLimitResult.java`
- Create: `backend/src/main/java/io/tracedelta/runner/ratelimit/RateLimiter.java`
- Create: `backend/src/main/java/io/tracedelta/runner/ratelimit/LocalTokenBucketRateLimiter.java`
- Create: `backend/src/main/java/io/tracedelta/runner/ratelimit/RedisTokenBucketRateLimiter.java`
- Create: `backend/src/main/java/io/tracedelta/runner/ratelimit/CompositeTokenBucketRateLimiter.java`
- Create: `backend/src/main/java/io/tracedelta/runner/ConcurrencyLimiter.java`
- Test: `backend/src/test/java/io/tracedelta/runner/ratelimit/TokenBucketRateLimiterTest.java`
- Test: `backend/src/test/java/io/tracedelta/runner/ConcurrencyLimiterTest.java`

**Interfaces:**
- Consumes: `RunnerProperties`, optional `ObjectProvider<StringRedisTemplate>`.
- Produces:
  - `RateLimitResult(boolean allowed, long waitMs, boolean degraded)`
  - `RateLimiter`:
    - `RateLimitResult tryAcquire(String key, double refillPerSec, int burst)`
    - `boolean acquireBlocking(String key, double refillPerSec, int burst, BooleanSupplier cancelled, AtomicBoolean degradedFlag)` — loops `tryAcquire`, sleeps `min(waitMs, 100ms)` checking `cancelled` so cancellation breaks out immediately; sets `degradedFlag` if any call degraded.
  - `ConcurrencyLimiter`:
    - `Semaphore getOrCreateRunSemaphore(Long runId, int runConcurrency)`
    - `void removeRunSemaphore(Long runId)`
    - `boolean acquireRunPermit(Semaphore runSem, BooleanSupplier cancelled)`
    - `boolean acquireGlobalPermit(BooleanSupplier cancelled)`
    - `void releaseGlobalPermit()`
    - `void releaseRunPermit(Semaphore runSem)`

- [x] **Step 1: Write failing tests `TokenBucketRateLimiterTest.java` & `ConcurrencyLimiterTest.java`**

Tests must assert:
- `LocalTokenBucketRateLimiter`:
  - Burst of 3 succeeds immediately on 1..3, 4th returns `allowed=false` with `waitMs > 0`.
  - Advancing fake clock by `1000ms / refillPerSec` refills exactly 1 token and allows next call.
  - `acquireBlocking` aborts promptly and returns `false` when `cancelled` flips to `true`.
- `CompositeTokenBucketRateLimiter`:
  - When Redis `execute(script, keys, args)` returns `[1, 0]`, returns `RateLimitResult(true, 0, false)`.
  - When Redis throws `RedisConnectionFailureException`, falls back to `LocalTokenBucketRateLimiter`, returns `degraded=true`, and still enforces rate limit locally without throwing (Review Focus #3).
- `ConcurrencyLimiter`:
  - Peak concurrent active workers for a single Run never exceeds `runConcurrency`.
  - Peak concurrent active workers across two Runs never exceeds `globalMaxConcurrency`.
  - Run A (`concurrency=1`, 10 queued workers) and Run B (`concurrency=2`, 4 workers) with `globalMaxConcurrency=3`: Run B runs 2 workers concurrently without being starved by Run A's queued workers (Review Focus #1).

- [x] **Step 2: Run tests to verify failure**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test -Dtest=TokenBucketRateLimiterTest,ConcurrencyLimiterTest`
Expected: FAIL.

- [x] **Step 3: Implement Lua script, rate limiters, and `ConcurrencyLimiter`**

`lua/token_bucket.lua` algorithm:
- `KEYS[1]` = `td:rl:<key>`, `ARGV[1]` = `capacity`, `ARGV[2]` = `refill_per_sec`, `ARGV[3]` = `now_ms`.
- Read `tokens` & `last_ts` via `HMGET`; if missing initialize `tokens = capacity`, `last_ts = now_ms`.
- Compute `elapsed_sec = max(0, (now_ms - last_ts) / 1000.0)`, `tokens = min(capacity, tokens + elapsed_sec * refill_per_sec)`.
- If `tokens >= 1.0`: deduct 1, `HMSET`, `PEXPIRE` (at least 60s or `ceil(capacity/refill)*2000`), return `{1, 0}`.
- Else: compute `wait_ms = math.ceil((1.0 - tokens) / refill_per_sec * 1000.0)`, return `{0, wait_ms}`.

- [x] **Step 4: Run tests to verify pass**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: PASS.

- [x] **Step 5: Commit and push**

```bash
git add backend/src/main/resources/lua/ backend/src/main/java/io/tracedelta/runner/ratelimit/ backend/src/main/java/io/tracedelta/runner/ConcurrencyLimiter.java backend/src/test/java/io/tracedelta/runner/
git commit -m "feat(runner): implement 3-level concurrency limiter and Redis Lua token bucket with local fallback / 实现三级并发控制与 Redis Lua 令牌桶限流及本地降级"
git push origin feat/m2-eval-engine
```

---

### Task 4: Retry Policy & Run-Level Budget Circuit Breaker

**Files:**
- Create: `backend/src/main/java/io/tracedelta/runner/RetryPolicy.java`
- Create: `backend/src/main/java/io/tracedelta/runner/RunBudgetTracker.java`
- Create: `backend/src/main/java/io/tracedelta/runner/RunControlHandle.java`
- Test: `backend/src/test/java/io/tracedelta/runner/RetryPolicyTest.java`
- Test: `backend/src/test/java/io/tracedelta/runner/RunBudgetTrackerTest.java`

**Interfaces:**
- Consumes: `io.tracedelta.provider.llm.LlmErrorType`.
- Produces:
  - `RetryPolicy`:
    - `boolean isRetryable(LlmErrorType errorType, String errorMessage)` — `true` for `RATE_LIMITED`, `TIMEOUT`, and `HTTP_ERROR` unless `errorMessage` indicates non-429 4xx (`Upstream HTTP status 4xx`); `false` for `NONE`, `MALFORMED_RESPONSE`, 4xx.
    - `long computeDelayMs(int attemptNo, long baseDelayMs, long maxDelayMs)` — `min(maxDelayMs, baseDelayMs * 2^(attemptNo - 1)) + jitter([0, baseDelayMs / 2])`.
    - `boolean sleepBackoff(long delayMs, BooleanSupplier cancelled)` — returns `false` immediately if cancelled during sleep.
  - `RunBudgetTracker`:
    - Constructor: `RunBudgetTracker(BigDecimal maxCostUsd, Long maxTotalTokens, BigDecimal initialCostUsd, long initialTotalTokens)`
    - `void recordUsage(BigDecimal costUsd, int inputTokens, int outputTokens)`
    - `boolean isExceeded()`
    - `String getExceededReason()` — `"COST_BUDGET_EXCEEDED"` or `"TOKEN_BUDGET_EXCEEDED"` or `null`
    - `BigDecimal getUsedCostUsd()`, `long getUsedTotalTokens()`
  - `RunControlHandle`: holds `runId`, `AtomicBoolean cancelRequested`, `AtomicBoolean rateLimitDegraded`, `RunBudgetTracker`, `Semaphore runSemaphore`.

- [x] **Step 1: Write failing tests `RetryPolicyTest.java` & `RunBudgetTrackerTest.java`**

Tests must assert:
- `RetryPolicyTest`:
  - `RATE_LIMITED`, `TIMEOUT`, `HTTP_ERROR("Upstream HTTP status 503")`, `HTTP_ERROR("Upstream connection error")` -> retryable.
  - `MALFORMED_RESPONSE`, `HTTP_ERROR("Upstream HTTP status 400")`, `HTTP_ERROR("Upstream HTTP status 401")` -> NOT retryable.
  - Backoff grows exponentially with cap (`base=200, max=1000`, zero jitter -> attempt 1: 200, attempt 2: 400, attempt 3: 800, attempt 4: 1000).
- `RunBudgetTrackerTest`:
  - Cost cap (`maxCostUsd=0.01`): stays below until cumulative cost `>= 0.01`, then `isExceeded() == true` and `getExceededReason() == "COST_BUDGET_EXCEEDED"`.
  - Token cap (`maxTotalTokens=100`): trips at `>= 100` with `"TOKEN_BUDGET_EXCEEDED"`.
  - Pre-seeded initial usage (simulating crash recovery): `initialTotalTokens=90, maxTotalTokens=100`, adding 15 tokens immediately trips breaker (Review Focus #2).
  - Thread-safety under 32 concurrent virtual threads calling `recordUsage` simultaneously.

- [x] **Step 2: Run tests to verify failure**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test -Dtest=RetryPolicyTest,RunBudgetTrackerTest`
Expected: FAIL.

- [x] **Step 3: Implement `RetryPolicy`, `RunBudgetTracker`, and `RunControlHandle`**

- [x] **Step 4: Run tests to verify pass**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: PASS.

- [x] **Step 5: Commit and push**

```bash
git add backend/src/main/java/io/tracedelta/runner/RetryPolicy.java backend/src/main/java/io/tracedelta/runner/RunBudgetTracker.java backend/src/main/java/io/tracedelta/runner/RunControlHandle.java backend/src/test/java/io/tracedelta/runner/RetryPolicyTest.java backend/src/test/java/io/tracedelta/runner/RunBudgetTrackerTest.java
git commit -m "feat(runner): add retry backoff policy and run-level budget circuit breaker / 新增指数退避重试策略与 Run 级预算熔断器"
git push origin feat/m2-eval-engine
```

---

### Task 5: Core Evaluation Execution Engine (`EvalCaseWorker`, `EvalRunExecutor`, `EvalRunService`)

**Files:**
- Create: `backend/src/main/java/io/tracedelta/runner/CreateEvalRunRequest.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalRunSummaryView.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalRunDetailView.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalResultView.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalAttemptView.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalCaseWorker.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalRunExecutor.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalRunService.java`
- Test: `backend/src/test/java/io/tracedelta/runner/EvalRunExecutorTest.java`

**Interfaces:**
- Consumes: `DatasetService`, `DatasetVersionMapper`, `DatasetCaseMapper`, `ProviderService`, `TargetRegistry`, `ConcurrencyLimiter`, `RateLimiter`, `RetryPolicy`, `EvalRunMapper`, `EvalResultMapper`, `EvalAttemptMapper`, `virtualThreadExecutor`.
- Produces:
  - `EvalRunService`:
    - `EvalRunDetailView createAndStart(CreateEvalRunRequest request)`
    - `List<EvalRunSummaryView> listRuns(EvalRunStatus statusFilter)`
    - `EvalRunDetailView getRunDetail(Long runId)`
    - `List<EvalResultView> listResults(Long runId, EvalResultStatus statusFilter, String query)`
    - `List<EvalAttemptView> listAttempts(Long runId, Long resultId)`
    - `EvalRunDetailView cancelRun(Long runId)`
  - `EvalRunExecutor`:
    - `CompletableFuture<Void> submitRun(Long runId)` — deduplicates concurrent submissions of the same `runId` via `ConcurrentHashMap<Long, RunControlHandle>`.
    - `void requestCancel(Long runId)`
    - `void finalizeRun(Long runId)` — computes exact aggregates from DB (`eval_result` + `eval_attempt`), including P95 latency (nearest-rank on `EXEC_SUCCEEDED` latencies) and terminal status per Spec 4.11 D.

- [ ] **Step 1: Write failing tests `EvalRunExecutorTest.java`**

Tests must assert:
1. `concurrentRunWith200MockGoodCasesCompletesWithAccurateAggregates`: 200 cases, `concurrency=16`, `mock-good` with pricing JSON -> Run ends `COMPLETED`, `completedCases=200`, `succeededCases=200`, `failedCases=0`, `skippedCases=0`, `totalCostUsd > 0`, `p95LatencyMs >= 0`, every result has `attemptCount=1` and `scoreConclusion=UNSCORED`.
2. `mockFlakyRetriesAndRecordsAttempts`: `mock-flaky` (`failRate=1.0`, `maxAttempts=3`) -> attempts 1 & 2 fail (`RATE_LIMITED`/`TIMEOUT`), attempt 3 succeeds -> result is `EXEC_SUCCEEDED` with `attemptCount=3` and 3 `eval_attempt` rows; when `maxAttempts=2`, case exhausts retries -> `EXEC_FAILED` and Run ends `PARTIAL_FAILED` (or `FAILED` if 100% cases failed).
3. `nonRetryableErrorFailsImmediatelyWithoutRetry`: custom/mocked `MALFORMED_RESPONSE` -> `attemptCount=1`, `EXEC_FAILED`.
4. `budgetCircuitBreakerStopsDispatchingAndSkipsRemainingCases`: 30 cases with `pricingJson` (`inputPer1k=10, outputPer1k=10`), `concurrency=2`, `maxCostUsd` set low enough to trip mid-run -> Run ends `PARTIAL_FAILED`, `budgetExceeded=true`, `budgetExceededReason="COST_BUDGET_EXCEEDED"`, `succeededCases > 0`, `skippedCases > 0`, skipped results have `errorType="BUDGET_EXCEEDED"`.
5. `cancelMidRunSkipsRemainingAndFinalizesAsCancelled`: 20 cases with `mock-slow` (`latencyMs=150`, `concurrency=2`) -> call `cancelRun` after ~100ms -> Run ends `CANCELLED`, never overwritten to `COMPLETED`/`PARTIAL_FAILED`, remaining `PENDING` cases marked `SKIPPED` (`CANCELLED`) (Review Focus #5).
6. `idempotentExecutionNeverReschedulesSucceededCases`: pre-mark 3 of 5 cases as `EXEC_SUCCEEDED` with `attemptCount=1`, submit Run -> only the 2 `PENDING` cases execute, the 3 pre-succeeded cases keep `attemptCount=1`.

- [ ] **Step 2: Run test to verify failure**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test -Dtest=EvalRunExecutorTest`
Expected: FAIL.

- [ ] **Step 3: Implement `EvalCaseWorker`, `EvalRunExecutor`, `EvalRunService`, and DTO/View records**

Critical execution flow in `EvalCaseWorker.executeCase(EvalRunEntity run, TargetConfigSnapshot targetConfig, EvalResultEntity result, RunControlHandle handle)`:
1. If `handle.isStopDispatching()` -> return (finalizer will mark remaining `PENDING` as `SKIPPED`).
2. Atomic claim: `resultMapper.claimPending(result.getId(), run.getMaxAttempts(), now)`; if `0` -> return (already owned or finished).
3. Loop while `attemptNo <= run.getMaxAttempts()`:
   - Acquire `globalPermit` (checking cancel); acquire `RateLimiter` token for `target.rateLimitKey(targetConfig)` (checking cancel, propagating `degradedFlag` to `handle`).
   - Insert `EvalAttemptEntity(attemptNo, RUNNING, startedAt)`.
   - Invoke `target.execute(context)` in `try/finally { concurrencyLimiter.releaseGlobalPermit(); }`.
   - Update `EvalAttemptEntity` (`SUCCEEDED` / `TIMEOUT` / `FAILED`, latency, tokens, cost, errorType, errorMessage, finishedAt).
   - Call `handle.budgetTracker().recordUsage(cost, inputTokens, outputTokens)`.
   - If succeeded -> update `eval_result` to `EXEC_SUCCEEDED` (outputText, latency, tokens, cost, finishedAt) and return!
   - If not retryable OR `attemptNo >= run.getMaxAttempts()` OR `handle.isStopDispatching()` -> update `eval_result` to `EXEC_FAILED` (errorType, errorMessage, finishedAt) and return!
   - Compute backoff delay via `RetryPolicy` and sleep (WITHOUT holding `globalSemaphore`!); if cancelled during sleep, mark `eval_result` `EXEC_FAILED (CANCELLED)` and return.
   - Increment `attemptCount` atomically (`incrementAttemptCount`) and continue loop for `attemptNo + 1`.
4. After each case finishes, refresh live progress counters on `eval_run` (atomic/lightweight counter update) so 2-second polling sees smooth real-time progress!
5. When all virtual-thread tasks complete (in `finally` of `EvalRunExecutor.runAsync`), skip any remaining `PENDING` cases with appropriate reason (`CANCELLED` / `BUDGET_EXCEEDED`), compute exact final aggregates & P95 latency, write terminal status (`CANCELLED` takes precedence if `cancelRequested`, else `FAILED` if engine error or all cases `EXEC_FAILED`, else `PARTIAL_FAILED` if `failed > 0 || skipped > 0`, else `COMPLETED`), and clean up `RunControlHandle` & `runSemaphore`.

- [ ] **Step 4: Run tests to verify pass**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: PASS.

- [ ] **Step 5: Commit and push**

```bash
git add backend/src/main/java/io/tracedelta/runner/ backend/src/test/java/io/tracedelta/runner/EvalRunExecutorTest.java
git commit -m "feat(runner): implement virtual-thread eval executor, case worker, and run service / 实现虚拟线程评测执行引擎、用例状态机与 Run 管理服务"
git push origin feat/m2-eval-engine
```

---

### Task 6: Crash Recovery (`EvalRecoveryService` & `EvalRecoveryBootstrap`)

**Files:**
- Create: `backend/src/main/java/io/tracedelta/runner/EvalRecoveryService.java`
- Create: `backend/src/main/java/io/tracedelta/runner/EvalRecoveryBootstrap.java`
- Test: `backend/src/test/java/io/tracedelta/runner/EvalRecoveryServiceTest.java`

**Interfaces:**
- Consumes: `EvalRunMapper`, `EvalResultMapper`, `EvalAttemptMapper`, `EvalRunExecutor`, `RunnerProperties`.
- Produces:
  - `RecoverySummary(int interruptedAttemptsMarked, int resultsRequeued, int resultsExhausted, int runsResumed)`
  - `EvalRecoveryService.recoverInterruptedRuns()` -> `RecoverySummary`
  - `EvalRecoveryBootstrap`: `@EventListener(ApplicationReadyEvent.class)` invokes `recoverInterruptedRuns()` when `runnerProperties.isRecoveryOnStartup()` is true.

- [ ] **Step 1: Write failing test `EvalRecoveryServiceTest.java`**

Tests must assert:
1. `recoversInterruptedRunWithoutReexecutingSucceededCases`:
   - Seed DB directly (simulating mid-run JVM crash):
     - `eval_run` in `RUNNING` (`maxAttempts=3`, `totalCases=4`).
     - Case 1: `EXEC_SUCCEEDED`, `attemptCount=1` + 1 `SUCCEEDED` attempt.
     - Case 2: `RUNNING`, `attemptCount=1` + 1 `RUNNING` attempt (`attemptNo=1`).
     - Case 3: `RUNNING`, `attemptCount=3` + 2 `FAILED` + 1 `RUNNING` attempt (`attemptNo=3`, at cap!).
     - Case 4: `PENDING`, `attemptCount=0`.
   - Call `recoverInterruptedRuns()` and await completion of resumed run:
     - Case 1 stays `EXEC_SUCCEEDED` with `attemptCount=1` (NEVER re-scheduled!).
     - Case 2's attempt 1 is marked `INTERRUPTED_UNKNOWN`, Case 2 retries as `attemptNo=2`, reaches `EXEC_SUCCEEDED` with `hasInterruptedUnknown=true`.
     - Case 3's attempt 3 is marked `INTERRUPTED_UNKNOWN`, Case 3 transitions directly to `EXEC_FAILED` (`errorType="INTERRUPTED_UNKNOWN"`) with `attemptCount=3` (no 4th attempt!).
     - Case 4 runs `attemptNo=1` -> `EXEC_SUCCEEDED`.
     - Run finishes `PARTIAL_FAILED` with `interruptedAttemptCount=2`, `succeededCases=3`, `failedCases=1`.
2. `recoveryRestoresAlreadySpentBudget`:
   - Seed a `RUNNING` run with `maxTotalTokens=50`, 1 `EXEC_SUCCEEDED` case already consuming 55 tokens, and 2 `PENDING` cases -> after recovery, remaining 2 cases are immediately `SKIPPED (BUDGET_EXCEEDED)` without calling LLM (Review Focus #2).
3. `recoveryFinalizesCancelledBeforeCrashRunWithoutDispatching`:
   - Seed a `RUNNING` run with `cancelRequested=true` and 2 `PENDING` cases -> recovery marks them `SKIPPED (CANCELLED)` and finalizes Run as `CANCELLED` (Review Focus #5).

- [ ] **Step 2: Run test to verify failure**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test -Dtest=EvalRecoveryServiceTest`
Expected: FAIL.

- [ ] **Step 3: Implement `EvalRecoveryService` and `EvalRecoveryBootstrap`**

- [ ] **Step 4: Run tests to verify pass**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: PASS.

- [ ] **Step 5: Commit and push**

```bash
git add backend/src/main/java/io/tracedelta/runner/EvalRecoveryService.java backend/src/main/java/io/tracedelta/runner/EvalRecoveryBootstrap.java backend/src/test/java/io/tracedelta/runner/EvalRecoveryServiceTest.java
git commit -m "feat(runner): add EvalAttempt crash recovery with At-Least-Once tracking / 新增进程中断恢复服务与 INTERRUPTED_UNKNOWN 追踪"
git push origin feat/m2-eval-engine
```

---

### Task 7: Evaluation Runs REST Controller & API Integration Tests

**Files:**
- Create: `backend/src/main/java/io/tracedelta/runner/EvalRunController.java`
- Test: `backend/src/test/java/io/tracedelta/runner/EvalRunControllerTest.java`

**Interfaces:**
- Consumes: `EvalRunService`, `ApiResponse`, JWT authentication filter.
- Produces REST API:
  - `POST /api/runs` — create & start run (`@Valid CreateEvalRunRequest`) -> `ApiResponse<EvalRunDetailView>`
  - `GET /api/runs` — list runs (`@RequestParam(required = false) EvalRunStatus status`) -> `ApiResponse<List<EvalRunSummaryView>>`
  - `GET /api/runs/{id}` — run detail -> `ApiResponse<EvalRunDetailView>`
  - `GET /api/runs/{id}/results` — list results (`@RequestParam(required = false) EvalResultStatus status`, `@RequestParam(required = false) String q`) -> `ApiResponse<List<EvalResultView>>`
  - `GET /api/runs/{id}/results/{resultId}/attempts` — list attempts -> `ApiResponse<List<EvalAttemptView>>`
  - `POST /api/runs/{id}/cancel` — cancel active run -> `ApiResponse<EvalRunDetailView>`

- [ ] **Step 1: Write failing integration test `EvalRunControllerTest.java`**

Tests must assert:
- Unauthenticated `GET /api/runs` returns `401`.
- Authenticated `POST /api/runs` creates run, polling `GET /api/runs/{id}` reaches `COMPLETED`, `GET /api/runs/{id}/results` returns all cases, `GET /api/runs/{id}/results/{resultId}/attempts` returns attempt records.
- `targetConfigSnapshotJson` in `GET /api/runs/{id}` never contains `apiKey` or `apiKeyCiphertext` even for an `OPENAI_COMPATIBLE` provider (Review Focus #4).
- `POST /api/runs/{id}/cancel` on a `COMPLETED` run returns `409` with `code = "INVALID_STATE"` (Review Focus #5).
- Validation errors (`concurrency = 0`, `maxCostUsd = -1`, non-existent dataset/version/provider) return `400` / `404` with unified `ApiResponse`.

- [ ] **Step 2: Run test to verify failure**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test -Dtest=EvalRunControllerTest`
Expected: FAIL (404 NoResourceFound).

- [ ] **Step 3: Implement `EvalRunController.java`**

- [ ] **Step 4: Run full backend test suite to verify pass**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: PASS (all backend unit + integration tests green).

- [ ] **Step 5: Commit and push**

```bash
git add backend/src/main/java/io/tracedelta/runner/EvalRunController.java backend/src/test/java/io/tracedelta/runner/EvalRunControllerTest.java
git commit -m "feat(runner): expose /api/runs REST endpoints with validation and integration tests / 新增评测任务 REST 接口与集成测试"
git push origin feat/m2-eval-engine
```

---

### Task 8: Frontend Evaluation Runs Console (`RunsView` & `RunDetailView`) & Docs Sync

**Files:**
- Create: `frontend/src/api/run.ts`
- Create: `frontend/src/views/RunsView.vue`
- Create: `frontend/src/views/RunDetailView.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/MainLayout.vue`
- Modify: `frontend/src/stores/consoleData.ts`
- Modify: `DESIGN.md`
- Modify: `PRODUCT.md`

**Interfaces:**
- Consumes: `POST/GET /api/runs`, `GET /api/runs/{id}`, `GET /api/runs/{id}/results`, `GET /api/runs/{id}/results/{resultId}/attempts`, `POST /api/runs/{id}/cancel`, existing `useConsoleDataStore` (datasets, versions, providers), `TdIcon`, `design-system.css` tokens.
- Produces:
  - Sidebar nav item `评测任务` (`/runs`, icon `activity`) + `/runs/:id` breadcrumb (`评测任务 / <runName>`).
  - `RunsView.vue`:
    - Header: `评测任务` + `新建评测` primary button.
    - Toolbar: status filter pills (`全部` / `运行中` / `已完成` / `部分失败` / `失败` / `已取消`), search input, active polling badge when any run is `PENDING`/`RUNNING` (2s interval, cleaned up on `onBeforeUnmount`).
    - Compact table: run name + frozen dataset/version + provider/model, status badge (icon + Chinese label), progress (`completed/total`), latency (`avg / P95`), tokens & cost, created time, enter action.
    - Create Run modal: dataset selector + version selector (shows case count), provider selector + optional model override, optional `systemPrompt` & `userPromptTemplate` (`{{question}}`), collapsible/grouped execution & budget config (`concurrency`, `maxAttempts`, `rateLimitPerSec`, `rateLimitBurst`, `requestTimeoutMs`, optional `maxCostUsd` & `maxTotalTokens`). Validates before submit, navigates to `/runs/:id` on creation.
  - `RunDetailView.vue`:
    - Header: run name, status badge (icon + label), live 2s polling indicator while active (stops automatically on terminal state), `取消任务` button (with confirmation) when `PENDING`/`RUNNING`, manual refresh button.
    - Honest engineering alert banners (shown ONLY when corresponding backend field is true / > 0):
      - `interruptedAttemptCount > 0`: warning banner disclosing At-Least-Once restart recovery count and possible duplicate upstream billing.
      - `budgetExceeded`: warning banner showing `budgetExceededReason` and skipped case impact.
      - `rateLimitDegraded`: info/warning banner noting Redis was unavailable and local in-memory rate limiting was used.
    - Metrics strip (real data only): progress bar (`completedCases / totalCases`), `执行成功` / `执行失败` / `已跳过`, `平均 / P95 延迟 (ms)`, `总 Tokens (入/出)`, `估算费用 ($)`.
    - Collapsible frozen snapshot card: dataset name & version, provider & model, prompt templates, concurrency / retry / rate-limit / budget parameters.
    - Case Results table: status filter (`全部` / `成功` / `失败` / `已跳过` / `待执行`) + search by `case_key`, columns: `case_key`, status (icon + text), `尝试次数` (+ `中断恢复` tag when `hasInterruptedUnknown`), latency ms, tokens, cost, output/error summary, inspect button.
    - Case Inspector Drawer: full output text, error message, `case_hash` (with copy + `aria-live`), and `EvalAttempt` attempt timeline (`#attemptNo`, status, latency, tokens, cost, errorType, errorMessage, timestamps).

- [ ] **Step 1: Add `frontend/src/api/run.ts`, update router, `MainLayout.vue`, and `consoleData.ts`**

- [ ] **Step 2: Build `RunsView.vue` and `RunDetailView.vue` following `DESIGN.md` tokens & WCAG 2.2 AA rules**

- [ ] **Step 3: Sync `DESIGN.md` and `PRODUCT.md` to reflect M1 + M2 implemented scope**

- [ ] **Step 4: Run frontend typecheck + production build and backend full test suite**

Run: `npm --prefix frontend run build && JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: Frontend builds cleanly with zero TypeScript errors; backend `mvn test` passes 100%.

- [ ] **Step 5: Commit and push**

```bash
git add frontend/ DESIGN.md PRODUCT.md
git commit -m "feat(frontend): add evaluation runs console, live polling dashboard, and attempt inspector / 新增评测任务列表、轮询看板与 EvalAttempt 尝试时间线"
git push origin feat/m2-eval-engine
```
