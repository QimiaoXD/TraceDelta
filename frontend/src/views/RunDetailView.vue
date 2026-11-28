<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  cancelRunApi,
  deleteRunApi,
  getRunDetailApi,
  isActiveRunStatus,
  listResultAttemptsApi,
  listRunResultsApi,
  type EvalAttemptItem,
  type EvalResultItem,
  type EvalResultStatus,
  type EvalRunDetail,
} from '../api/run'
import { useConsoleDataStore } from '../stores/consoleData'
import { getResultTraceApi, type TraceDetail } from '../api/trace'
import TdIcon from '../components/TdIcon.vue'
import TraceWaterfall from '../components/TraceWaterfall.vue'
import { copyText, formatDateTime, prettyJson } from '../utils/format'
import {
  attemptStatusMeta,
  formatBudgetReason,
  formatCostUsd,
  formatLatency,
  resultStatusMeta,
  runStatusMeta,
  statusTagClass,
} from '../utils/runFormat'

const route = useRoute()
const router = useRouter()
const store = useConsoleDataStore()

const runId = computed(() => Number(route.params.id))

const run = ref<EvalRunDetail | null>(null)
const results = ref<EvalResultItem[]>([])
const loading = ref(false)
const loadError = ref('')
const cancelling = ref(false)
const deleting = ref(false)
const showSnapshot = ref(false)
const liveAnnouncement = ref('')

const resultFilter = ref<'ALL' | EvalResultStatus>('ALL')
const keyword = ref('')

const drawerVisible = ref(false)
const drawerTab = ref<'trace' | 'attempts'>('trace')
const selectedResult = ref<EvalResultItem | null>(null)
const attempts = ref<EvalAttemptItem[]>([])
const attemptsLoading = ref(false)
const attemptsError = ref('')
const traceDetail = ref<TraceDetail | null>(null)
const traceLoading = ref(false)
const traceError = ref('')

let pollTimer: number | null = null

const isActive = computed(() => (run.value ? isActiveRunStatus(run.value.status) : false))

const progressPercent = computed(() => {
  if (!run.value || run.value.totalCases <= 0) return 0
  return Math.min(100, Math.round((run.value.completedCases / run.value.totalCases) * 100))
})

const filteredResults = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return results.value.filter((item) => {
    if (resultFilter.value !== 'ALL' && item.status !== resultFilter.value) {
      return false
    }
    if (!q) return true
    return (
      item.caseKey.toLowerCase().includes(q) ||
      (item.errorType || '').toLowerCase().includes(q) ||
      (item.outputText || '').toLowerCase().includes(q)
    )
  })
})

const resultFilterOptions: { key: 'ALL' | EvalResultStatus; label: string }[] = [
  { key: 'ALL', label: '全部' },
  { key: 'EXEC_SUCCEEDED', label: '执行成功' },
  { key: 'EXEC_FAILED', label: '执行失败' },
  { key: 'SKIPPED', label: '已跳过' },
  { key: 'PENDING', label: '待执行' },
]

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

function syncPolling() {
  if (isActive.value) {
    if (pollTimer === null) {
      pollTimer = window.setInterval(() => {
        fetchAll(true)
      }, 2000)
    }
  } else {
    stopPolling()
  }
}

