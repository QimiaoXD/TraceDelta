<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createRunApi,
  deleteRunApi,
  isActiveRunStatus,
  type CreateEvalRunPayload,
  type EvalRunStatus,
  type EvalRunSummary,
} from '../api/run'
import { useConsoleDataStore } from '../stores/consoleData'
import TdIcon from '../components/TdIcon.vue'
import { formatDateTime } from '../utils/format'
import {
  formatCostUsd,
  formatLatency,
  runStatusMeta,
  statusTagClass,
} from '../utils/runFormat'

const router = useRouter()
const store = useConsoleDataStore()

const runs = computed(() => store.runs)
const statusFilter = ref<'ALL' | EvalRunStatus>('ALL')
const keyword = ref('')
const loadError = ref('')
const liveAnnouncement = ref('')

let pollTimer: number | null = null

const dialogVisible = ref(false)
const submitting = ref(false)
const formError = ref('')
const showAdvanced = ref(false)
const formAlertRef = ref<HTMLElement | null>(null)
const datasetSelectRef = ref<{ focus?: () => void } | null>(null)

const form = reactive({
  name: '',
  targetType: 'PROMPT' as 'PROMPT' | 'HTTP',
  datasetId: null as number | null,
  datasetVersionNo: null as number | null,
  providerId: null as number | null,
  httpTargetId: null as number | null,
  model: '',
  systemPrompt: '',
  userPromptTemplate: '{{question}}',
  concurrency: 8,
  maxAttempts: 3,
  requestTimeoutMs: 30000,
  rateLimitPerSec: 20,
  rateLimitBurst: 20,
  maxCostUsd: '' as string,
  maxTotalTokens: '' as string,
})

const hasActiveRun = computed(() => runs.value.some((r) => isActiveRunStatus(r.status)))

const statusFilters: { key: 'ALL' | EvalRunStatus; label: string }[] = [
  { key: 'ALL', label: '全部' },
  { key: 'RUNNING', label: '运行中' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'PARTIAL_FAILED', label: '部分失败' },
  { key: 'FAILED', label: '已失败' },
  { key: 'CANCELLED', label: '已取消' },
]

const filteredRuns = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return runs.value.filter((run) => {
    if (statusFilter.value !== 'ALL') {
      if (statusFilter.value === 'RUNNING') {
        if (!isActiveRunStatus(run.status)) return false
      } else if (run.status !== statusFilter.value) {
        return false
      }
    }
    if (!q) return true
    return (
      run.name.toLowerCase().includes(q) ||
      run.datasetNameSnapshot.toLowerCase().includes(q) ||
      run.providerNameSnapshot.toLowerCase().includes(q) ||
      run.modelSnapshot.toLowerCase().includes(q) ||
      String(run.id).includes(q)
    )
  })
})

const selectedDatasetVersions = computed(() => {
  if (!form.datasetId) return []
  return store.getCachedVersions(form.datasetId) ?? []
})

const selectedProvider = computed(() =>
  store.providers.find((p) => p.id === form.providerId) ?? null,
)

const selectedHttpTarget = computed(() =>
  store.httpTargets.find((t) => t.id === form.httpTargetId) ?? null,
)

