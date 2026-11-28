# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users
- **Primary reviewers (demo & evaluation):** Engineering hiring interviewers and undergraduate thesis defense judges inspecting TraceDelta's engineering depth, recoverability, and regression diagnostics.
- **Primary operator:** The author using TraceDelta as a personal multi-model benchmarking and automated regression workbench for Java AI applications (RAG / Agent / LLM endpoints).

## Product Purpose
TraceDelta turns ad-hoc prompt checking into a repeatable engineering loop—immutable dataset version snapshots, concurrent evaluation scheduling with recovery semantics, execution trace recording, five-dimensional regression diffing, and quality gates—so AI application changes can be verified with the same rigor as running unit tests. Success means completing the full loop (`create dataset -> evaluate -> compare runs -> trace replay -> quality gate`) locally via `docker compose up` in zero-cost Mock mode with honest, reproducible evidence.

## Positioning
Unlike general-purpose cloud LLM observability tools or heavyweight microservice suites, TraceDelta is a single-node, pure Java modular monolith (`io.tracedelta.*`, no ClickHouse/ES/external vector DB) focused on four engineering guarantees:
1. **Recoverable scheduling with honest boundaries:** Java 21 virtual threads, three-level concurrency control, `EvalAttempt` tracking, idempotent result persistence, and explicit At-Least-Once disclosure for upstream LLM calls.
2. **Strict reproducibility:** Immutable append-only dataset versions with canonical SHA-256 `case_hash` fairness checks and frozen run snapshots.
3. **Rule-based diagnosability:** Five-dimensional regression diff (functional, quality, performance, cost, reliability) linked directly to OTel-aligned Trace/Span evidence.
4. **Zero-cost deterministic verification:** Programmable deterministic Mock profiles (`mock-good`, `mock-sloppy`, `mock-slow`, `mock-flaky`) that validate retry, rate-limiting, and regression detection without API spend.

## Operating Context
- Local single-machine desktop workflow (macOS / Linux) backed by Docker Compose (MySQL 8 + Redis 7), Spring Boot 3 / Java 21 backend (`:8080`), and Vue 3 + Vite console (`:5173`).
- Two primary viewing contexts: bright daytime lighting (including screenshots for thesis documents, defense slides, and README) and dim evening engineering sessions.
- Console-first application (JWT operator sign-in into authenticated workbench views; no public marketing site required unless later requested).

## Capabilities and Constraints
- **Implemented (M1–M3):** JWT operator login with seeded admin; AES-GCM encrypted provider & HTTP-target secret storage (`TRACEDELTA_MASTER_KEY` from local `.env`); immutable dataset versions with JSON/JSONL/CSV import and canonical `case_hash`; deterministic Mock (4 profiles) and OpenAI-compatible client with single-call smoke testing; Java 21 virtual-thread concurrent evaluation scheduler with three-level concurrency/rate limiting (global + run semaphores + Redis Lua token bucket with local fallback), exponential backoff retries, `EvalAttempt` state machine & startup crash recovery with At-Least-Once disclosure, run-level cost/token budget circuit breaker; OTel-aligned Trace/Span bounded async batch writer with backpressure fallback, drop accounting (`traceIncomplete`), automatic secret sanitization, and `/api/traces` replay API; SSRF-guarded `HttpTarget` CRUD, smoke test, JSON-safe templating, and Jackson JSONPath extraction; Vue 3 2-second polling evaluation console, HTTP targets view, and Trace waterfall replay.
- **Planned (M4–M5):** Rule assertions + 5D Regression Diff with fairness check (M4); Quality Gate + L2 offline rescore + built-in Chinese RAG benchmark + LLM-as-a-Judge (M5).
- **Technical constraints:** Vue 3 + TypeScript + Vite + Pinia + Vue Router + Element Plus (+ ECharts for upcoming charts). Pure Java modular monolith backend; Flyway migrations compatible with MySQL 8 and H2.
- **Copy & terminology:** Simplified Chinese (`zh-CN`) interface copy paired with standard technical English identifiers (`case_key`, `case_hash`, `JSONL`, `Mock`, `Tokens`, `ms`, `API Key`).
- **Appearance constraint:** First-class Light, Dark, and System-following (`prefers-color-scheme`) appearance switching must be maintained across all current and future views.
- **Undecided facts:** Exact thesis formatting/word-count template from university; whether P2 items (SSE push, `PAUSED` state, `AgentTarget`) enter v1 depends on milestone schedule.

## Brand Commitments
- **Product name:** `TraceDelta` (Trace execution lineage + Delta regression difference).
- **Tone:** Calm, restrained, engineering-first, and honest about system boundaries—never hype, never marketing fluff.
- **Visual alignment:** Bound to the restrained monochrome developer console language documented in `DESIGN.md` (clear hierarchy, verifiable engineering evidence, and Apple HIG-grade platform clarity and feedback).

## Evidence on Hand
- Design specification: `docs/superpowers/specs/2026-11-26-tracedelta-design.md`
- M1 implementation plan: `docs/superpowers/plans/2026-11-26-m1-foundation-dataset-mock.md`
- Working backend test suite (`backend/src/test/java/io/tracedelta/...`) and Vue 3 console (`frontend/`).
- **Explicit absences (must never be fabricated):** Benchmark throughput numbers, latency improvements, recovery timing figures, test coverage percentages, and real-model experiment scores do not exist yet and must only be reported after real empirical measurement in M5.

## Product Principles
1. **Honesty over theatrics:** Never fabricate metrics, fake statuses, or impossible consistency claims (such as external LLM Exactly-Once).
2. **Zero-cost verifiability:** Every core workflow must be runnable and demonstrable end-to-end with deterministic Mock providers before spending API budget.
3. **Quiet operational clarity:** Keep interfaces uncluttered so datasets, hashes, diffs, and traces remain the focal point.
4. **Reproducibility by default:** Treat dataset versions and run configurations as immutable snapshots.
5. **Depth in core engines:** Prioritize solid concurrency control, recovery semantics, and diagnostic clarity over feature sprawl.

## Accessibility & Inclusion
- Maintain WCAG AA contrast (≥ 4.5:1 for body text, ≥ 3:1 for large text) across both Light and Dark themes.
- Preserve full keyboard focusability, visible focus rings, and semantic `aria-pressed` / `role` states on interactive controls.