async function fetchAll(silent = false) {
  if (!silent) {
    loading.value = true
    loadError.value = ''
  }
  try {
    const [detail, resultList] = await Promise.all([
      getRunDetailApi(runId.value),
      listRunResultsApi(runId.value),
    ])
    run.value = detail
    results.value = resultList
    store.rememberRunName(detail.id, detail.name)
    syncPolling()
  } catch (err) {
    if (!silent) {
      loadError.value = err instanceof Error ? err.message : '加载评测详情失败'
    }
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
}

async function handleCancel() {
  if (!run.value || cancelling.value) return
  try {
    await ElMessageBox.confirm(
      '确认取消当前评测任务？在途请求将自然完成，剩余待执行用例将标记为 SKIPPED。',
      '取消评测任务',
      {
        type: 'warning',
        confirmButtonText: '确认取消',
        cancelButtonText: '继续运行',
      },
    )
  } catch {
    return
  }

  cancelling.value = true
  try {
    run.value = await cancelRunApi(run.value.id)
    ElMessage.success('已发送取消指令，等待在途用例收尾')
    liveAnnouncement.value = '已请求取消当前评测任务'
    await fetchAll(true)
  } finally {
    cancelling.value = false
  }
}

async function handleDelete() {
  if (!run.value || deleting.value || isActive.value) return
  try {
    await ElMessageBox.confirm(
      `确认删除评测任务「${run.value.name}」？其下 ${run.value.totalCases} 条用例结果与全部 EvalAttempt 尝试记录将一并清除，此操作不可撤销。`,
      '删除评测任务',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }

  deleting.value = true
  try {
    const id = run.value.id
    const name = run.value.name
    await deleteRunApi(id)
    store.removeRunCache(id)
    stopPolling()
    ElMessage.success(`已删除评测任务「${name}」`)
    router.push('/runs')
  } finally {
    deleting.value = false
  }
}

async function openInspector(item: EvalResultItem, initialTab: 'trace' | 'attempts' = 'trace') {
  selectedResult.value = item
  drawerTab.value = initialTab
  drawerVisible.value = true
  attempts.value = []
  attemptsError.value = ''
  attemptsLoading.value = true
  traceDetail.value = null
  traceError.value = ''
  traceLoading.value = true

  await Promise.all([
    listResultAttemptsApi(runId.value, item.id)
      .then((res) => {
        attempts.value = res
      })
      .catch((err) => {
        attemptsError.value = err instanceof Error ? err.message : '加载尝试记录失败'
      })
      .finally(() => {
        attemptsLoading.value = false
      }),
    getResultTraceApi(runId.value, item.id)
      .then((res) => {
        traceDetail.value = res
      })
      .catch((err) => {
        traceError.value = err instanceof Error ? err.message : '加载 Trace 链路失败'
      })
      .finally(() => {
        traceLoading.value = false
      }),
  ])
}

async function copyCaseHash(hash: string) {
  await copyText(hash, '已复制 case_hash')
  liveAnnouncement.value = '已复制完整 case_hash'
}

watch(runId, () => {
  stopPolling()
  fetchAll(false)
})

onMounted(() => {
  fetchAll(false)
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<template>
  <div class="view-stack">
    <div class="sr-only" aria-live="polite">{{ liveAnnouncement }}</div>

    <div v-if="loadError" class="error-banner" role="alert">
      <span class="error-banner-body">
        <TdIcon name="alert-circle" :size="15" />
        <span>{{ loadError }}</span>
      </span>
      <button type="button" class="btn btn--secondary btn--sm" @click="fetchAll(false)">
        <TdIcon name="refresh" :size="13" />
        <span>重试</span>
      </button>
    </div>

    <template v-else-if="run">
      <header class="view-header">
        <div class="view-title-group">
          <h1 class="view-title">{{ run.name }}</h1>
          <span :class="statusTagClass(runStatusMeta(run.status).tone)">
            <TdIcon :name="runStatusMeta(run.status).icon" :size="12" />
            <span>{{ runStatusMeta(run.status).label }}</span>
          </span>
          <span v-if="isActive" class="micro-badge mono">
            <TdIcon name="refresh" :size="12" />
            <span>每 2 秒自动轮询</span>
          </span>
        </div>

        <div class="view-actions">
          <button
            v-if="isActive"
            type="button"
            class="btn btn--secondary"
            :disabled="cancelling || run.cancelRequested"
            @click="handleCancel"
          >
            <TdIcon name="x" :size="14" />
            <span>{{ run.cancelRequested ? '取消中…' : '取消任务' }}</span>
          </button>
          <button
            v-else
            type="button"
            class="btn btn--secondary"
            :disabled="deleting"
            @click="handleDelete"
          >
            <TdIcon name="trash" :size="14" />
            <span>{{ deleting ? '删除中…' : '删除记录' }}</span>
          </button>
          <button type="button" class="btn btn--secondary" :disabled="loading" @click="fetchAll(false)">
            <TdIcon name="refresh" :size="14" />
            <span>刷新</span>
          </button>
        </div>
      </header>

      <!-- Honest Engineering Disclosures (rendered strictly from real backend flags) -->
      <div v-if="run.interruptedAttemptCount > 0" class="notice-banner notice-banner--warning" role="status">
        <TdIcon name="alert-triangle" :size="16" class="notice-icon" />
        <div class="notice-content">
          <strong>包含 {{ run.interruptedAttemptCount }} 次进程中断恢复记录（At-Least-Once 语义）</strong>
          <p>
            已持久化成功的用例未被重复调度；进程中断瞬间处于运行中的尝试已标记为
            <code class="mono">INTERRUPTED_UNKNOWN</code>
            并按总重试上限受控重试，若上游模型在中断前已返回并计费，可能产生重复费用。
          </p>
        </div>
      </div>

      <div v-if="run.budgetExceeded" class="notice-banner notice-banner--warning" role="status">
        <TdIcon name="alert-triangle" :size="16" class="notice-icon" />
        <div class="notice-content">
          <strong>预算熔断已触发：{{ formatBudgetReason(run.budgetExceededReason) }}</strong>
          <p>
            系统已停止派发新用例，在途请求自然完成后，共有
            <span class="mono">{{ run.skippedCases }}</span> 条待执行用例标记为
            <code class="mono">SKIPPED</code>。
          </p>
        </div>
      </div>

      <div v-if="run.rateLimitDegraded" class="notice-banner" role="status">
        <TdIcon name="info" :size="16" class="notice-icon" />
        <div class="notice-content">
          <strong>Redis 限流已自动降级为本机内存令牌桶</strong>
          <p>执行期间检测到 Redis 不可用，已平滑切换至本地令牌桶继续保障渠道限流，未中断评测批次。</p>
        </div>
      </div>

      <div v-if="run.traceIncomplete" class="notice-banner notice-banner--warning" role="status">
        <TdIcon name="alert-triangle" :size="16" class="notice-icon" />
        <div class="notice-content">
          <strong>诊断 Trace 不完整（有界队列背压丢弃 {{ run.traceDroppedCount }} 条 Span）</strong>
          <p>
            为防止内存耗尽，部分诊断链路记录在异步写入队列满且同步回退失败后被丢弃；核心评测结果与
            <code class="mono">EvalAttempt</code> 记录均已同步落库，不受影响。
          </p>
        </div>
      </div>

      <!-- Progress & Real Metrics Panel -->
      <section class="panel-card">
        <div class="progress-head">
          <span class="progress-label">
            执行进度 <strong class="mono">{{ run.completedCases }} / {{ run.totalCases }}</strong>
            （<span class="mono">{{ progressPercent }}%</span>）
          </span>
          <span class="progress-meta mono">
            开始：{{ formatDateTime(run.startedAt) }} · 结束：{{ formatDateTime(run.finishedAt) }}
          </span>
        </div>
        <div
          class="progress-track"
          role="progressbar"
          :aria-valuenow="progressPercent"
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <div class="progress-fill" :style="{ width: `${progressPercent}%` }" />
        </div>

        <div class="metrics-grid">
          <div class="metric-item">
            <span class="metric-label">执行成功</span>
            <strong class="metric-value mono">{{ run.succeededCases }}</strong>
          </div>
          <div class="metric-item">
            <span class="metric-label">执行失败</span>
            <strong class="metric-value mono">{{ run.failedCases }}</strong>
          </div>
          <div class="metric-item">
            <span class="metric-label">已跳过</span>
            <strong class="metric-value mono">{{ run.skippedCases }}</strong>
          </div>
          <div class="metric-item">
            <span class="metric-label">平均 / P95 延迟</span>
            <strong class="metric-value mono">
              {{ formatLatency(run.avgLatencyMs) }} / {{ formatLatency(run.p95LatencyMs) }}
            </strong>
          </div>
          <div class="metric-item">
            <span class="metric-label">总 Tokens（入 / 出）</span>
            <strong class="metric-value mono">
              {{ (run.totalInputTokens + run.totalOutputTokens).toLocaleString() }}
              <span class="metric-sub">({{ run.totalInputTokens }}/{{ run.totalOutputTokens }})</span>
            </strong>
          </div>
          <div class="metric-item">
            <span class="metric-label">估算费用</span>
            <strong class="metric-value mono">{{ formatCostUsd(run.totalCostUsd) }}</strong>
          </div>
        </div>
      </section>

      <!-- Collapsible Frozen Snapshot -->
      <section class="panel-card">
        <div class="snapshot-header">
          <div class="snapshot-summary">
            <span class="micro-badge">冻结快照</span>
            <span class="mono snapshot-meta">
              {{ run.datasetNameSnapshot }} v{{ run.datasetVersionNo }} · {{ run.providerNameSnapshot }} / {{ run.modelSnapshot }} · 并发 {{ run.concurrency }} · 最多 {{ run.maxAttempts }} 次尝试 · 限流 {{ run.rateLimitPerSec }}/s (burst {{ run.rateLimitBurst }})
            </span>
          </div>
          <button
            type="button"
            class="btn btn--ghost btn--sm"
            :aria-expanded="showSnapshot"
            @click="showSnapshot = !showSnapshot"
          >
            <TdIcon :name="showSnapshot ? 'chevron-down' : 'chevron-right'" :size="13" />
            <span>{{ showSnapshot ? '收起配置快照' : '查看完整快照' }}</span>
          </button>
        </div>

        <div v-if="showSnapshot" class="snapshot-body">
          <div class="snapshot-kv-grid mono">
            <div><span class="kv-k">目标类型：</span>{{ run.targetType }}</div>
            <div><span class="kv-k">请求超时：</span>{{ run.requestTimeoutMs }} ms</div>
            <div><span class="kv-k">费用预算：</span>{{ run.maxCostUsd !== null ? formatCostUsd(run.maxCostUsd) : '不限' }}</div>
            <div><span class="kv-k">Token 预算：</span>{{ run.maxTotalTokens !== null ? run.maxTotalTokens.toLocaleString() : '不限' }}</div>
            <div><span class="kv-k">退避基数：</span>{{ run.retryBaseDelayMs }} ms – {{ run.retryMaxDelayMs }} ms</div>
            <div><span class="kv-k">状态说明：</span>{{ run.statusReason || '—' }}</div>
          </div>
          <pre class="code-block mono">{{ prettyJson(run.targetConfigSnapshotJson) }}</pre>
        </div>
      </section>

      <!-- Results Table -->
      <div class="view-toolbar">
        <div class="view-toolbar-group">
          <div class="seg-control seg-control--sm" role="group" aria-label="按用例状态筛选">
            <button
              v-for="opt in resultFilterOptions"
              :key="opt.key"
              type="button"
              class="seg-btn"
              :class="{ 'is-active': resultFilter === opt.key }"
              :aria-pressed="resultFilter === opt.key"
              @click="resultFilter = opt.key"
            >
              {{ opt.label }}
            </button>
          </div>

          <div class="search-box">
            <TdIcon name="search" :size="14" class="search-box-icon" />
            <input
              v-model="keyword"
              type="search"
              class="search-box-input"
              placeholder="按 case_key、输出或错误搜索…"
              aria-label="搜索用例执行结果"
            />
            <button
              v-if="keyword"
              type="button"
              class="search-box-clear"
              aria-label="清空搜索"
              @click="keyword = ''"
            >
              <TdIcon name="x" :size="12" />
            </button>
          </div>
        </div>
        <span class="view-count mono">
          {{ filteredResults.length }} / {{ results.length }} 条用例
        </span>
      </div>

      <div class="data-table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>case_key</th>
              <th>执行状态</th>
              <th class="col-num">尝试次数</th>
              <th class="col-num">延迟</th>
              <th class="col-num">Tokens / 费用</th>
              <th>输出 / 错误摘要</th>
              <th class="col-actions">详情</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in filteredResults" :key="item.id">
              <td class="mono">
                <div class="case-key-cell">{{ item.caseKey }}</div>
                <div class="cell-sub">{{ item.caseHash.slice(0, 10) }}</div>
              </td>
              <td>
                <span :class="statusTagClass(resultStatusMeta(item.status).tone)">
                  <TdIcon :name="resultStatusMeta(item.status).icon" :size="12" />
                  <span>{{ resultStatusMeta(item.status).label }}</span>
                </span>
              </td>
              <td class="col-num mono">
                <span>{{ item.attemptCount }}</span>
                <span
                  v-if="item.hasInterruptedUnknown"
                  class="status-tag status-tag--warning"
                  style="margin-left: 6px"
                >
                  中断恢复
                </span>
              </td>
              <td class="col-num mono">{{ formatLatency(item.latencyMs) }}</td>
              <td class="col-num mono">
                <div>{{ (item.inputTokens + item.outputTokens).toLocaleString() }}</div>
                <div class="cell-sub">{{ formatCostUsd(item.estimatedCostUsd) }}</div>
              </td>
              <td>
                <div v-if="item.outputText" class="output-preview">{{ item.outputText }}</div>
                <div v-else-if="item.errorType" class="error-preview mono">
                  {{ item.errorType }}: {{ item.errorMessage }}
                </div>
                <span v-else class="cell-muted">—</span>
              </td>
              <td class="col-actions">
                <button type="button" class="btn btn--secondary btn--sm" @click="openInspector(item, 'trace')">
                  <TdIcon name="layers" :size="12" />
                  <span>链路 / 详情</span>
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Case, Trace Waterfall & EvalAttempt Inspector Drawer -->
      <el-drawer
        v-model="drawerVisible"
        :title="selectedResult ? `用例详情 · ${selectedResult.caseKey}` : '用例详情'"
        size="720px"
      >
        <div v-if="selectedResult" class="drawer-stack">
          <div class="drawer-meta-bar">
            <span :class="statusTagClass(resultStatusMeta(selectedResult.status).tone)">
              <TdIcon :name="resultStatusMeta(selectedResult.status).icon" :size="12" />
              <span>{{ resultStatusMeta(selectedResult.status).label }}</span>
            </span>
            <span class="micro-badge mono">评分：{{ selectedResult.scoreConclusion }}</span>
            <button
              type="button"
              class="code-pill code-pill--copy"
              title="复制完整 case_hash"
              @click="copyCaseHash(selectedResult.caseHash)"
            >
              <TdIcon name="copy" :size="12" class="code-pill-icon" />
              <span class="code-pill-text">{{ selectedResult.caseHash.slice(0, 16) }}…</span>
            </button>
            <router-link
              v-if="selectedResult.traceId"
              :to="`/traces/${selectedResult.traceId}`"
              class="btn btn--secondary btn--sm"
              style="margin-left: auto"
            >
              <TdIcon name="layers" :size="12" />
              <span>全屏 Trace 回放</span>
            </router-link>
          </div>

          <section v-if="selectedResult.outputText" class="drawer-section">
            <h3>目标输出</h3>
            <pre class="code-block">{{ selectedResult.outputText }}</pre>
          </section>

          <section v-if="selectedResult.errorType" class="drawer-section">
            <h3>错误信息</h3>
            <div class="error-banner" role="alert">
              <span class="error-banner-body mono">
                <TdIcon name="alert-circle" :size="14" />
                <span>{{ selectedResult.errorType }} — {{ selectedResult.errorMessage }}</span>
              </span>
            </div>
          </section>

          <div class="seg-control seg-control--sm" role="tablist" aria-label="检查视图切换">
            <button
              type="button"
              role="tab"
              class="seg-btn"
              :class="{ 'is-active': drawerTab === 'trace' }"
              :aria-selected="drawerTab === 'trace'"
              @click="drawerTab = 'trace'"
            >
              Trace 瀑布图（{{ traceDetail?.spans.length ?? 0 }}）
            </button>
            <button
              type="button"
              role="tab"
              class="seg-btn"
              :class="{ 'is-active': drawerTab === 'attempts' }"
              :aria-selected="drawerTab === 'attempts'"
              @click="drawerTab = 'attempts'"
            >
              EvalAttempt 时间线（{{ attempts.length }}）
            </button>
          </div>

          <section v-if="drawerTab === 'trace'" class="drawer-section">
            <p v-if="traceLoading" class="cell-muted">正在加载 OTel Trace 链路…</p>
            <div v-else-if="traceError" class="error-banner" role="alert">{{ traceError }}</div>
            <TraceWaterfall v-else-if="traceDetail" :trace="traceDetail" :compact="true" />
          </section>

          <section v-else class="drawer-section">
            <h3>EvalAttempt 执行尝试时间线（{{ attempts.length }} 次）</h3>
            <p v-if="attemptsLoading" class="cell-muted">正在加载尝试记录…</p>
            <div v-else-if="attemptsError" class="error-banner" role="alert">{{ attemptsError }}</div>
            <p v-else-if="attempts.length === 0" class="cell-muted">尚无执行尝试记录（用例处于待执行或已跳过）。</p>
            <div v-else class="attempt-list">
              <div v-for="att in attempts" :key="att.id" class="attempt-card">
                <div class="attempt-head">
                  <span class="mono attempt-no">Attempt #{{ att.attemptNo }}</span>
                  <span :class="statusTagClass(attemptStatusMeta(att.status).tone)">
                    <TdIcon :name="attemptStatusMeta(att.status).icon" :size="11" />
                    <span>{{ attemptStatusMeta(att.status).label }}</span>
                  </span>
                  <span class="mono attempt-latency">{{ formatLatency(att.latencyMs) }}</span>
                </div>
                <div class="attempt-meta mono">
                  <span>Tokens: {{ att.inputTokens }} / {{ att.outputTokens }}</span>
                  <span>费用: {{ formatCostUsd(att.estimatedCostUsd) }}</span>
                  <span>{{ formatDateTime(att.startedAt) }} → {{ formatDateTime(att.finishedAt) }}</span>
                </div>
                <div v-if="att.errorType" class="attempt-err mono">
                  {{ att.errorType }}: {{ att.errorMessage }}
                </div>
              </div>
            </div>
          </section>
        </div>
      </el-drawer>
    </template>
  </div>
</template>

<style scoped>
.panel-card {
  background-color: var(--td-bg-surface);
  border: 1px solid var(--td-border);
  border-radius: var(--radius-md);
  padding: 16px 18px;
}

.notice-banner {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--td-border-strong);
  background-color: var(--td-bg-surface);
  color: var(--td-text-primary);
}

.notice-banner--warning {
  border-color: var(--td-warning-border);
  background-color: var(--td-warning-bg);
  color: var(--td-warning-text);
}

.notice-icon {
  flex-shrink: 0;
  margin-top: 2px;
}

.notice-content strong {
  display: block;
  font-size: 13.5px;
  font-weight: 600;
}

.notice-content p {
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--td-text-primary);
}

.progress-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.progress-label {
  font-size: 13.5px;
  color: var(--td-text-primary);
}

.progress-meta {
  font-size: 12px;
  color: var(--td-text-secondary);
}

.progress-track {
  height: 8px;
  border-radius: var(--radius-pill);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border);
  overflow: hidden;
  margin-bottom: 16px;
}

.progress-fill {
  height: 100%;
  background-color: var(--td-primary-bg);
  transition: width var(--dur-base) var(--ease);
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.metric-item {
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.metric-label {
  font-size: 12px;
  color: var(--td-text-secondary);
}

.metric-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--td-text-primary);
}

.metric-sub {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--td-text-secondary);
}