function startOrStopPolling() {
  if (hasActiveRun.value) {
    if (pollTimer === null) {
      pollTimer = window.setInterval(async () => {
        try {
          await store.fetchRuns()
        } catch {
          // keep quiet on transient poll hiccup
        }
      }, 2000)
    }
  } else if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(hasActiveRun, startOrStopPolling)

watch(
  () => form.datasetId,
  async (newId) => {
    form.datasetVersionNo = null
    if (!newId) return
    try {
      const versions = await store.fetchVersions(newId)
      if (versions.length > 0) {
        const latest = versions.reduce((a, b) => (b.versionNo > a.versionNo ? b : a), versions[0])
        form.datasetVersionNo = latest.versionNo
      }
    } catch {
      // handled in UI
    }
  },
)

watch(
  () => form.providerId,
  () => {
    const provider = selectedProvider.value
    if (provider) {
      form.model = provider.defaultModel
    }
  },
)

async function refresh() {
  loadError.value = ''
  try {
    await store.fetchRuns()
    startOrStopPolling()
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '加载评测任务列表失败'
  }
}

async function openCreateDialog() {
  formError.value = ''
  showAdvanced.value = false
  form.name = ''
  form.targetType = 'PROMPT'
  form.datasetId = null
  form.datasetVersionNo = null
  form.providerId = null
  form.httpTargetId = null
  form.model = ''
  form.systemPrompt = ''
  form.userPromptTemplate = '{{question}}'
  form.concurrency = 8
  form.maxAttempts = 3
  form.requestTimeoutMs = 30000
  form.rateLimitPerSec = 20
  form.rateLimitBurst = 20
  form.maxCostUsd = ''
  form.maxTotalTokens = ''
  dialogVisible.value = true

  try {
    const [dsList, provList, httpList] = await Promise.all([
      store.fetchDatasets(),
      store.fetchProviders(),
      store.fetchHttpTargets(),
    ])
    if (dsList.length > 0) {
      form.datasetId = dsList[0].id
    }
    if (provList.length > 0) {
      form.providerId = provList[0].id
    }
    if (httpList.length > 0) {
      form.httpTargetId = httpList[0].id
    }
  } catch {
    formError.value = '加载数据集、模型渠道或外部接口失败，请刷新后重试'
  }
}

function onDialogOpened() {
  nextTick(() => {
    datasetSelectRef.value?.focus?.()
  })
}

async function handleDialogBeforeClose(done: () => void) {
  if (submitting.value) return
  if (form.name.trim() || form.systemPrompt.trim() || form.maxCostUsd.trim() || form.maxTotalTokens.trim()) {
    try {
      await ElMessageBox.confirm('当前有未提交的配置内容，确定关闭吗？', '放弃新建评测', {
        confirmButtonText: '放弃并关闭',
        cancelButtonText: '继续填写',
        type: 'warning',
      })
      done()
    } catch {
      // keep dialog open
    }
    return
  }
  done()
}

async function requestCloseDialog() {
  await handleDialogBeforeClose(() => {
    dialogVisible.value = false
  })
}

async function handleCreate() {
  if (submitting.value) return
  formError.value = ''

  if (!form.datasetId || !form.datasetVersionNo) {
    formError.value = '请选择已导入用例的数据集版本'
    await nextTick()
    formAlertRef.value?.focus()
    return
  }
  if (form.targetType === 'PROMPT' && !form.providerId) {
    formError.value = '请选择模型渠道'
    await nextTick()
    formAlertRef.value?.focus()
    return
  }
  if (form.targetType === 'HTTP' && !form.httpTargetId) {
    formError.value = '请选择外部 HTTP 接口目标（可先在「外部接口」页面创建）'
    await nextTick()
    formAlertRef.value?.focus()
    return
  }
  if (form.concurrency < 1 || form.concurrency > 64) {
    formError.value = '并发度需在 1 到 64 之间'
    showAdvanced.value = true
    await nextTick()
    formAlertRef.value?.focus()
    return
  }
  if (form.maxAttempts < 1 || form.maxAttempts > 10) {
    formError.value = '最大尝试次数需在 1 到 10 之间'
    showAdvanced.value = true
    await nextTick()
    formAlertRef.value?.focus()
    return
  }

  let parsedMaxCost: number | null = null
  if (form.maxCostUsd.trim()) {
    const val = Number(form.maxCostUsd.trim())
    if (Number.isNaN(val) || val <= 0) {
      formError.value = '费用预算上限必须为大于 0 的数字'
      showAdvanced.value = true
      await nextTick()
      formAlertRef.value?.focus()
      return
    }
    parsedMaxCost = val
  }

  let parsedMaxTokens: number | null = null
  if (form.maxTotalTokens.trim()) {
    const val = Number(form.maxTotalTokens.trim())
    if (!Number.isInteger(val) || val <= 0) {
      formError.value = 'Token 预算上限必须为大于 0 的整数'
      showAdvanced.value = true
      await nextTick()
      formAlertRef.value?.focus()
      return
    }
    parsedMaxTokens = val
  }

  const payload: CreateEvalRunPayload = {
    name: form.name.trim() || undefined,
    datasetId: form.datasetId,
    datasetVersionNo: form.datasetVersionNo,
    targetType: form.targetType,
    providerId: form.targetType === 'PROMPT' ? form.providerId : null,
    httpTargetId: form.targetType === 'HTTP' ? form.httpTargetId : null,
    model: form.targetType === 'PROMPT' ? form.model.trim() || undefined : undefined,
    systemPrompt: form.targetType === 'PROMPT' ? form.systemPrompt.trim() || undefined : undefined,
    userPromptTemplate:
      form.targetType === 'PROMPT' ? form.userPromptTemplate.trim() || '{{question}}' : undefined,
    concurrency: form.concurrency,
    maxAttempts: form.maxAttempts,
    requestTimeoutMs: form.requestTimeoutMs,
    rateLimitPerSec: form.rateLimitPerSec,
    rateLimitBurst: form.rateLimitBurst,
    maxCostUsd: parsedMaxCost,
    maxTotalTokens: parsedMaxTokens,
  }

  submitting.value = true
  try {
    const created = await createRunApi(payload)
    store.rememberRunName(created.id, created.name)
    dialogVisible.value = false
    ElMessage.success(`评测任务「${created.name}」已启动`)
    liveAnnouncement.value = `已启动评测任务 ${created.name}`
    router.push(`/runs/${created.id}`)
  } catch (err) {
    formError.value = err instanceof Error ? err.message : '启动评测任务失败'
    await nextTick()
    formAlertRef.value?.focus()
  } finally {
    submitting.value = false
  }
}

async function handleDeleteRun(run: EvalRunSummary) {
  if (isActiveRunStatus(run.status)) {
    ElMessage.warning('运行中的评测任务无法直接删除，请先进入看板取消任务')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除评测任务「${run.name}」？其下 ${run.totalCases} 条用例结果与全部 EvalAttempt 尝试记录将一并清除，此操作不可撤销。`,
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
  await deleteRunApi(run.id)
  store.removeRunCache(run.id)
  ElMessage.success(`已删除评测任务「${run.name}」`)
  liveAnnouncement.value = `已删除评测任务 ${run.name}`
  await store.fetchRuns()
}

function handleRowMenu(command: string, run: EvalRunSummary) {
  if (command === 'delete') {
    handleDeleteRun(run)
  }
}

onMounted(() => {
  refresh()
})

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<template>
  <div class="view-stack">
    <div class="sr-only" aria-live="polite">{{ liveAnnouncement }}</div>

    <header class="view-header">
      <div class="view-title-group">
        <h1 class="view-title">评测任务</h1>
        <span v-if="hasActiveRun" class="micro-badge mono">
          <TdIcon name="refresh" :size="12" />
          <span>每 2 秒自动刷新</span>
        </span>
      </div>
      <div class="view-actions">
        <button type="button" class="btn btn--secondary" @click="refresh">
          <TdIcon name="refresh" :size="14" />
          <span>刷新</span>
        </button>
        <button type="button" class="btn btn--primary" @click="openCreateDialog">
          <TdIcon name="play" :size="14" />
          <span>新建评测</span>
        </button>
      </div>
    </header>

    <div class="view-toolbar">
      <div class="view-toolbar-group">
        <div class="seg-control seg-control--sm" role="group" aria-label="按任务状态筛选">
          <button
            v-for="tab in statusFilters"
            :key="tab.key"
            type="button"
            class="seg-btn"
            :class="{ 'is-active': statusFilter === tab.key }"
            :aria-pressed="statusFilter === tab.key"
            @click="statusFilter = tab.key"
          >
            {{ tab.label }}
          </button>
        </div>

        <div class="search-box">
          <TdIcon name="search" :size="14" class="search-box-icon" />
          <input
            v-model="keyword"
            type="search"
            class="search-box-input"
            placeholder="按名称、数据集、渠道或模型搜索…"
            aria-label="搜索评测任务"
          />
          <button
            v-if="keyword"
            type="button"
            class="search-box-clear"
            aria-label="清空搜索条件"
            @click="keyword = ''"
          >
            <TdIcon name="x" :size="12" />
          </button>
        </div>
      </div>

      <span v-if="store.runsLoaded" class="view-count mono" aria-live="polite">
        {{ filteredRuns.length }}<template v-if="statusFilter !== 'ALL' || keyword.trim()"> / {{ runs.length }}</template> 次评测
      </span>
    </div>

    <div v-if="loadError" class="error-banner" role="alert">
      <span class="error-banner-body">
        <TdIcon name="alert-circle" :size="15" />
        <span>{{ loadError }}</span>
      </span>
      <button type="button" class="btn btn--secondary btn--sm" @click="refresh">
        <TdIcon name="refresh" :size="13" />
        <span>重试</span>
      </button>
    </div>

    <section v-else-if="store.runsLoaded && runs.length === 0" class="empty-panel">
      <div class="empty-panel-icon" aria-hidden="true">
        <TdIcon name="activity" :size="18" />
      </div>
      <h2>尚无评测任务</h2>
      <p>选择一个不可变数据集版本与模型渠道（推荐 Mock 零费用验证），启动并发评测。</p>
      <button type="button" class="btn btn--primary" @click="openCreateDialog">
        <TdIcon name="play" :size="14" />
        <span>新建评测</span>
      </button>
    </section>

    <section v-else-if="store.runsLoaded && filteredRuns.length === 0" class="empty-panel">
      <div class="empty-panel-icon" aria-hidden="true">
        <TdIcon name="search" :size="18" />
      </div>
      <h2>没有匹配的评测任务</h2>
      <p>尝试切换状态筛选条件或清空搜索关键字。</p>
      <button
        type="button"
        class="btn btn--secondary"
        @click="statusFilter = 'ALL'; keyword = ''"
      >
        清除筛选条件
      </button>
    </section>

    <div v-else-if="store.runsLoaded" class="data-table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>任务与快照</th>
            <th>状态</th>
            <th class="col-num">进度</th>
            <th class="col-num">延迟（均 / P95）</th>
            <th class="col-num">Tokens / 估算费用</th>
            <th>创建时间</th>
            <th class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="run in filteredRuns" :key="run.id">
            <td>
              <router-link :to="`/runs/${run.id}`" class="table-row-link">
                <span class="table-row-link-text">{{ run.name }}</span>
              </router-link>
              <div class="table-subtext mono">
                <span class="micro-badge" style="margin-right: 6px">{{ run.targetType }}</span>
                {{ run.datasetNameSnapshot }} v{{ run.datasetVersionNo }} · {{ run.providerNameSnapshot }} / {{ run.modelSnapshot }}
              </div>
              <div v-if="run.budgetExceeded || run.interruptedAttemptCount > 0 || run.rateLimitDegraded || run.traceIncomplete" class="flag-row">
                <span v-if="run.budgetExceeded" class="status-tag status-tag--warning">
                  <TdIcon name="alert-triangle" :size="11" />
                  <span>预算熔断</span>
                </span>
                <span v-if="run.interruptedAttemptCount > 0" class="status-tag status-tag--warning">
                  <TdIcon name="refresh" :size="11" />
                  <span>中断恢复 ×{{ run.interruptedAttemptCount }}</span>
                </span>
                <span v-if="run.traceIncomplete" class="status-tag status-tag--warning">
                  <TdIcon name="layers" :size="11" />
                  <span>Trace 丢弃 ×{{ run.traceDroppedCount }}</span>
                </span>
                <span v-if="run.rateLimitDegraded" class="micro-badge">
                  <span>本地限流降级</span>
                </span>
              </div>
            </td>
            <td>
              <span :class="statusTagClass(runStatusMeta(run.status).tone)">
                <TdIcon :name="runStatusMeta(run.status).icon" :size="12" />
                <span>{{ runStatusMeta(run.status).label }}</span>
              </span>
            </td>
            <td class="col-num mono">
              <div>{{ run.completedCases }} / {{ run.totalCases }}</div>
              <div class="cell-sub">
                成功 {{ run.succeededCases }} · 失败 {{ run.failedCases }}<template v-if="run.skippedCases > 0"> · 跳过 {{ run.skippedCases }}</template>
              </div>
            </td>
            <td class="col-num mono">
              <div>{{ formatLatency(run.avgLatencyMs) }}</div>
              <div class="cell-sub">P95 {{ formatLatency(run.p95LatencyMs) }}</div>
            </td>
            <td class="col-num mono">
              <div>{{ (run.totalInputTokens + run.totalOutputTokens).toLocaleString() }}</div>
              <div class="cell-sub">{{ formatCostUsd(run.totalCostUsd) }}</div>
            </td>
            <td class="mono cell-muted">{{ formatDateTime(run.createdAt) }}</td>
            <td class="col-actions">
              <div class="row-action-inline">
                <router-link :to="`/runs/${run.id}`" class="btn btn--secondary btn--sm">
                  <span>看板</span>
                  <TdIcon name="chevron-right" :size="13" />
                </router-link>
                <el-dropdown trigger="click" @command="(cmd: string) => handleRowMenu(cmd, run)">
                  <button
                    type="button"
                    class="btn btn--icon"
                    :aria-label="`更多操作：${run.name}`"
                    title="更多操作"
                  >
                    <TdIcon name="more-horizontal" :size="15" />
                  </button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item
                        command="delete"
                        :disabled="isActiveRunStatus(run.status)"
                      >
                        <span class="dropdown-danger">
                          <TdIcon name="trash" :size="13" />
                          <span>{{ isActiveRunStatus(run.status) ? '运行中不可删除' : '删除记录' }}</span>
                        </span>
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <el-dialog
      v-model="dialogVisible"
      title="新建评测任务"
      width="620px"
      :before-close="handleDialogBeforeClose"
      @opened="onDialogOpened"
    >
      <div
        v-if="formError"
        ref="formAlertRef"
        class="error-banner"
        role="alert"
        tabindex="-1"
        style="margin-bottom: 14px"
      >
        <span class="error-banner-body">
          <TdIcon name="alert-circle" :size="15" />
          <span>{{ formError }}</span>
        </span>
      </div>

      <el-form label-position="top" @submit.prevent="handleCreate">
        <el-form-item label="被测目标类型">
          <div class="seg-control" role="group" aria-label="选择被测目标类型">
            <button
              type="button"
              class="seg-btn"
              :class="{ 'is-active': form.targetType === 'PROMPT' }"
              :aria-pressed="form.targetType === 'PROMPT'"
              @click="form.targetType = 'PROMPT'"
            >
              Prompt 目标（模型渠道）
            </button>
            <button
              type="button"
              class="seg-btn"
              :class="{ 'is-active': form.targetType === 'HTTP' }"
              :aria-pressed="form.targetType === 'HTTP'"
              @click="form.targetType = 'HTTP'"
            >
              HTTP 目标（外部接口）
            </button>
          </div>
        </el-form-item>

        <div class="form-grid-2">
          <el-form-item label="数据集" required>
            <el-select
              ref="datasetSelectRef"
              v-model="form.datasetId"
              placeholder="选择数据集"
              style="width: 100%"
            >
              <el-option
                v-for="ds in store.datasets"
                :key="ds.id"
                :label="ds.name"
                :value="ds.id"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="版本快照（不可变）" required>
            <el-select
              v-model="form.datasetVersionNo"
              placeholder="选择版本"
              :disabled="!form.datasetId || selectedDatasetVersions.length === 0"
              style="width: 100%"
            >
              <el-option
                v-for="v in selectedDatasetVersions"
                :key="v.id"
                :label="`v${v.versionNo}（${v.caseCount} 条用例）`"
                :value="v.versionNo"
              />
            </el-select>
          </el-form-item>
        </div>

        <div v-if="form.targetType === 'PROMPT'" class="form-grid-2">
          <el-form-item label="模型渠道" required>
            <el-select v-model="form.providerId" placeholder="选择渠道" style="width: 100%">
              <el-option
                v-for="p in store.providers"
                :key="p.id"
                :label="`${p.name} (${p.providerType === 'MOCK' ? 'Mock' : 'OpenAI 兼容'})`"
                :value="p.id"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="模型名称（可覆盖默认）">
            <el-input v-model="form.model" placeholder="留空使用渠道默认模型" />
          </el-form-item>
        </div>

        <el-form-item v-else label="外部 HTTP 接口目标（已启用 SSRF 防护与独立限流桶）" required>
          <el-select
            v-model="form.httpTargetId"
            placeholder="选择外部接口目标"
            style="width: 100%"
          >
            <el-option
              v-for="t in store.httpTargets"
              :key="t.id"
              :label="`${t.name} · ${t.httpMethod} ${t.url}`"
              :value="t.id"
            />
          </el-select>
          <p v-if="selectedHttpTarget" class="form-hint mono">
            {{ selectedHttpTarget.httpMethod }} {{ selectedHttpTarget.url }} · JSONPath:
            {{ selectedHttpTarget.responseJsonPath || '原始响应体' }}
          </p>
        </el-form-item>

        <el-form-item label="任务名称（可选）">
          <el-input v-model="form.name" placeholder="留空自动生成：数据集版本 · 目标" />
        </el-form-item>

        <template v-if="form.targetType === 'PROMPT'">
          <el-form-item label="User Prompt 模板（支持 {{变量名}}，默认 {{question}}）">
            <el-input
              v-model="form.userPromptTemplate"
              type="textarea"
              :rows="2"
              placeholder="{{question}}"
            />
          </el-form-item>

          <el-form-item label="System Prompt（可选）">
            <el-input
              v-model="form.systemPrompt"
              type="textarea"
              :rows="2"
              placeholder="例如：请简洁准确回答问题"
            />
          </el-form-item>
        </template>

        <div class="advanced-toggle">
          <button
            type="button"
            class="btn btn--ghost btn--sm"
            :aria-expanded="showAdvanced"
            @click="showAdvanced = !showAdvanced"
          >
            <TdIcon :name="showAdvanced ? 'chevron-down' : 'chevron-right'" :size="13" />
            <span>并发、限流、重试与预算熔断参数</span>
          </button>
        </div>

        <div v-if="showAdvanced" class="advanced-box">
          <div class="form-grid-3">
            <el-form-item label="并发度 (1–64)">
              <el-input-number v-model="form.concurrency" :min="1" :max="64" style="width: 100%" />
            </el-form-item>
            <el-form-item label="最大尝试次数 (1–10)">
              <el-input-number v-model="form.maxAttempts" :min="1" :max="10" style="width: 100%" />
            </el-form-item>
            <el-form-item label="单次超时 (ms)">
              <el-input-number
                v-model="form.requestTimeoutMs"
                :min="1000"
                :max="300000"
                :step="1000"
                style="width: 100%"
              />
            </el-form-item>
          </div>

          <div class="form-grid-2">
            <el-form-item label="渠道限流速率 (req/s)">
              <el-input-number
                v-model="form.rateLimitPerSec"
                :min="0.5"
                :max="1000"
                :step="5"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="令牌桶突发容量 (burst)">
              <el-input-number
                v-model="form.rateLimitBurst"
                :min="1"
                :max="1000"
                style="width: 100%"
              />
            </el-form-item>
          </div>

          <div class="form-grid-2">
            <el-form-item label="费用预算上限 (USD，留空不限)">
              <el-input v-model="form.maxCostUsd" placeholder="例如 0.50" />
            </el-form-item>
            <el-form-item label="Token 预算上限（留空不限）">
              <el-input v-model="form.maxTotalTokens" placeholder="例如 50000" />
            </el-form-item>
          </div>
          <p class="form-hint">
            达到预算上限后将停止派发新用例，在途请求自然完成，剩余待执行用例标记为 SKIPPED（预算熔断）。
          </p>
        </div>
      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <button type="button" class="btn btn--secondary" :disabled="submitting" @click="requestCloseDialog">
            取消
          </button>
          <button type="button" class="btn btn--primary" :disabled="submitting" @click="handleCreate">
            <TdIcon name="play" :size="14" />
            <span>{{ submitting ? '启动中…' : '冻结快照并启动评测' }}</span>
          </button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.flag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.cell-sub {
  font-size: 12px;
  color: var(--td-text-secondary);
  margin-top: 2px;
}

.cell-muted {
  color: var(--td-text-secondary);
  white-space: nowrap;
}

.row-action-inline {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.dropdown-danger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--td-danger-text);
}

.form-grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.form-grid-3 {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.advanced-toggle {
  margin-bottom: 10px;
}

.advanced-box {
  padding: 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--td-border);
  background-color: var(--td-bg-subtle);
}

.form-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-text-secondary);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 640px) {
  .form-grid-2,
  .form-grid-3 {
    grid-template-columns: 1fr;
  }
}
</style>
