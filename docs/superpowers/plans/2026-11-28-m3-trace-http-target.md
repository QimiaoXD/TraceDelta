# TraceDelta M3（OTel 风格 Trace/Span 链路追踪与 HttpTarget SSRF 防护）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver TraceDelta's OTel-aligned Trace/Span execution observability (bounded async batch writer with backpressure, fallback, drop accounting, and automatic secret sanitization), reusable `HttpTarget` external-endpoint evaluation with strict SSRF protection, and a Monochrome Precision Console UI for Trace waterfall replay and HTTP target management.

**Architecture:** Pure Java 21 modular monolith extending `io.tracedelta.trace` (OTel GenAI/HTTP semantic span model, `SensitiveDataSanitizer`, `BoundedAsyncTraceSpanWriter` backed by an `ArrayBlockingQueue` + JDBC batch flusher with sync fallback and `eval_run.trace_incomplete` drop tracking, and read/stats REST API) and `io.tracedelta.target` (`SsrfGuard` with DNS resolution + IP range blocking + redirect disabled, JSON-safe `HttpRequestTemplateRenderer`, Jackson `JsonPathExtractor`, encrypted-header `HttpTargetConfig` CRUD/smoke-test, and `HttpTarget` strategy). Flyway `V5__init_trace_and_http_target.sql` evolves `eval_run`/`eval_result` and creates `http_target` and `trace_span`; Vue 3 adds `HttpTargetsView`, dual-target run creation, reusable `TraceWaterfall`, and `/traces/:traceId`.

**Tech Stack:** Java 21, Spring Boot 3.4.3, MyBatis-Plus 3.5.10.1, Spring JDBC (`JdbcTemplate` batch insert), Flyway, MySQL 8 / H2 (`MODE=MySQL`), JDK `java.net.http.HttpClient`, JUnit 5 + Mockito + MockMvc + OkHttp `MockWebServer`, Vue 3 + TypeScript + Vite + Pinia + Vue Router + Element Plus.

**Spec:** `docs/superpowers/specs/2026-11-26-tracedelta-design.md` (Sections 2, 3.1, 4.3, 4.4, 4.10, 4.11 A/C/G, 7) + `DESIGN.md` + `PRODUCT.md`

## Global Constraints

- Pure Java modular monolith (`io.tracedelta.*`); no microservices, no ClickHouse/ES/Kafka/external vector DB.
- Critical data (`eval_result`, `eval_attempt`) stays synchronous and reliable; diagnostic data (`trace_span`) uses bounded async batch writing and must NEVER crash or fail an evaluation case when backpressured.
- Never persist plaintext sensitive headers or API keys in `http_target`, `eval_run.target_config_snapshot_json`, `trace_span`, or logs; encrypt `http_target` headers at rest with `AesGcmEncryptor` (`TRACEDELTA_MASTER_KEY`).
- Flyway migration `V5__init_trace_and_http_target.sql` must execute cleanly on both MySQL 8 and H2 (`MODE=MySQL;DATABASE_TO_LOWER=TRUE`).
- Strict TDD per task: write failing test (RED) → minimal implementation (GREEN) → run `mvn test` (and `npm run build` for frontend) → bilingual English/Simplified-Chinese commit and push to `feat/m3-trace-http-target`.
- Frontend strictly follows `DESIGN.md` Monochrome Precision Console: Zinc neutrals, 1px borders, `tabular-nums` mono metadata, Light/Dark/System themes, WCAG 2.2 AA contrast, icon+text status badges, and zero fake/unimplemented M4–M5 controls.
- Local macOS Apple Silicon toolchain: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"`, `PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"`, Maven `/opt/homebrew/bin/mvn`.

## Review Focus

1. **SSRF Bypass Vectors (IPv6-Mapped IPv4, CGNAT, Cloud Metadata, Redirects, Non-HTTP Schemes):** `http://169.254.169.254/latest/meta-data/`, `http://[::ffff:127.0.0.1]/`, `http://100.64.0.1/`, `file:///etc/passwd`, `http://user:pass@example.com`, and `302 Location: http://127.0.0.1:8080/actuator` redirects from an allowed host must all be blocked as non-retryable `SSRF_BLOCKED` (pinned in Task 4 & Task 5 tests).
2. **Bounded Queue Backpressure & Non-Blocking Case Isolation:** When the `TraceSpan` queue is full and synchronous fallback DB insert also fails, `BoundedAsyncTraceSpanWriter` must never grow memory unboundedly, never propagate exceptions into `EvalCaseWorker`, and must mark `eval_run.trace_incomplete = true` with accurate `trace_dropped_count` while `EvalResult` / `EvalAttempt` still succeed (pinned in Task 2 & Task 3 tests).
3. **Secret Hygiene Across Snapshots, Spans & Headers:** `Authorization: Bearer ...`, `X-Api-Key`, `Cookie`, and inline `sk-...` tokens in `HttpTarget` headers, payloads, or error messages must be encrypted at rest (`headers_ciphertext`), masked in API views/snapshots, and redacted to `[REDACTED]` before entering the trace queue (pinned in Task 2, Task 3 & Task 5 tests).
4. **JSON-Safe Template Substitution & Missing JSONPath Handling:** `HttpTarget` inputs containing quotes, backslashes, or newlines (`He said "hi"\nnext`) must render valid JSON when substituted inside JSON string values; unmatched `responseJsonPath` must return non-retryable `MALFORMED_RESPONSE` with clear message instead of NPE or retries (pinned in Task 4 & Task 5 tests).
5. **Run Finalization Flush Ordering & Transactional Cascade Delete:** Finalizing a Run must flush queued spans (with bounded timeout) before flipping to terminal status so immediate UI reads see complete spans, and deleting a terminal Run must delete `trace_span` in the same transaction as `eval_attempt` and `eval_result` (pinned in Task 3 tests).

---

## File Structure Overview

