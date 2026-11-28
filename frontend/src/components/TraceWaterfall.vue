<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { SpanKind, TraceDetail, TraceSpanItem } from '../api/trace'
import TdIcon from './TdIcon.vue'
import { copyText, prettyJson } from '../utils/format'
import { formatCostUsd, formatLatency } from '../utils/runFormat'

const props = withDefaults(
  defineProps<{
    trace: TraceDetail
    compact?: boolean
  }>(),
  {
    compact: false,
  },
)

interface WaterfallRow {
  span: TraceSpanItem
  depth: number
  offsetPct: number
  widthPct: number
  ttftPct: number | null
  relativeStartMs: number
}

const selectedSpanId = ref<string | null>(null)
const liveAnnouncement = ref('')

const rows = computed<WaterfallRow[]>(() => {
  const spans = props.trace.spans ?? []
  if (spans.length === 0) return []

  const byId = new Map<string, TraceSpanItem>()
  const childrenMap = new Map<string, TraceSpanItem[]>()
  for (const s of spans) {
    byId.set(s.spanId, s)
  }

  const roots: TraceSpanItem[] = []
  for (const s of spans) {
    if (s.parentSpanId && byId.has(s.parentSpanId)) {
      const list = childrenMap.get(s.parentSpanId) ?? []
      list.push(s)
      childrenMap.set(s.parentSpanId, list)
    } else {
      roots.push(s)
    }
  }

  const baseStart = props.trace.startTimeMs ?? Math.min(...spans.map((s) => s.startTimeMs))
  const totalSpanMs = Math.max(1, props.trace.totalDurationMs || 1)

  const ordered: WaterfallRow[] = []
  function walk(node: TraceSpanItem, depth: number) {
    const relStart = Math.max(0, node.startTimeMs - baseStart)
    const offsetPct = Math.min(98, (relStart / totalSpanMs) * 100)
    const rawWidthPct = (Math.max(0, node.durationMs) / totalSpanMs) * 100
    const widthPct = Math.min(100 - offsetPct, Math.max(1.5, rawWidthPct))
    const ttftPct =
      node.ttftMs !== null && node.ttftMs !== undefined && node.durationMs > 0
        ? Math.min(100, Math.max(0, (node.ttftMs / node.durationMs) * 100))
        : null

    ordered.push({
      span: node,
      depth,
      offsetPct,
      widthPct,
      ttftPct,
      relativeStartMs: relStart,
    })

    const children = childrenMap.get(node.spanId) ?? []
    for (const child of children) {
      walk(child, depth + 1)
    }
  }

  for (const r of roots) {
    walk(r, 0)
  }
  return ordered
})

watch(
  rows,
  (list) => {
    if (list.length === 0) {
      selectedSpanId.value = null
      return
    }
    if (!selectedSpanId.value || !list.some((r) => r.span.spanId === selectedSpanId.value)) {
      // Auto-select the most informative leaf span (LLM_CALL or HTTP_CALL) if available
      const leaf =
        list.find((r) => r.span.spanKind === 'LLM_CALL' || r.span.spanKind === 'HTTP_CALL') ??
        list[0]
      selectedSpanId.value = leaf.span.spanId
    }
  },
  { immediate: true },
)

const selectedSpan = computed<TraceSpanItem | null>(() => {
  if (!selectedSpanId.value) return null
  return rows.value.find((r) => r.span.spanId === selectedSpanId.value)?.span ?? null
})

const parsedAttributes = computed<[string, string][]>(() => {
  const raw = selectedSpan.value?.attributesJson
  if (!raw) return []
  try {
    const obj = JSON.parse(raw)
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return []
    return Object.entries(obj).map(([k, v]) => [
      k,
      typeof v === 'string' ? v : JSON.stringify(v),
    ])
  } catch {
    return []
  }
})

