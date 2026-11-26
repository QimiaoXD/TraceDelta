---
name: TraceDelta
description: Precision Light & Dark workbench design system for automated LLM regression testing and trace diagnostics.
colors:
  cobalt-signal: "#1d4ed8"
  cobalt-signal-hover: "#1e40af"
  cobalt-dark-signal: "#5b9bff"
  precision-paper: "#f7f8fa"
  paper-rail: "#f0f2f6"
  pure-surface: "#ffffff"
  hairline-light: "#e4e8f0"
  ink-primary: "#111827"
  ink-secondary: "#475467"
  carbon-canvas: "#0b0e14"
  carbon-rail: "#0f131c"
  carbon-surface: "#131823"
  hairline-dark: "#202838"
  chalk-primary: "#eef2f8"
  chalk-secondary: "#a8b5c9"
  emerald-pass: "#047857"
  emerald-pass-dark: "#34d399"
  amber-warn: "#b45309"
  crimson-fail: "#be123c"
typography:
  headline:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'PingFang SC', 'Noto Sans SC', sans-serif"
    fontSize: "1.25rem"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "-0.02em"
  title:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'PingFang SC', 'Noto Sans SC', sans-serif"
    fontSize: "0.9375rem"
    fontWeight: 600
    lineHeight: 1.4
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'PingFang SC', 'Noto Sans SC', sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "'JetBrains Mono', 'SF Mono', ui-monospace, SFMono-Regular, Menlo, monospace"
    fontSize: "0.75rem"
    fontWeight: 500
    lineHeight: 1.5
rounded:
  xs: "4px"
  sm: "6px"
  md: "8px"
  lg: "12px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
components:
  button-primary:
    backgroundColor: "{colors.cobalt-signal}"
    textColor: "{colors.pure-surface}"
    rounded: "{rounded.sm}"
    padding: "8px 16px"
  button-primary-hover:
    backgroundColor: "{colors.cobalt-signal-hover}"
  surface-panel:
    backgroundColor: "{colors.pure-surface}"
    rounded: "{rounded.md}"
  pill-success:
    backgroundColor: "#ecfdf5"
    textColor: "{colors.emerald-pass}"
    rounded: "{rounded.xs}"
    padding: "2px 8px"
---

# Design System: TraceDelta

## Overview

**Creative North Star: "The Precision Bench"**

TraceDelta is built as a calibrated engineering bench for evaluating and diagnosing AI applications. Every screen serves an Operate workflow: engineers scanning dataset snapshots, verifying canonical SHA-256 hashes, configuring encrypted model providers, and inspecting smoke-test telemetry. The interface stays quiet, border-defined, and high-contrast so that real data—diffs, assertions, latency numbers, and execution traces—carries the visual weight.

Both Light ("Precision Paper") and Dark ("Carbon Bench") themes are first-class citizens, paired with live `prefers-color-scheme` system synchronization and a pre-hydration `<head>` script that prevents flash-of-wrong-theme on reload. Visual hierarchy relies on subtle tonal layering (rail vs. canvas vs. surface) and crisp 1px hairlines rather than drop shadows or decorative gradients.

**Key Characteristics:**
- Dual first-class Light, Dark, and System-following (`light | system | dark`) theme architecture.
- Flat, hairline-bordered work surfaces (`--td-bg-surface`) resting on a cooler canvas (`--td-bg-canvas`) and side rail (`--td-bg-rail`).
- Single restrained Cobalt Signal accent reserved strictly for primary actions, active navigation, and keyboard focus rings.
- Tabular monospace alignment (`font-variant-numeric: tabular-nums`) on all IDs, hashes, version numbers, latencies, token counts, and timestamps.
- Unified 1.75px stroke inline SVG icon library (`TdIcon.vue`) with zero emoji or glyph substitutions.

## Colors

A restrained neutral workbench anchored by one Cobalt Signal accent and four calibrated semantic states across Light and Dark modes.

### Primary
- **Cobalt Signal** (`#1d4ed8` light / `#5b9bff` dark): Primary action buttons, active navigation indicators, interactive hash copy hover states, and keyboard focus rings.

### Neutral
- **Precision Paper** (`#f7f8fa`) & **Carbon Canvas** (`#0b0e14`): Main viewport backdrop behind data containers.
- **Paper Rail** (`#f0f2f6`) & **Carbon Rail** (`#0f131c`): Sidebar navigation and split-login technical showcase layer.
- **Pure Surface** (`#ffffff`) & **Carbon Surface** (`#131823`): Data tables, toolbars, version rails, and form containers.
- **Hairline Borders** (`#e4e8f0` light / `#202838` dark): Structural separation across layout shells and table rows.
- **Ink & Chalk Text** (`#111827` / `#475467` light; `#eef2f8` / `#a8b5c9` dark): Guarantees ≥ 4.5:1 body contrast in both themes.

### Semantic States
- **Emerald Pass** (`#047857` light / `#34d399` dark): `MOCK` provider availability, `SUCCEEDED` smoke tests, verified snapshots.
- **Amber Warn** (`#b45309` light / `#fbbf24` dark): Slow latency or flaky fault-injection warnings.
- **Crimson Fail** (`#be123c` light / `#fb7185` dark): Destructive actions, failed calls, and validation alerts.