### Backend (`backend/src/main/...`)
- `resources/db/migration/V5__init_trace_and_http_target.sql` — evolve `eval_run`/`eval_result`, create `http_target` and `trace_span` tables & indexes
- `resources/application.yml` & `test/resources/application-test.yml` — add `tracedelta.trace.*` and `tracedelta.security.ssrf.*` configuration properties
- `java/io/tracedelta/provider/llm/LlmErrorType.java` — add `SSRF_BLOCKED`
- `java/io/tracedelta/trace/`
  - `SpanKind.java` — enum (`CASE_ROOT`, `ATTEMPT`, `PROMPT_RENDER`, `TEMPLATE_RENDER`, `LLM_CALL`, `HTTP_CALL`, `RETRIEVAL`, `EVALUATOR`)
  - `SpanStatus.java` — enum (`OK`, `ERROR`)
  - `TraceIdGenerator.java` — OTel-compatible 32-hex `traceId` & 16-hex `spanId` generator
  - `SpanDraft.java` — target-produced span record before runner attaches trace/run/attempt context
  - `TraceSpanEntity.java` — MyBatis-Plus entity for `trace_span`
  - `TraceSpanMapper.java` — MyBatis-Plus mapper + delete-by-run helper
  - `SensitiveDataSanitizer.java` — header/JSON/text redaction & payload size truncation
  - `TraceWriterProperties.java` — `@ConfigurationProperties("tracedelta.trace")`
  - `TraceWriterStats.java` — snapshot record of queue/enqueued/flushed/fallback/dropped/batch metrics
  - `TraceSpanWriter.java` — writer interface (`submit`, `flushAll`, `stats`, `droppedCountForRun`)
  - `BoundedAsyncTraceSpanWriter.java` — `ArrayBlockingQueue` + daemon batch flusher + `JdbcTemplate.batchUpdate` + sync fallback + drop accounting + `@PreDestroy` graceful drain
  - `TraceSpanView.java`, `TraceDetailView.java`, `TraceWriterStatsView.java` — REST response DTOs
  - `TraceQueryService.java` — queries trace by `(runId, resultId)` or `traceId`
  - `TraceController.java` — `/api/traces/{traceId}`, `/api/traces/writer-stats`, `/api/runs/{runId}/results/{resultId}/trace`
- `java/io/tracedelta/target/`
  - `TargetType.java` — add `HTTP`
  - `TargetConfigSnapshot.java` — add nullable HTTP target snapshot fields (`httpTargetId`, `httpTargetName`, `httpUrl`, `httpMethod`, `httpHeadersMaskedJson`, `httpBodyTemplate`, `httpResponseJsonPath`)
  - `TargetExecutionResult.java` — add `List<SpanDraft> spans` (with backward-compatible single-arg helper)
  - `PromptTarget.java` — emit `PROMPT_RENDER` + `LLM_CALL` (OTel GenAI attributes) `SpanDraft`s
  - `http/SsrfProperties.java` — `@ConfigurationProperties("tracedelta.security.ssrf")`
  - `http/HostResolver.java` — functional interface (`InetAddress[] resolveAll(String host)`)
  - `http/SsrfGuard.java` — scheme/userinfo/whitelist/IP-range SSRF validator
  - `http/HttpRequestTemplateRenderer.java` — JSON-string-aware `{{var}}` renderer
  - `http/JsonPathExtractor.java` — Jackson-based JSONPath extractor (`$.a.b[0].c`, `$['k']`, `$`)
  - `http/HttpTargetEntity.java`, `http/HttpTargetMapper.java` — persistence for `http_target`
  - `http/HttpTargetUpsertRequest.java`, `http/HttpTargetView.java`, `http/HttpTargetSmokeTestRequest.java`, `http/HttpTargetSmokeTestView.java`
  - `http/HttpTargetService.java` — CRUD with SSRF validation, AES-GCM header encryption, masked header preview, and single smoke test
  - `http/HttpTargetController.java` — `/api/http-targets` REST endpoints
  - `http/HttpTarget.java` — `EvalTarget` implementation (`TargetType.HTTP`, rate limit key `http_target:<id>`, `Redirect.NEVER`, SSRF re-check, outer `HTTP_CALL` Span capture)
- `java/io/tracedelta/runner/`
  - `EvalRunEntity.java`, `EvalRunSummaryView.java`, `EvalRunDetailView.java` — add `httpTargetId`, `traceIncomplete`, `traceDroppedCount`
  - `EvalResultEntity.java`, `EvalResultView.java` — add `traceId`
  - `EvalRunMapper.java` — add `markTraceIncomplete(runId, droppedDelta, now)`
  - `CreateEvalRunRequest.java` — make `providerId` conditional, add `httpTargetId`
  - `EvalCaseWorker.java` — wrap case & attempts with `CASE_ROOT` / `ATTEMPT` spans and submit target `SpanDraft`s to `TraceSpanWriter`
  - `EvalRunExecutor.java` — call `traceSpanWriter.flushAll(...)` and persist `traceIncomplete`/`traceDroppedCount` during `finalizeRun`
  - `EvalRunService.java` — support creating `HTTP` runs (freeze sanitized HTTP snapshot, generate `traceId` on `EvalResult`), cascade-delete `trace_span` in `deleteRun`

### Frontend (`frontend/src/...`)
- `api/httpTarget.ts` — TypeScript interfaces & Axios calls for `/api/http-targets`
- `api/trace.ts` — TypeScript interfaces & Axios calls for `/api/traces/*` & `/api/runs/:runId/results/:resultId/trace`
- `api/run.ts` — extend `CreateEvalRunPayload`, `EvalRunSummary`, `EvalRunDetail`, `EvalResultItem` for `HTTP` targets & Trace fields
- `stores/consoleData.ts` — add `httpTargets` cache helpers
- `components/TraceWaterfall.vue` — reusable monochrome OTel Span waterfall timeline & Span detail inspector
- `views/HttpTargetsView.vue` — HTTP Target list, create/edit modal (headers, JSON body template, JSONPath, SSRF notice), single smoke test dialog
- `views/TraceDetailView.vue` — full-page `/traces/:traceId` replay view
- `views/RunsView.vue` — dual-target (`PROMPT` / `HTTP`) selector in Create Run modal and target metadata rendering
- `views/RunDetailView.vue` — `traceIncomplete` honest disclosure banner, HTTP target snapshot display, Trace waterfall tab + full-page jump inside Case Inspector drawer
- `router/index.ts` & `layouts/MainLayout.vue` — add `/http-targets` nav item and `/traces/:traceId` route + breadcrumb
- `DESIGN.md` & `PRODUCT.md` — sync documented implemented scope to M1 + M2 + M3

---