function kindLabel(kind: SpanKind): string {
  switch (kind) {
    case 'CASE_ROOT':
      return 'CASE'
    case 'ATTEMPT':
      return 'ATTEMPT'
    case 'PROMPT_RENDER':
      return 'PROMPT'
    case 'TEMPLATE_RENDER':
      return 'TEMPLATE'
    case 'LLM_CALL':
      return 'LLM'
    case 'HTTP_CALL':
      return 'HTTP'
    case 'RETRIEVAL':
      return 'RAG'
    case 'EVALUATOR':
      return 'SCORE'
  }
}

function formatPayload(raw: string | null): string {
  if (!raw) return ''
  const trimmed = raw.trim()
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    return prettyJson(trimmed)
  }
  return raw
}

async function copyPayload(text: string, label: string) {
  await copyText(text, `已复制${label}`)
  liveAnnouncement.value = `已复制${label}`
}
</script>

<template>
  <div class="wf-root" :class="{ 'wf-root--compact': compact }">
    <div class="sr-only" aria-live="polite">{{ liveAnnouncement }}</div>

    <div v-if="trace.traceIncomplete" class="wf-warning" role="status">
      <TdIcon name="alert-triangle" :size="15" />
      <span>
        该任务的诊断 Trace 存在背压丢弃（Trace 不完整），已落库的 Span 仍可正常查看，评测结果不受影响。
      </span>
    </div>

    <div v-if="rows.length === 0" class="wf-empty">
      <TdIcon name="layers" :size="16" />
      <span>尚无已落库的 Trace Span 记录（用例可能处于待执行、已跳过或为 M2 历史记录）。</span>
    </div>

    <div v-else class="wf-layout" :class="{ 'wf-layout--stacked': compact }">
      <!-- Timeline Tree -->
      <div class="wf-tree" role="listbox" aria-label="Trace Span 瀑布时间线">
        <div class="wf-tree-head mono">
          <span>Span 链路层级</span>
          <span>耗时时间轴（总计 {{ formatLatency(trace.totalDurationMs) }}）</span>
        </div>

        <button
          v-for="row in rows"
          :key="row.span.spanId"
          type="button"
          role="option"
          class="wf-row"
          :class="{
            'is-selected': selectedSpanId === row.span.spanId,
            'is-error': row.span.status === 'ERROR',
          }"
          :aria-selected="selectedSpanId === row.span.spanId"
          @click="selectedSpanId = row.span.spanId"
        >
          <div class="wf-row-name" :style="{ paddingLeft: `${8 + row.depth * 16}px` }">
            <span class="wf-kind mono">{{ kindLabel(row.span.spanKind) }}</span>
            <span class="wf-span-title mono" :title="row.span.spanName">
              {{ row.span.spanName }}
            </span>
            <TdIcon
              v-if="row.span.status === 'ERROR'"
              name="alert-circle"
              :size="12"
              class="wf-err-icon"
            />
          </div>

          <div class="wf-row-track">
            <div class="wf-bar-rail">
              <div
                class="wf-bar"
                :class="{
                  'wf-bar--error': row.span.status === 'ERROR',
                  'wf-bar--root': row.span.spanKind === 'CASE_ROOT',
                  'wf-bar--io': row.span.spanKind === 'LLM_CALL' || row.span.spanKind === 'HTTP_CALL',
                }"
                :style="{ left: `${row.offsetPct}%`, width: `${row.widthPct}%` }"
              >
                <span
                  v-if="row.ttftPct !== null"
                  class="wf-ttft-tick"
                  :style="{ left: `${row.ttftPct}%` }"
                  :title="`首字延迟 TTFT: ${row.span.ttftMs} ms`"
                />
              </div>
            </div>
            <span class="wf-dur mono">{{ row.span.durationMs }} ms</span>
          </div>
        </button>
      </div>

      <!-- Selected Span Detail Inspector -->
      <section v-if="selectedSpan" class="wf-inspector" aria-label="Span 详情检查器">
        <header class="wf-ins-head">
          <div class="wf-ins-title-wrap">
            <span class="wf-kind mono">{{ kindLabel(selectedSpan.spanKind) }}</span>
            <h4 class="wf-ins-title mono">{{ selectedSpan.spanName }}</h4>
            <span
              class="status-tag"
              :class="selectedSpan.status === 'ERROR' ? 'status-tag--danger' : 'status-tag--success'"
            >
              <TdIcon
                :name="selectedSpan.status === 'ERROR' ? 'alert-circle' : 'check-circle'"
                :size="11"
              />
              <span>{{ selectedSpan.status }}</span>
            </span>
          </div>
          <span class="mono wf-ins-id">span_id: {{ selectedSpan.spanId }}</span>
        </header>

        <div class="wf-meta-grid mono">
          <div><span class="wf-k">耗时：</span>{{ formatLatency(selectedSpan.durationMs) }}</div>
          <div v-if="selectedSpan.ttftMs !== null">
            <span class="wf-k">TTFT：</span>{{ formatLatency(selectedSpan.ttftMs) }}
          </div>
          <div v-if="selectedSpan.attemptNo > 0">
            <span class="wf-k">Attempt：</span>#{{ selectedSpan.attemptNo }}
          </div>
          <div
            v-if="
              (selectedSpan.inputTokens ?? 0) > 0 || (selectedSpan.outputTokens ?? 0) > 0
            "
          >
            <span class="wf-k">Tokens：</span>
            {{ selectedSpan.inputTokens ?? 0 }} / {{ selectedSpan.outputTokens ?? 0 }}
          </div>
          <div v-if="selectedSpan.estimatedCostUsd !== null && Number(selectedSpan.estimatedCostUsd) > 0">
            <span class="wf-k">估算费用：</span>{{ formatCostUsd(selectedSpan.estimatedCostUsd) }}
          </div>
        </div>

        <div v-if="selectedSpan.errorType" class="error-banner" role="alert">
          <span class="error-banner-body mono">
            <TdIcon name="alert-circle" :size="14" />
            <span>{{ selectedSpan.errorType }} — {{ selectedSpan.errorMessage }}</span>
          </span>
        </div>

        <div v-if="parsedAttributes.length > 0" class="wf-section">
          <div class="wf-sec-head">
            <h5>OTel 语义属性（已自动脱敏）</h5>
          </div>
          <div class="wf-attr-table mono">
            <div v-for="[k, v] in parsedAttributes" :key="k" class="wf-attr-row">
              <span class="wf-attr-key">{{ k }}</span>
              <span class="wf-attr-val">{{ v }}</span>
            </div>
          </div>
        </div>

        <div v-if="selectedSpan.inputPayload" class="wf-section">
          <div class="wf-sec-head">
            <h5>输入载荷（脱敏）</h5>
            <button
              type="button"
              class="btn btn--ghost btn--sm"
              @click="copyPayload(selectedSpan.inputPayload!, '输入载荷')"
            >
              <TdIcon name="copy" :size="12" />
              <span>复制</span>
            </button>
          </div>
          <pre class="code-block mono wf-code">{{ formatPayload(selectedSpan.inputPayload) }}</pre>
        </div>

        <div v-if="selectedSpan.outputPayload" class="wf-section">
          <div class="wf-sec-head">
            <h5>输出载荷（脱敏）</h5>
            <button
              type="button"
              class="btn btn--ghost btn--sm"
              @click="copyPayload(selectedSpan.outputPayload!, '输出载荷')"
            >
              <TdIcon name="copy" :size="12" />
              <span>复制</span>
            </button>
          </div>
          <pre class="code-block mono wf-code">{{ formatPayload(selectedSpan.outputPayload) }}</pre>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.wf-root {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.wf-warning {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--td-warning-border);
  background-color: var(--td-warning-bg);
  color: var(--td-warning-text);
  font-size: 12.5px;
}