**The One Voice Rule.** Cobalt Signal appears on ≤ 10% of any viewport—only primary actions, current selection, and focus states. Never use it as decorative fill.

## Typography

**Body & UI Font:** System sans stack (`-apple-system`, `SF Pro Text`, `PingFang SC`, `Noto Sans SC`, `Segoe UI`, sans-serif) for crisp CJK + Latin legibility without font-loading jank.
**Data / Code Font:** `JetBrains Mono`, `SF Mono`, `ui-monospace`, `Menlo`, monospace.

**Character:** Compact, fixed-rem Operate hierarchy (1.15–1.2 scale ratio) that balances dense data tables with clean breathing room in dialogs and drawers.

### Hierarchy
- **Headline** (`600`, `1.25rem` / `20px`, `1.3` line-height, `-0.02em` tracking): Page titles (`td-page-title`).
- **Title** (`600`, `0.9375rem` / `15px`, `1.4` line-height): Section titles, dialog headers, empty-state headings.
- **Body** (`400`, `0.875rem` / `14px`, `1.5` line-height, max `68ch` for prose): Table content, form labels, descriptions.
- **Label / Mono** (`500`, `0.75rem` / `12px`, `1.5` line-height, `tabular-nums`): `case_key`, SHA-256 hashes, timestamps, version badges, latency/token metrics.

**The Honest Mono Rule.** Use monospace strictly for code, JSON, hashes, keys, and numeric measurements—never as decorative styling for prose headings.

## Layout

- **Application Shell:** Collapsible 236px (64px collapsed) left rail paired with a 52px sticky top bar and scrollable main workspace (`max-width: 1380px`, `24px` desktop padding / `16px` mobile padding).
- **Responsive Breakpoints:**
  - `≤ 980px`: Dataset detail collapses from two-column (`268px` version rail + cases table) into a single column with an inline version `<el-select>`.
  - `≤ 960px`: Login view collapses from asymmetric 54/46 split into a focused single-column sign-in panel.
  - `≤ 860px`: Sidebar transitions into a slide-over drawer triggered from the top bar.
- **Spacing Rhythm:** `4px` base step (`4 / 8 / 12 / 16 / 20 / 24 / 32px`). Page sections separate by `20px`; toolbar controls group at `8px`.

## Elevation & Depth

Surfaces are flat by default; depth is established through three neutral tonal planes (rail → canvas → surface) and crisp `1px` borders. Offset shadows appear only on overlays (dialogs, drawers, dropdowns) and active segmented controls.

**The Flat-By-Default Rule.** Never nest cards inside cards. Tables and toolbars share one `.td-surface` container separated by a hairline divider.

## Shapes

- **Sharp-Practical Corners:** `4px` (`--td-radius-xs`) for badges, pills, and code chips; `6px` (`--td-radius-sm`) for buttons, inputs, nav items, and segmented controls; `8px` (`--td-radius-md`) for primary `.td-surface` containers; `12px` (`--td-radius-lg`) for modal dialogs.
- **Borders:** `1px solid` everywhere; no decorative left-side accent bars on rounded containers.

## Components

### Buttons
- **Shape:** Subtle rounded rectangle (`6px` radius), medium weight (`500`).
- **Primary:** Cobalt Signal fill with pure white label, `140ms` exponential ease-out hover shift.
- **Secondary / Link:** Hairline-bordered neutral surface or bare link action for inline table controls.

### Theme Switcher
- **Structure:** Compact 3-way segmented control (`浅色` / `自动` / `深色`) with `aria-pressed` state, mounted in the top bar and login corner. Persists `light | system | dark` in `localStorage('tracedelta_theme_mode')` and tracks OS `prefers-color-scheme` changes live.

### Status Pills
- **Style:** `4px` radius, tinted soft fill + matching hairline border + `6px` leading dot (`td-pill--success`, `td-pill--accent`, `td-pill--warning`, `td-pill--danger`, `td-pill--neutral`).

### Data Tables & Drawers
- **Tables:** Single-surface container with integrated search/filter toolbar, skeleton loading, actionable empty states, clickable rows, and tabular-aligned mono columns.
- **Drawers:** Used for deep inspection without leaving list context (Dataset Case JSON Inspector and Provider Smoke-Test Bench).

## Do's and Don'ts

### Do:
- **Do** declare every color through `--td-*` CSS custom properties so Light and Dark modes stay synchronized automatically.
- **Do** pair every data table with skeleton loading, actionable empty states, and filtered empty states.
- **Do** use `td-mono` and `tabular-nums` for `case_key`, `case_hash`, latency (`ms`), token counts, and timestamps.
- **Do** keep transitions crisp (`140ms–200ms`) and bound strictly to state feedback.

### Don't:
- **Don't** hardcode hex colors inside Vue components or mix Element Plus default blue with custom palette accents.
- **Don't** place eyebrow/kicker labels above page headings or nest `el-card` inside another card.
- **Don't** use emoji or raw Unicode arrows in place of `TdIcon.vue` SVG icons.
- **Don't** use gradient text, zero-offset neon glows, or decorative glassmorphism on Operate workbenches.