.snapshot-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.snapshot-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
}

.snapshot-meta {
  font-size: 12.5px;
  color: var(--td-text-secondary);
}

.snapshot-body {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--td-border);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.snapshot-kv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 8px 16px;
  font-size: 12.5px;
}

.kv-k {
  color: var(--td-text-secondary);
}

.code-block {
  margin: 0;
  padding: 12px;
  border-radius: var(--radius-sm);
  background-color: var(--td-bg-code);
  border: 1px solid var(--td-border);
  font-size: 12.5px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 300px;
  overflow: auto;
}

.case-key-cell {
  font-weight: 600;
}

.cell-sub {
  font-size: 12px;
  color: var(--td-text-secondary);
  margin-top: 2px;
}

.cell-muted {
  color: var(--td-text-secondary);
  font-size: 13px;
}

.output-preview,
.error-preview {
  max-width: 340px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
}

.error-preview {
  color: var(--td-danger-text);
}

.drawer-stack {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.drawer-meta-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.drawer-section h3 {
  margin: 0 0 8px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--td-text-primary);
}

.attempt-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.attempt-card {
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--td-border);
  background-color: var(--td-bg-subtle);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.attempt-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.attempt-no {
  font-weight: 600;
  font-size: 13px;
}

.attempt-latency {
  margin-left: auto;
  font-size: 12px;
  color: var(--td-text-secondary);
}

.attempt-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: var(--td-text-secondary);
}

.attempt-err {
  font-size: 12px;
  color: var(--td-danger-text);
  word-break: break-word;
}
</style>