.wf-empty {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 18px 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--td-border);
  background-color: var(--td-bg-subtle);
  color: var(--td-text-secondary);
  font-size: 13px;
}

.wf-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.wf-layout--stacked {
  grid-template-columns: 1fr;
}

.wf-tree {
  border: 1px solid var(--td-border);
  border-radius: var(--radius-md);
  background-color: var(--td-bg-surface);
  overflow: hidden;
}

.wf-tree-head {
  display: grid;
  grid-template-columns: minmax(170px, 1.1fr) minmax(140px, 1fr);
  gap: 8px;
  padding: 8px 12px;
  font-size: 11.5px;
  color: var(--td-text-secondary);
  background-color: var(--td-bg-subtle);
  border-bottom: 1px solid var(--td-border);
}

.wf-row {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(170px, 1.1fr) minmax(140px, 1fr);
  gap: 8px;
  align-items: center;
  padding: 9px 12px 9px 0;
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--td-border);
  color: var(--td-text-primary);
  text-align: left;
  cursor: pointer;
  transition: background-color var(--dur-fast) var(--ease);
}

.wf-row:last-child {
  border-bottom: 0;
}

.wf-row:hover {
  background-color: var(--td-bg-row-hover);
}

.wf-row.is-selected {
  background-color: var(--td-bg-subtle);
  box-shadow: inset 3px 0 0 var(--td-text-primary);
}

