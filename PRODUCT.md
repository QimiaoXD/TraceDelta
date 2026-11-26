# TraceDelta — Product Context

## Product Summary
TraceDelta is a lightweight, recoverable, reproducible, and traceable automated quality assurance platform for enterprise Java AI applications (RAG / Agent / LLM endpoints). It turns ad-hoc LLM prompt checking into an engineering loop: immutable dataset snapshots, concurrent evaluation scheduling, execution trace recording, multi-dimensional regression diffing, and quality gates.

## Audience & Usage Scene
- **Primary users:** Java backend engineers, AI application engineers, and QA/evaluation reviewers inspecting dataset versions, provider configurations, batch evaluation runs, regression diffs, and execution traces.
- **Usage environment:** Long desktop sessions in both bright office lighting (and thesis/demo screenshots) and dim evening engineering workspaces — requiring first-class Light, Dark, and System-following (`prefers-color-scheme`) themes.
- **Primary mode:** **Operate** (precision engineering workbench). Clarity, scanability, tabular alignment, honest state semantics, and low visual friction take priority over marketing theatrics.

## Platform
web

## Core Surfaces (M1 → M5)
1. **Sign-in (`/login`)**: JWT operator login for the console.
2. **Datasets & Immutable Versions (`/datasets`, `/datasets/:id`)**: Create datasets, import JSON/JSONL/CSV into append-only immutable version snapshots, inspect case inputs, assertions, reference answers, and canonical SHA-256 `case_hash`.
3. **Providers & Deterministic Mock (`/providers`)**: Manage encrypted OpenAI-compatible providers (masked API keys only) and zero-cost deterministic Mock profiles (`mock-good`, `mock-sloppy`, `mock-slow`, `mock-flaky`), with interactive smoke testing.
4. **Upcoming (M2–M5)**: Evaluation Run Dashboard, 5D Regression Diff, Trace Waterfall Replay, Quality Gate.

## Constraints
- Frontend stack: Vue 3 + TypeScript + Vite + Pinia + Vue Router + Element Plus (plus ECharts in later milestones).
- Chinese UI copy (`zh-CN`) with crisp technical English terms (`case_key`, `case_hash`, `JSONL`, `Mock`, `Tokens`, `ms`, `API Key`).
- Zero fake performance claims or fabricated metrics in the UI.
- Full keyboard accessibility and contrast ≥ 4.5:1 across both Light and Dark themes.