### Task 1: Schema Migration (`V5`), Trace Domain Entities, Mappers & `TraceIdGenerator`

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application-test.yml`
- Modify: `backend/src/main/java/io/tracedelta/provider/llm/LlmErrorType.java`
- Modify: `backend/src/main/java/io/tracedelta/runner/EvalRunEntity.java`
- Modify: `backend/src/main/java/io/tracedelta/runner/EvalResultEntity.java`
- Modify: `backend/src/main/java/io/tracedelta/runner/EvalRunMapper.java`
- Create: `backend/src/main/resources/db/migration/V5__init_trace_and_http_target.sql`
- Create: `backend/src/main/java/io/tracedelta/trace/SpanKind.java`
- Create: `backend/src/main/java/io/tracedelta/trace/SpanStatus.java`
- Create: `backend/src/main/java/io/tracedelta/trace/TraceIdGenerator.java`
- Create: `backend/src/main/java/io/tracedelta/trace/SpanDraft.java`
- Create: `backend/src/main/java/io/tracedelta/trace/TraceSpanEntity.java`
- Create: `backend/src/main/java/io/tracedelta/trace/TraceSpanMapper.java`
- Create: `backend/src/main/java/io/tracedelta/trace/TraceWriterProperties.java`
- Test: `backend/src/test/java/io/tracedelta/trace/TracePersistenceTest.java`

**Interfaces:**
- Consumes: Flyway migrations `V1`–`V4`, `EvalRunMapper`, `EvalResultMapper`.
- Produces:
  - `LlmErrorType.SSRF_BLOCKED`
  - `SpanKind`: `CASE_ROOT`, `ATTEMPT`, `PROMPT_RENDER`, `TEMPLATE_RENDER`, `LLM_CALL`, `HTTP_CALL`, `RETRIEVAL`, `EVALUATOR`
  - `SpanStatus`: `OK`, `ERROR`
  - `TraceIdGenerator`: `static String newTraceId()` (32 lowercase hex chars), `static String newSpanId()` (16 lowercase hex chars)
  - `SpanDraft(String spanName, SpanKind spanKind, SpanStatus status, long startTimeMs, long endTimeMs, Long ttftMs, Integer inputTokens, Integer outputTokens, BigDecimal estimatedCostUsd, String inputPayload, String outputPayload, Map<String, Object> attributes, String errorType, String errorMessage)`
  - `TraceSpanEntity` mapped to `trace_span`
  - `TraceSpanMapper`: `List<TraceSpanEntity> findByTraceIdOrdered(@Param("traceId") String traceId)`, `int deleteByRunId(@Param("runId") Long runId)`
  - `EvalRunMapper.incrementTraceDropped(@Param("runId") Long runId, @Param("delta") int delta, @Param("now") LocalDateTime now)`
  - `TraceWriterProperties` (`queueCapacity=4096`, `batchSize=100`, `flushIntervalMs=500`, `syncFallbackOnFull=true`, `maxPayloadChars=32768`)

- [ ] **Step 1: Write failing test `TracePersistenceTest.java`**

```java
@SpringBootTest
@ActiveProfiles("test")
class TracePersistenceTest {
    @Autowired private TraceSpanMapper traceSpanMapper;
    @Autowired private EvalRunMapper evalRunMapper;

    @Test
    void generatesValidOtelHexIds() {
        assertThat(TraceIdGenerator.newTraceId()).matches("^[0-9a-f]{32}$");
        assertThat(TraceIdGenerator.newSpanId()).matches("^[0-9a-f]{16}$");
    }

    @Test
    void insertsQueriesOrdersAndDeletesTraceSpansByRunId() {
        // Insert root + child spans for same traceId, assert findByTraceIdOrdered returns parent before child
        // Assert deleteByRunId removes only target run's spans
    }

