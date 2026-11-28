<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getTraceByIdApi, type TraceDetail } from '../api/trace'
import TdIcon from '../components/TdIcon.vue'
import TraceWaterfall from '../components/TraceWaterfall.vue'
import { copyText } from '../utils/format'
import { formatLatency } from '../utils/runFormat'

const route = useRoute()
const router = useRouter()

const traceId = computed(() => String(route.params.traceId || ''))
const trace = ref<TraceDetail | null>(null)
const loading = ref(false)
const loadError = ref('')
const liveAnnouncement = ref('')

async function loadTrace() {
  if (!traceId.value) return
  loading.value = true
  loadError.value = ''
  try {
    trace.value = await getTraceByIdApi(traceId.value)
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '加载 Trace 链路失败'
  } finally {
    loading.value = false
  }
}

async function copyTraceId() {
  if (!trace.value?.traceId) return
  await copyText(trace.value.traceId, '已复制 traceId')
  liveAnnouncement.value = '已复制完整 traceId'
}

watch(traceId, loadTrace)
onMounted(loadTrace)
</script>

<template>
  <div class="view-stack">
    <div class="sr-only" aria-live="polite">{{ liveAnnouncement }}</div>

    <header class="view-header">
      <div class="view-title-group">
        <button
          v-if="trace?.runId"
          type="button"
          class="btn btn--secondary btn--sm"
          @click="router.push(`/runs/${trace.runId}`)"
        >
          <TdIcon name="arrow-left" :size="13" />
          <span>返回评测看板</span>
        </button>
        <h1 class="view-title">Trace 链路回放</h1>
        <button
          v-if="trace?.traceId"
          type="button"
          class="code-pill code-pill--copy"
          title="复制完整 traceId"
          @click="copyTraceId"
        >
          <TdIcon name="copy" :size="12" class="code-pill-icon" />
          <span class="code-pill-text">{{ trace.traceId }}</span>
        </button>
      </div>

      <div class="view-actions">
        <button type="button" class="btn btn--secondary" :disabled="loading" @click="loadTrace">
          <TdIcon name="refresh" :size="14" />
          <span>刷新</span>
        </button>
      </div>
    </header>

    <div v-if="loadError" class="error-banner" role="alert">
      <span class="error-banner-body">
        <TdIcon name="alert-circle" :size="15" />
        <span>{{ loadError }}</span>
      </span>
      <button type="button" class="btn btn--secondary btn--sm" @click="loadTrace">
        <TdIcon name="refresh" :size="13" />
        <span>重试</span>
      </button>
    </div>

    <template v-else-if="trace">
      <section class="summary-strip mono">
        <div class="summary-item">
          <span class="summary-k">case_key</span>
          <strong>{{ trace.caseKey || '—' }}</strong>
        </div>
        <div class="summary-item">
          <span class="summary-k">Span 总数</span>
          <strong>{{ trace.spans.length }}</strong>
        </div>
        <div class="summary-item">
          <span class="summary-k">端到端总耗时</span>
          <strong>{{ formatLatency(trace.totalDurationMs) }}</strong>
        </div>
        <div class="summary-item">
          <span class="summary-k">所属任务</span>
          <router-link v-if="trace.runId" :to="`/runs/${trace.runId}`" class="table-row-link">
            Run #{{ trace.runId }}
          </router-link>
          <strong v-else>—</strong>
        </div>
      </section>

      <TraceWaterfall :trace="trace" />
    </template>
  </div>
</template>

<style scoped>
.summary-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  padding: 14px 16px;
  border-radius: var(--radius-md);
  border: 1px solid var(--td-border);
  background-color: var(--td-bg-surface);
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.summary-k {
  font-size: 11.5px;
  color: var(--td-text-secondary);
}

.summary-item strong {
  font-size: 14px;
  font-weight: 600;
  color: var(--td-text-primary);
}

@media (max-width: 720px) {
  .summary-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