.wf-row.is-error.is-selected {
  box-shadow: inset 3px 0 0 var(--td-danger);
}

.wf-row-name {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.wf-kind {
  font-size: 10.5px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: var(--radius-xs);
  border: 1px solid var(--td-border-strong);
  background-color: var(--td-bg-subtle);
  color: var(--td-text-secondary);
  flex-shrink: 0;
}

.wf-span-title {
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-err-icon {
  color: var(--td-danger-text);
  flex-shrink: 0;
}

.wf-row-track {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.wf-bar-rail {
  position: relative;
  flex: 1;
  height: 12px;
  border-radius: var(--radius-xs);
  background-color: var(--td-bg-subtle);
  overflow: hidden;
}

.wf-bar {
  position: absolute;
  top: 2px;
  bottom: 2px;
  border-radius: 3px;
  background-color: var(--td-border-strong);
}

.wf-bar--root {
  background-color: var(--td-text-secondary);
}

.wf-bar--io {
  background-color: var(--td-text-primary);
}

.wf-bar--error {
  background-color: var(--td-danger);
}

.wf-ttft-tick {
  position: absolute;
  top: -1px;
  bottom: -1px;
  width: 2px;
  background-color: var(--td-bg-surface);
}

.wf-dur {
  font-size: 11.5px;
  color: var(--td-text-secondary);
  min-width: 52px;
  text-align: right;
  flex-shrink: 0;
}

.wf-inspector {
  border: 1px solid var(--td-border);
  border-radius: var(--radius-md);
  background-color: var(--td-bg-surface);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.wf-ins-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--td-border);
}

.wf-ins-title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.wf-ins-title {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
}

.wf-ins-id {
  font-size: 11.5px;
  color: var(--td-text-secondary);
}

.wf-meta-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  font-size: 12px;
}

.wf-k {
  color: var(--td-text-secondary);
}

.wf-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.wf-sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.wf-sec-head h5 {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--td-text-secondary);
}

.wf-attr-table {
  border: 1px solid var(--td-border);
  border-radius: var(--radius-sm);
  overflow: hidden;
  font-size: 12px;
}

.wf-attr-row {
  display: grid;
  grid-template-columns: minmax(140px, 0.9fr) minmax(0, 1.3fr);
  gap: 10px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--td-border);
}

.wf-attr-row:last-child {
  border-bottom: 0;
}

.wf-attr-key {
  color: var(--td-text-secondary);
  word-break: break-all;
}

.wf-attr-val {
  color: var(--td-text-primary);
  word-break: break-all;
}

.wf-code {
  max-height: 220px;
  overflow: auto;
  font-size: 12px;
}

@media (max-width: 960px) {
  .wf-layout {
    grid-template-columns: 1fr;
  }
}
</style>