    @Test
    void incrementsTraceDroppedAndFlagsRunIncomplete() {
        // Insert eval_run with providerId = null (validates nullable provider_id for HTTP runs)
        // Call evalRunMapper.incrementTraceDropped(runId, 3, now)
        // Assert traceIncomplete == true and traceDroppedCount == 3
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test -Dtest=TracePersistenceTest`
Expected: FAIL (compilation error — classes not yet created).

- [ ] **Step 3: Implement `V5__init_trace_and_http_target.sql`, properties, enums, entities, and mappers**

Key schema rules in `V5__init_trace_and_http_target.sql` (MySQL 8 + H2 `MODE=MySQL` compatible):
- `ALTER TABLE eval_run MODIFY COLUMN provider_id BIGINT NULL;`
- `ALTER TABLE eval_run ADD COLUMN http_target_id BIGINT NULL;`
- `ALTER TABLE eval_run ADD COLUMN trace_incomplete BOOLEAN NOT NULL DEFAULT FALSE;`
- `ALTER TABLE eval_run ADD COLUMN trace_dropped_count INT NOT NULL DEFAULT 0;`
- `ALTER TABLE eval_result ADD COLUMN trace_id CHAR(32) NULL;`
- `CREATE INDEX idx_eval_result_trace_id ON eval_result (trace_id);`
- `CREATE TABLE http_target`: `id`, `name VARCHAR(128) NOT NULL UNIQUE`, `description VARCHAR(500)`, `url VARCHAR(1024) NOT NULL`, `http_method VARCHAR(16) NOT NULL`, `headers_ciphertext LONGTEXT`, `headers_masked_json TEXT`, `body_template LONGTEXT`, `response_json_path VARCHAR(255)`, `default_timeout_ms BIGINT NOT NULL DEFAULT 30000`, `created_at DATETIME NOT NULL`, `updated_at DATETIME NOT NULL`.
- `CREATE TABLE trace_span`: `id BIGINT AUTO_INCREMENT PRIMARY KEY`, `trace_id CHAR(32) NOT NULL`, `span_id CHAR(16) NOT NULL`, `parent_span_id CHAR(16)`, `run_id BIGINT NOT NULL`, `eval_result_id BIGINT NOT NULL`, `case_key VARCHAR(128) NOT NULL`, `attempt_no INT NOT NULL DEFAULT 0`, `span_name VARCHAR(128) NOT NULL`, `span_kind VARCHAR(32) NOT NULL`, `status VARCHAR(16) NOT NULL`, `start_time_ms BIGINT NOT NULL`, `end_time_ms BIGINT NOT NULL`, `duration_ms BIGINT NOT NULL`, `ttft_ms BIGINT`, `input_tokens INT`, `output_tokens INT`, `estimated_cost_usd DECIMAL(14, 6)`, `input_payload LONGTEXT`, `output_payload LONGTEXT`, `attributes_json LONGTEXT`, `error_type VARCHAR(64)`, `error_message VARCHAR(1000)`, `created_at DATETIME NOT NULL`.
- Separate indexes: `idx_trace_span_trace_time (trace_id, start_time_ms)`, `idx_trace_span_run (run_id)`, `idx_trace_span_result (eval_result_id, attempt_no, start_time_ms)`.
- Configure `tracedelta.trace.*` and `tracedelta.security.ssrf.*` defaults in `application.yml` and `application-test.yml` (in test profile, small flush interval like `50ms` for fast tests).

- [ ] **Step 4: Run test suite to verify pass**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: PASS (all M1/M2 tests + `TracePersistenceTest` green).

- [ ] **Step 5: Commit and push**

```bash
git add backend/src/main/resources/db/migration/V5__init_trace_and_http_target.sql backend/src/main/resources/application.yml backend/src/test/resources/application-test.yml backend/src/main/java/io/tracedelta/provider/llm/LlmErrorType.java backend/src/main/java/io/tracedelta/runner/EvalRunEntity.java backend/src/main/java/io/tracedelta/runner/EvalResultEntity.java backend/src/main/java/io/tracedelta/runner/EvalRunMapper.java backend/src/main/java/io/tracedelta/trace/ backend/src/test/java/io/tracedelta/trace/TracePersistenceTest.java
git commit -m "feat(trace): add V5 trace_span and http_target schema, entities, and TraceIdGenerator / 新增 V5 链路与 HTTP 目标表结构、实体及 TraceId 生成器"
git push -u origin feat/m3-trace-http-target
```

---

### Task 2: Sensitive Data Sanitizer & Bounded Async Batch Trace Writer (Backpressure + Fallback + Drop Accounting)

**Files:**
- Create: `backend/src/main/java/io/tracedelta/trace/SensitiveDataSanitizer.java`
- Create: `backend/src/main/java/io/tracedelta/trace/TraceWriterStats.java`
- Create: `backend/src/main/java/io/tracedelta/trace/TraceSpanWriter.java`
- Create: `backend/src/main/java/io/tracedelta/trace/BoundedAsyncTraceSpanWriter.java`
- Test: `backend/src/test/java/io/tracedelta/trace/SensitiveDataSanitizerTest.java`
- Test: `backend/src/test/java/io/tracedelta/trace/BoundedAsyncTraceSpanWriterTest.java`

**Interfaces:**
- Consumes: `TraceSpanEntity`, `TraceWriterProperties`, `JdbcTemplate`, `EvalRunMapper`, `ObjectMapper`.
- Produces:
  - `SensitiveDataSanitizer`:
    - `boolean isSensitiveKey(String key)` (case-insensitive check for `authorization`, `proxy-authorization`, `api-key`, `x-api-key`, `api_key`, `apikey`, `token`, `access_token`, `secret`, `password`, `cookie`, `set-cookie`)
    - `String sanitizeText(String raw, int maxChars)` (redacts `Bearer <tok>`, `Basic <b64>`, `sk-<tok>`, `api_key=...`, truncates over `maxChars`)
    - `String sanitizeJson(String rawJson, int maxChars)` (recursively redacts sensitive object keys and string values, falls back to `sanitizeText` if non-JSON)
    - `Map<String, String> maskHeaders(Map<String, String> headers)` (keeps non-sensitive values, masks sensitive header values with `[REDACTED]`)
    - `TraceSpanEntity sanitizeSpan(TraceSpanEntity span)` (returns sanitized copy/instance before enqueue)
  - `TraceSpanWriter`:
    - `void submit(TraceSpanEntity span)` (never throws)
    - `boolean flushAll(Duration timeout)` (best-effort wait for queue + active flush completion)
    - `TraceWriterStats stats()`
    - `int droppedCountForRun(Long runId)`

- [ ] **Step 1: Write failing tests `SensitiveDataSanitizerTest.java` & `BoundedAsyncTraceSpanWriterTest.java`**

Tests must assert:
- `SensitiveDataSanitizerTest` (Review Focus #3):
  - Redacts `Authorization: Bearer eyJabc123...`, `x-api-key`, `api_key`, `Cookie`, and inline `sk-proj-1234567890abcdef` in both JSON objects (nested maps/arrays) and plain text payloads.
  - Preserves non-sensitive fields (`Content-Type`, `gen_ai.request.model`, Chinese text) untouched.
  - Truncates payloads exceeding `maxChars` cleanly with `...[truncated]`.
- `BoundedAsyncTraceSpanWriterTest` (Review Focus #2):
  - Normal async path: submitting 250 spans with `batchSize=50` flushes all 250 into DB via batch insert and updates `flushedCount` and `batchCount`.
  - Queue-full sync fallback: with `queueCapacity=2`, paused background flusher, and `syncFallbackOnFull=true`, submitting 5 spans triggers synchronous fallback without dropping or throwing, and `syncFallbackCount >= 3`.
  - Queue-full + DB failure drop accounting: when queue is full AND batch/fallback DB write throws `DataAccessException`, `submit(...)` NEVER throws, increments `droppedCount`, records `droppedCountForRun(runId) > 0`, and invokes `evalRunMapper.incrementTraceDropped(runId, ...)`.
  - `@PreDestroy` / `close()` drains queued spans before shutdown.

- [ ] **Step 2: Run tests to verify failure**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test -Dtest=SensitiveDataSanitizerTest,BoundedAsyncTraceSpanWriterTest`
Expected: FAIL (classes not found).

- [ ] **Step 3: Implement `SensitiveDataSanitizer`, `TraceWriterStats`, `TraceSpanWriter`, `BoundedAsyncTraceSpanWriter`**

Design details for `BoundedAsyncTraceSpanWriter`:
- Uses `ArrayBlockingQueue<TraceSpanEntity>(properties.getQueueCapacity())`.
- Single daemon worker thread (`tracedelta-trace-flusher`) loops on `queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS)`, then `queue.drainTo(batch, batchSize - 1)`.
- Persists batch via `JdbcTemplate.batchUpdate(...)` (exact column list of `trace_span`).
- On `submit(span)`: sanitize first; if `!queue.offer(sanitized)`: if `syncFallbackOnFull`, try `persistBatch(List.of(sanitized))` synchronously and increment `syncFallbackCount`; if that fails (or fallback disabled), record drop (`droppedCount`, per-run counter, and best-effort `evalRunMapper.incrementTraceDropped(...)`).
- On async batch write failure: try single-row fallback for each span in batch (so one bad row never drops the whole batch); any span still failing is counted as dropped and marks its `runId` incomplete.
- Support package-private constructor/hook for injectable `BatchPersister` functional interface so unit tests can simulate DB failures deterministically without fragile mocks.

- [ ] **Step 4: Run test suite to verify pass**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: PASS.

- [ ] **Step 5: Commit and push**

```bash
git add backend/src/main/java/io/tracedelta/trace/ backend/src/test/java/io/tracedelta/trace/
git commit -m "feat(trace): add SensitiveDataSanitizer and bounded async batch TraceSpanWriter / 新增 Trace 自动脱敏器与有界异步批量写入器"
git push
```

---

### Task 3: Runner & PromptTarget Trace Instrumentation + Trace Query REST API

**Files:**
- Modify: `backend/src/main/java/io/tracedelta/target/TargetExecutionResult.java`
- Modify: `backend/src/main/java/io/tracedelta/target/PromptTarget.java`
- Modify: `backend/src/main/java/io/tracedelta/runner/EvalCaseWorker.java`
- Modify: `backend/src/main/java/io/tracedelta/runner/EvalRunExecutor.java`
- Modify: `backend/src/main/java/io/tracedelta/runner/EvalRunService.java`
- Modify: `backend/src/main/java/io/tracedelta/runner/EvalRunSummaryView.java`
- Modify: `backend/src/main/java/io/tracedelta/runner/EvalRunDetailView.java`
- Modify: `backend/src/main/java/io/tracedelta/runner/EvalResultView.java`
- Create: `backend/src/main/java/io/tracedelta/trace/TraceSpanView.java`
- Create: `backend/src/main/java/io/tracedelta/trace/TraceDetailView.java`
- Create: `backend/src/main/java/io/tracedelta/trace/TraceWriterStatsView.java`
- Create: `backend/src/main/java/io/tracedelta/trace/TraceQueryService.java`
- Create: `backend/src/main/java/io/tracedelta/trace/TraceController.java`
- Test: `backend/src/test/java/io/tracedelta/trace/TraceInstrumentationIntegrationTest.java`

**Interfaces:**
- Consumes: `TraceSpanWriter`, `TraceIdGenerator`, `SpanDraft`, `EvalRunService`, `EvalRunExecutor`, `EvalCaseWorker`, `PromptTarget`.
- Produces:
  - `TargetExecutionResult(LlmResponse response, List<SpanDraft> spans)` + backward-compatible `TargetExecutionResult(LlmResponse response)` constructor delegating with `List.of()`
  - `EvalResultView` adds `String traceId`
  - `EvalRunSummaryView` & `EvalRunDetailView` add `Long httpTargetId`, `Boolean traceIncomplete`, `Integer traceDroppedCount`
  - REST Endpoints:
    - `GET /api/runs/{runId}/results/{resultId}/trace` -> `ApiResponse<TraceDetailView>`
    - `GET /api/traces/{traceId}` -> `ApiResponse<TraceDetailView>`
    - `GET /api/traces/writer-stats` -> `ApiResponse<TraceWriterStatsView>`

- [ ] **Step 1: Write failing integration test `TraceInstrumentationIntegrationTest.java`**

Tests must assert (including Review Focus #2, #3, #5):
- Creating and completing a Mock `PROMPT` run generates a non-null 32-hex `traceId` on every `EvalResult`.
- `GET /api/runs/{runId}/results/{resultId}/trace` and `GET /api/traces/{traceId}` both return the full span hierarchy:
  - `eval.case` (`CASE_ROOT`, `parentSpanId == null`)
  - `eval.attempt` (`ATTEMPT`, `parentSpanId == caseRoot.spanId`)
  - `prompt.render` (`PROMPT_RENDER`, `parentSpanId == attempt.spanId`)
  - `gen_ai.chat` (`LLM_CALL`, `parentSpanId == attempt.spanId`) with OTel attributes `gen_ai.system`, `gen_ai.operation.name`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`.
- For a `mock-flaky` case that fails attempt #1 and succeeds attempt #2, the trace contains TWO `eval.attempt` spans (`attemptNo=1` with `status=ERROR` and `attemptNo=2` with `status=OK`) under the same `eval.case` root span!
- `GET /api/traces/writer-stats` returns valid stats with `flushedCount > 0`.
- Deleting the completed Run (`DELETE /api/runs/{id}`) transactionally removes all associated `trace_span` rows (`GET /api/traces/{traceId}` then returns 404).

- [ ] **Step 2: Run test to verify failure**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test -Dtest=TraceInstrumentationIntegrationTest`
Expected: FAIL (endpoints & traceId not wired yet).

- [ ] **Step 3: Wire Trace instrumentation across `PromptTarget`, `EvalCaseWorker`, `EvalRunExecutor`, `EvalRunService`, and create `TraceQueryService` & `TraceController`**

Key wiring rules:
- `EvalRunService.createAndStart`: assign `result.setTraceId(TraceIdGenerator.newTraceId())` for every `EvalResultEntity`; initialize `run.setTraceIncomplete(false)` and `run.setTraceDroppedCount(0)`.
- `EvalRunService.deleteRun`: call `traceSpanMapper.deleteByRunId(runId)` inside the existing `transactionTemplate` before deleting `attempt`/`result`/`run`.
- `PromptTarget.execute`: record `prompt.render` span (with rendered message count & input summary) and `gen_ai.chat` span (with OTel GenAI attributes, input/output payloads, TTFT, tokens, cost, and error if failed), returning them in `TargetExecutionResult(response, spans)`.
- `EvalCaseWorker.executeCase`: ensure `traceId` exists (fallback generate & persist if a legacy row has null `traceId`); open `caseRootSpanId` at case start, wrap each attempt with `attemptSpanId`, submit target child `SpanDraft`s with `parentSpanId = attemptSpanId`, close `attemptSpan` and `caseRootSpan`, and submit via `traceSpanWriter.submit(...)`.
- `EvalRunExecutor.finalizeRun`: invoke `traceSpanWriter.flushAll(Duration.ofSeconds(2))` before final stats aggregation, and sync `traceIncomplete` / `traceDroppedCount` from DB & writer so immediate client polls see persisted spans (Review Focus #5).

- [ ] **Step 4: Run full backend test suite to verify pass**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: PASS (all existing M1/M2 tests + `TraceInstrumentationIntegrationTest` green).

- [ ] **Step 5: Commit and push**

```bash
git add backend/src/main/java/io/tracedelta/target/ backend/src/main/java/io/tracedelta/runner/ backend/src/main/java/io/tracedelta/trace/ backend/src/test/java/io/tracedelta/trace/TraceInstrumentationIntegrationTest.java
git commit -m "feat(trace): instrument PromptTarget and runner with OTel spans and expose trace query API / 接入评测引擎 OTel 链路埋点与 Trace 查询接口"
git push
```

---

### Task 4: SSRF Guard, JSON-Safe HTTP Template Renderer & Jackson JSONPath Extractor

**Files:**
- Create: `backend/src/main/java/io/tracedelta/target/http/SsrfProperties.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/HostResolver.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/SsrfGuard.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/HttpRequestTemplateRenderer.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/JsonPathExtractor.java`
- Test: `backend/src/test/java/io/tracedelta/target/http/SsrfGuardTest.java`
- Test: `backend/src/test/java/io/tracedelta/target/http/HttpRequestTemplateRendererTest.java`
- Test: `backend/src/test/java/io/tracedelta/target/http/JsonPathExtractorTest.java`

**Interfaces:**
- Consumes: `ObjectMapper`, `tracedelta.security.ssrf.*` config (`blockPrivateAddresses=true`, `allowedHosts=[]`).
- Produces:
  - `SsrfGuard`:
    - `URI validateAndResolve(String rawUrl)` (throws `BizException(VALIDATION_ERROR, ...)` or dedicated `SsrfBlockedException` — let's create `SsrfBlockedException extends RuntimeException` so `HttpTargetService` maps it to HTTP 400 validation error and `HttpTarget.execute` maps it to non-retryable `LlmErrorType.SSRF_BLOCKED`!)
  - `HttpRequestTemplateRenderer`:
    - `String renderBody(String bodyTemplate, String inputJson)` (JSON-escapes values substituted inside JSON string literals)
  - `JsonPathExtractor`:
    - `Optional<String> extract(String responseBody, String jsonPath)` (supports `$`, `$.a.b`, `$.choices[0].message.content`, `$['key-name'][1]`)

- [ ] **Step 1: Write failing tests `SsrfGuardTest.java`, `HttpRequestTemplateRendererTest.java`, `JsonPathExtractorTest.java`**

Tests must assert:
- `SsrfGuardTest` (Review Focus #1):
  - Rejects `file:///etc/passwd`, `ftp://example.com`, `gopher://127.0.0.1:6379`, and URLs with userinfo `https://user:pass@example.com`.
  - Rejects loopback (`127.0.0.1`, `localhost`, `[::1]`), RFC1918 private (`10.0.0.1`, `172.16.0.1`, `192.168.1.1`), link-local / cloud metadata (`169.254.169.254`, `[fe80::1]`), unspecified (`0.0.0.0`), multicast (`224.0.0.1`), CGNAT (`100.64.0.1`), IPv6 ULA (`[fc00::1]`, `[fd12::1]`), and IPv4-mapped IPv6 (`[::ffff:127.0.0.1]`, `[::ffff:169.254.169.254]`).
  - Rejects a hostname whose DNS resolves to a mix of public + private IPs (if ANY resolved IP is private, block!).
  - Allows public IP (`93.184.216.34`) over `http`/`https`.
  - Allows `127.0.0.1` or `localhost` ONLY when explicitly listed in `SsrfProperties.allowedHosts`.
- `HttpRequestTemplateRendererTest` (Review Focus #4):
  - Substitutes `{{question}}` containing `"quoted" text\nwith\\slash` inside `{"query": "{{question}}"}` and result parses as valid JSON with exact round-trip value!
  - Substitutes nested keys `{{user.name}}` and missing keys as empty string.
  - Default blank template renders `{"question": "<escaped question>"}` or preserves empty body for `GET`.
- `JsonPathExtractorTest` (Review Focus #4):
  - Extracts `$.data.answer`, `$.choices[0].message.content`, `$['x-output'][1].text`, numeric/boolean leaf values as string, and `$` as full body.
  - Returns `Optional.empty()` cleanly for missing path, out-of-bounds array index, or invalid JSON without throwing NPE.

- [ ] **Step 2: Run tests to verify failure**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test -Dtest=SsrfGuardTest,HttpRequestTemplateRendererTest,JsonPathExtractorTest`
Expected: FAIL (classes not found).

- [ ] **Step 3: Implement `SsrfProperties`, `HostResolver`, `SsrfBlockedException`, `SsrfGuard`, `HttpRequestTemplateRenderer`, `JsonPathExtractor`**

Key implementation rules:
- `SsrfGuard` checks IPv4 byte prefixes explicitly (including extracting the 4-byte IPv4 payload from `::ffff:a.b.c.d` mapped IPv6 addresses) and IPv6 ULA (`(bytes[0] & 0xfe) == 0xfc`).
- `HttpRequestTemplateRenderer` scans JSON string quotes properly (or uses Jackson's `JsonStringEncoder.getInstance().quoteAsString(value)`) when a `{{var}}` placeholder appears inside quotes.

- [ ] **Step 4: Run test suite to verify pass**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: PASS.

- [ ] **Step 5: Commit and push**

```bash
git add backend/src/main/java/io/tracedelta/target/http/ backend/src/test/java/io/tracedelta/target/http/
git commit -m "feat(target): add SsrfGuard, JSON-safe HttpRequestTemplateRenderer, and JsonPathExtractor / 新增 SSRF 防护、JSON 安全模板渲染与 JSONPath 提取器"
git push
```

---

### Task 5: `HttpTarget` CRUD/Smoke-Test API, `HttpTarget` Strategy & Dual-Target `EvalRun` Support

**Files:**
- Modify: `backend/src/main/java/io/tracedelta/target/TargetType.java`
- Modify: `backend/src/main/java/io/tracedelta/target/TargetConfigSnapshot.java`
- Modify: `backend/src/main/java/io/tracedelta/runner/CreateEvalRunRequest.java`
- Modify: `backend/src/main/java/io/tracedelta/runner/EvalRunService.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/HttpTargetEntity.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/HttpTargetMapper.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/HttpTargetUpsertRequest.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/HttpTargetView.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/HttpTargetSmokeTestRequest.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/HttpTargetSmokeTestView.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/HttpTargetService.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/HttpTargetController.java`
- Create: `backend/src/main/java/io/tracedelta/target/http/HttpTarget.java`
- Test: `backend/src/test/java/io/tracedelta/target/http/HttpTargetControllerTest.java`
- Test: `backend/src/test/java/io/tracedelta/target/http/HttpTargetEvalRunIntegrationTest.java`

**Interfaces:**
- Consumes: `AesGcmEncryptor`, `SensitiveDataSanitizer`, `SsrfGuard`, `HttpRequestTemplateRenderer`, `JsonPathExtractor`, `EvalTarget`, `TargetRegistry`, `EvalRunService`, `TraceQueryService`.
- Produces:
  - `TargetType.HTTP`
  - `HttpTarget.rateLimitKey(config)` -> `"http_target:" + config.httpTargetId()`
  - `HttpTarget` REST API (`/api/http-targets`):
    - `GET /api/http-targets` -> `ApiResponse<List<HttpTargetView>>`
    - `GET /api/http-targets/{id}` -> `ApiResponse<HttpTargetView>`
    - `POST /api/http-targets` -> `ApiResponse<HttpTargetView>`
    - `PUT /api/http-targets/{id}` -> `ApiResponse<HttpTargetView>`
    - `DELETE /api/http-targets/{id}` -> `ApiResponse<Void>`
    - `POST /api/http-targets/{id}/test` -> `ApiResponse<HttpTargetSmokeTestView>`
  - `CreateEvalRunRequest` accepts either `targetType=PROMPT` (requires `providerId`) OR `targetType=HTTP` (requires `httpTargetId`).

- [ ] **Step 1: Write failing tests `HttpTargetControllerTest.java` & `HttpTargetEvalRunIntegrationTest.java`**

Tests must assert (using OkHttp `MockWebServer` on `127.0.0.1` with explicit test whitelist toggle or custom `SsrfProperties` test bean):
- `HttpTargetControllerTest` (Review Focus #1 & #3):
  - Rejects creating an HTTP target pointing to `http://169.254.169.254/latest/meta-data/` with HTTP 400.
  - Encrypts `headers` (`Authorization: Bearer secret-xyz`, `X-Tenant: acme`) at rest in `http_target.headers_ciphertext` (plaintext secret NOT stored in `headers_ciphertext` or `headers_masked_json`); API response masks `Authorization` as `[REDACTED]` while keeping `X-Tenant: acme`.
  - Updating target with `headers == null` preserves existing encrypted headers; deleting removes target cleanly.
  - `POST /api/http-targets/{id}/test` invokes `MockWebServer`, extracts `$.data.reply`, and returns status code, latency, extracted output, and sanitized response preview.
- `HttpTargetEvalRunIntegrationTest` (Review Focus #1, #3, #4):
  - Runs a full 3-case `TargetType.HTTP` `EvalRun` against `MockWebServer`:
    - Sends JSON-rendered body + decrypted `Authorization` header to `MockWebServer`.
    - Extracts `$.data.answer` via `JsonPathExtractor` into `EvalResult.outputText`.
    - Freezes sanitized `TargetConfigSnapshot` on `eval_run` containing NO plaintext `Authorization` token.
    - Records outer `HTTP_CALL` Span with `http.request.method`, `http.response.status_code`, `url.full`, and `[REDACTED]` headers in `trace_span` (verified via `GET /api/runs/{runId}/results/{resultId}/trace`).
  - Redirect SSRF guard: when `MockWebServer` returns `302 Found` with `Location: http://169.254.169.254/meta`, `HttpTarget` does NOT follow the redirect and marks the case non-retryable `EXEC_FAILED` (`SSRF_BLOCKED`) with `attemptCount == 1`!
  - Unmatched JSONPath returns non-retryable `MALFORMED_RESPONSE` (`attemptCount == 1`).
  - Rate-limit 429 from `MockWebServer` triggers retry (`attemptCount == 2`) and succeeds on second attempt.

- [ ] **Step 2: Run tests to verify failure**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test -Dtest=HttpTargetControllerTest,HttpTargetEvalRunIntegrationTest`
Expected: FAIL (classes/endpoints not yet created).

- [ ] **Step 3: Implement `HttpTarget` persistence, service, controller, `EvalTarget` strategy, and `EvalRunService` dual-target support**

Key implementation details:
- `HttpTarget` uses a shared JDK `HttpClient` built with `.followRedirects(HttpClient.Redirect.NEVER)` and `.connectTimeout(Duration.ofSeconds(10))` (closed cleanly on `@PreDestroy`).
- On `3xx` response: immediately return `LlmResponse.failure(elapsed, LlmErrorType.SSRF_BLOCKED, "Redirect blocked by SSRF policy (HTTP " + status + ")")` without following.
- On `429`: return `LlmErrorType.RATE_LIMITED` (retryable); on `5xx` or network error: `LlmErrorType.HTTP_ERROR` (retryable); on `4xx` (other than 429): `LlmErrorType.HTTP_ERROR` with `"Upstream HTTP status " + status` (non-retryable by `RetryPolicy`); on timeout: `LlmErrorType.TIMEOUT` (retryable); on missing JSONPath: `LlmErrorType.MALFORMED_RESPONSE` (non-retryable).
- Emit `TEMPLATE_RENDER` (for body rendering when applicable) + outer `HTTP_CALL` `SpanDraft` with sanitized request headers/body & response preview.
- In `EvalRunService.createAndStart`: validate `providerId` for `PROMPT` and `httpTargetId` for `HTTP`; for `HTTP` runs, set `run.setProviderId(null)`, `run.setHttpTargetId(httpTarget.getId())`, `run.setProviderNameSnapshot(httpTarget.getName())`, `run.setModelSnapshot(httpTarget.getHttpMethod() + " " + summarizeUrlHostPath(httpTarget.getUrl()))`, and freeze sanitized `TargetConfigSnapshot`.

- [ ] **Step 4: Run full backend test suite to verify pass**

Run: `JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test`
Expected: PASS.

- [ ] **Step 5: Commit and push**

```bash
git add backend/src/main/java/io/tracedelta/target/ backend/src/main/java/io/tracedelta/runner/ backend/src/test/java/io/tracedelta/target/http/
git commit -m "feat(target): implement HttpTarget CRUD, smoke test, SSRF-guarded execution, and dual-target eval runs / 实现 HttpTarget 管理、冒烟测试、SSRF 防护调用与双目标评测接入"
git push
```

---

### Task 6: Frontend M3 — HTTP Targets Console, Dual-Target Run Modal, Trace Waterfall Replay & Docs Sync

**Files:**
- Create: `frontend/src/api/httpTarget.ts`
- Create: `frontend/src/api/trace.ts`
- Create: `frontend/src/components/TraceWaterfall.vue`
- Create: `frontend/src/views/HttpTargetsView.vue`
- Create: `frontend/src/views/TraceDetailView.vue`
- Modify: `frontend/src/api/run.ts`
- Modify: `frontend/src/stores/consoleData.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/MainLayout.vue`
- Modify: `frontend/src/views/RunsView.vue`
- Modify: `frontend/src/views/RunDetailView.vue`
- Modify: `DESIGN.md`
- Modify: `PRODUCT.md`

**Interfaces:**
- Consumes: `/api/http-targets*`, `/api/runs*`, `/api/runs/{runId}/results/{resultId}/trace`, `/api/traces/{traceId}`.
- Produces:
  - Sidebar navigation: `数据集` (`/datasets`), `模型渠道` (`/providers`), `外部接口` (`/http-targets`), `评测任务` (`/runs`).
  - Route `/traces/:traceId` (`trace-detail`, breadcrumb `评测任务 / Trace <shortId>`).
  - `TraceWaterfall.vue` accepting `trace: TraceDetail` and rendering:
    - Hierarchical span tree with relative timeline bars (`tabular-nums` offset + duration + TTFT marker if present)
    - Interactive span selection displaying OTel attributes table, desensitized input/output payloads (with copy button), and error banner
    - Honest incomplete-trace warning if `trace.traceIncomplete === true`
    - Full Light / Dark / System theme support and keyboard navigation (`Tab` / `Enter` on span rows).

- [ ] **Step 1: Add TypeScript API clients (`api/httpTarget.ts`, `api/trace.ts`), extend `api/run.ts` and `stores/consoleData.ts`**

Match exact backend DTO field names for `HttpTargetView`, `HttpTargetUpsertRequest`, `HttpTargetSmokeTestView`, `TraceDetailView`, `TraceSpanView`, `EvalRunDetail`, and `EvalResultItem`.

- [ ] **Step 2: Build `components/TraceWaterfall.vue` and `views/TraceDetailView.vue`**

Follow `DESIGN.md` Monochrome Precision Console:
- No flashy rainbow bars; use neutral Zinc bar fills with subtle kind badges (`CASE_ROOT`, `ATTEMPT`, `PROMPT_RENDER`, `TEMPLATE_RENDER`, `LLM_CALL`, `HTTP_CALL`) and semantic error accent (`--td-danger-*`) only when `span.status === 'ERROR'`.
- Show exact `startOffsetMs`, `durationMs`, `ttftMs`, `inputTokens/outputTokens`, and `estimatedCostUsd` in `mono` (`tabular-nums`).
- Span detail panel shows structured OTel attributes (`gen_ai.*`, `http.*`) + pretty-printed JSON/text payloads with `[REDACTED]` visibly intact.

- [ ] **Step 3: Build `views/HttpTargetsView.vue` and wire routes & sidebar in `router/index.ts` and `layouts/MainLayout.vue`**

Features in `HttpTargetsView.vue`:
- Compact table listing Name, Method + URL, JSONPath, Timeout, masked Header count, Updated At, Smoke Test button, Edit, Delete.
- Create/Edit modal with Method selector (`POST`/`GET`/`PUT`/`PATCH`), URL input (with concise SSRF protection hint), Headers key-value editor (marks sensitive keys like `Authorization`/`X-Api-Key` as encrypted at rest; on edit shows masked preview and leaves unchanged if untouched), JSON Request Body template (`{{question}}`), Response JSONPath (`$.data.answer`), Timeout.
- Single Smoke Test dialog allowing custom sample JSON input (default `{"question": "你好，请介绍一下 TraceDelta"}`), showing real HTTP status, latency, extracted output, raw preview, or SSRF block error.

- [ ] **Step 4: Upgrade `RunsView.vue` and `RunDetailView.vue` for HTTP targets & Trace waterfall**

- `RunsView.vue`: add segmented target type switch (`Prompt 目标` / `HTTP 目标`) in Create Run dialog; when `HTTP` is selected, show HTTP Target dropdown + URL/JSONPath preview and omit Provider/Prompt fields.
- `RunDetailView.vue`:
  - Render `traceIncomplete` honest warning banner when `run.traceIncomplete` is true (`共丢弃 run.traceDroppedCount 条诊断 Span，不影响用例评测结果`).
  - Upgrade Case Inspector drawer with segmented switch (`Trace 瀑布图` / `EvalAttempt 时间线`) + "全屏回放" link to `/traces/${selectedResult.traceId}` (only when `traceId` is present).

- [ ] **Step 5: Update `DESIGN.md` and `PRODUCT.md` to sync M1 + M2 + M3 scope**

Document the new `外部接口（HttpTarget）` view, dual-target Run modal, and `Trace 瀑布图` replay view truthfully, keeping M4–M5 clearly marked as future milestones.

- [ ] **Step 6: Verify frontend build and backend full test suite**

Run:
```bash
npm --prefix frontend run build
JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH" /opt/homebrew/bin/mvn -f backend/pom.xml test
```
Expected: `vue-tsc --noEmit && vite build` exits 0 with zero type/build errors, and all backend JUnit tests pass.

- [ ] **Step 7: Commit and push**

```bash
git add frontend/ DESIGN.md PRODUCT.md
git commit -m "feat(frontend): add HttpTargets console, dual-target run creation, and Trace waterfall replay / 新增外部接口管理、双目标评测创建与 Trace 瀑布图回放"
git push
```

---

## Plan Self-Review Checklist

- **Spec coverage:**
  - OTel GenAI/HTTP Trace/Span hierarchy → Tasks 1, 3, 5
  - Bounded async batch writer + backpressure sync fallback + drop accounting + `trace_incomplete` → Tasks 1, 2, 3
  - Automatic secret sanitization (`Authorization`, `api-key`, `Bearer`, `sk-...`) → Task 2
  - `HttpTarget` (URL, method, encrypted headers, `{{var}}` JSON template, JSONPath, timeout, `http_target:<id>` rate limit, outer HTTP Span) → Tasks 4, 5
  - SSRF guard (private/loopback/link-local/metadata/CGNAT/ULA/IPv4-mapped IPv6/non-HTTP/no-redirect + dev whitelist) → Tasks 4, 5
  - Frontend Trace waterfall + HttpTarget entry + Run dual-target modal → Task 6
- **Type consistency:** `TraceSpanEntity`, `SpanDraft`, `TraceSpanWriter`, `TargetConfigSnapshot`, `EvalRunDetailView`, `EvalResultView`, `HttpTargetView`, `TraceDetailView` names and field contracts match across all 6 tasks.
- **Review Focus coverage:** All 5 failure modes are explicitly pinned by automated JUnit / MockMvc / MockWebServer tests in Tasks 2, 3, 4, and 5.
