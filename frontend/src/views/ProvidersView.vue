<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createProviderApi,
  deleteProviderApi,
  smokeTestProviderApi,
  updateProviderApi,
  type ProviderType,
  type ProviderView,
  type SmokeTestResponse,
} from '../api/provider'
import { useConsoleDataStore } from '../stores/consoleData'
import TdIcon from '../components/TdIcon.vue'
import { copyText } from '../utils/format'

const store = useConsoleDataStore()

const providers = computed(() => store.providers)
const keyword = ref('')
const typeFilter = ref<'ALL' | ProviderType>('ALL')
const loadError = ref('')
const liveAnnouncement = ref('')

const showSlowHint = ref(false)
let slowTimer: number | null = null

interface SmokeSessionResult {
  state: 'testing' | 'ok' | 'timeout' | 'rate_limited' | 'err'
  latencyMs?: number
  errorType?: string
  errorMessage?: string | null
  testedAt?: string
  model?: string
}

// Session-only map: M1 backend has no persisted smoke-test history, so refresh resets to untested.
const smokeSessionMap = reactive<Record<number, SmokeSessionResult>>({})

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const editingApiKeyMask = ref<string | null>(null)
const advancedOpen = ref(false)
const formError = ref('')
const fieldErrors = reactive({
  name: '',
  defaultModel: '',
  baseUrl: '',
  apiKey: '',
  mockConfigJson: '',
  pricingJson: '',
})
const nameInputRef = ref<{ focus?: () => void } | null>(null)
const formAlertRef = ref<HTMLElement | null>(null)

const form = reactive({
  name: '',
  providerType: 'MOCK' as ProviderType,
  baseUrl: '',
  apiKey: '',
  defaultModel: 'mock-good',
  pricingJson: '',
  mockConfigJson: '{"profile":"mock-good","seed":1}',
})

let initialFormSnapshot = ''

const smokeVisible = ref(false)
const smokeLoading = ref(false)
const smokeTarget = ref<ProviderView | null>(null)
const smokePrompt = ref('你好，请用一句话介绍 TraceDelta。')
const smokeModel = ref('')
const smokeResult = ref<SmokeTestResponse | null>(null)
const smokeTestedAt = ref('')
const smokeRequestModel = ref('')
const smokeRequestError = ref('')

const VALID_MOCK_PROFILES = new Set(['mock-good', 'mock-sloppy', 'mock-slow', 'mock-flaky'])

const mockPresets = [
  {
    profile: 'mock-good',
    behavior: '稳定返回合法内容与标准 Token 统计',
    json: '{"profile":"mock-good","seed":1}',
  },
  {
    profile: 'mock-sloppy',
    behavior: '返回非规范/残缺 JSON，用于验证解析容错',
    json: '{"profile":"mock-sloppy","seed":1}',
  },
  {
    profile: 'mock-slow',
    behavior: '按设定 latencyMs 模拟慢响应（非故障）',
    json: '{"profile":"mock-slow","seed":1,"latencyMs":800}',
  },
  {
    profile: 'mock-flaky',
    behavior: '按 seed 与 failRate 确定性模拟间歇失败',
    json: '{"profile":"mock-flaky","seed":1,"failRate":0.5}',
  },
]

const promptPresets = [
  { label: '基础问答', text: '你好，请用一句话介绍 TraceDelta。' },
  { label: 'JSON 协议', text: '请仅返回合法 JSON，包含字段 status 和 latency_ok。' },
  { label: '虚拟线程', text: '简述 Java 21 虚拟线程在高并发 I/O 场景的优势。' },
]

const mockCount = computed(() => providers.value.filter((p) => p.providerType === 'MOCK').length)
const openAiCount = computed(
  () => providers.value.filter((p) => p.providerType === 'OPENAI_COMPATIBLE').length,
)

const filteredProviders = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return providers.value.filter((item) => {
    if (typeFilter.value !== 'ALL' && item.providerType !== typeFilter.value) return false
    if (!q) return true
    return (
      item.name.toLowerCase().includes(q) ||
      item.defaultModel.toLowerCase().includes(q) ||
      (item.baseUrl || '').toLowerCase().includes(q) ||
      (item.mockConfigJson || '').toLowerCase().includes(q)
    )
  })
})

function announce(text: string) {
  liveAnnouncement.value = ''
  nextTick(() => {
    liveAnnouncement.value = text
  })
}

function nowTimeString(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function formatTypeLabel(type: ProviderType): string {
  return type === 'MOCK' ? 'Mock' : 'OpenAI 兼容'
}

function describeMockConfig(item: ProviderView): { profile: string; params: string; behavior: string } {
  let profile = item.defaultModel || 'mock-good'
  const parts: string[] = []
  if (item.mockConfigJson) {
    try {
      const parsed = JSON.parse(item.mockConfigJson) as Record<string, unknown>
      if (typeof parsed.profile === 'string' && parsed.profile.trim()) {
        profile = parsed.profile.trim()
      }
      if (parsed.seed !== undefined) parts.push(`seed=${parsed.seed}`)
      if (parsed.latencyMs !== undefined) parts.push(`latencyMs=${parsed.latencyMs}`)
      if (parsed.failRate !== undefined) parts.push(`failRate=${parsed.failRate}`)
    } catch {
      parts.push(item.mockConfigJson)
    }
  }
  const preset = mockPresets.find((p) => p.profile === profile)
  return {
    profile,
    params: parts.join(' · '),
    behavior: preset?.behavior || '自定义 Mock 配置',
  }
}

function getConfigWarning(item: ProviderView): string | null {
  if (item.providerType === 'OPENAI_COMPATIBLE') {
    const missing: string[] = []
    if (!item.baseUrl?.trim()) missing.push('Base URL')
    if (!item.apiKeyMask?.trim()) missing.push('API Key')
    if (!item.defaultModel?.trim()) missing.push('默认模型')
    return missing.length > 0 ? `缺少：${missing.join('、')}` : null
  }
  if (!item.defaultModel?.trim()) return '缺少默认模型'
  return null
}

function mapErrorTypeLabel(errorType?: string): string {
  switch (errorType) {
    case 'NONE':
      return '成功'
    case 'TIMEOUT':
      return '超时'
    case 'RATE_LIMITED':
      return '触发限流'
    case 'HTTP_ERROR':
      return 'HTTP 错误'
    case 'MALFORMED_RESPONSE':
      return '响应格式异常'
    default:
      return errorType || '请求失败'
  }
}

function takeFormSnapshot(): string {
  return JSON.stringify({
    name: form.name,
    providerType: form.providerType,
    baseUrl: form.baseUrl,
    apiKey: form.apiKey,
    defaultModel: form.defaultModel,
    pricingJson: form.pricingJson,
    mockConfigJson: form.mockConfigJson,
  })
}

function clearFieldErrors() {
  formError.value = ''
  fieldErrors.name = ''
  fieldErrors.defaultModel = ''
  fieldErrors.baseUrl = ''
  fieldErrors.apiKey = ''
  fieldErrors.mockConfigJson = ''
  fieldErrors.pricingJson = ''
}

function validateBaseUrlField(): boolean {
  fieldErrors.baseUrl = ''
  if (form.providerType !== 'OPENAI_COMPATIBLE') return true
  const raw = form.baseUrl.trim()
  if (!raw) {
    fieldErrors.baseUrl = 'OpenAI 兼容渠道必须填写 Base URL'
    return false
  }
  try {
    const url = new URL(raw)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      fieldErrors.baseUrl = 'Base URL 必须以 http:// 或 https:// 开头'
      return false
    }
  } catch {
    fieldErrors.baseUrl = 'Base URL 不是合法的 http/https 地址'
    return false
  }
  return true
}

function validateMockConfigField(): boolean {
  fieldErrors.mockConfigJson = ''
  if (form.providerType !== 'MOCK' || !form.mockConfigJson.trim()) return true
  let parsed: unknown
  try {
    parsed = JSON.parse(form.mockConfigJson)
  } catch {
    fieldErrors.mockConfigJson = 'Mock 配置不是合法的 JSON'
    return false
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    fieldErrors.mockConfigJson = 'Mock 配置必须是 JSON 对象（{...}）'
    return false
  }
  const obj = parsed as Record<string, unknown>
  if (obj.profile !== undefined && obj.profile !== null) {
    const normalized = String(obj.profile).trim().toLowerCase().replace(/_/g, '-')
    if (!VALID_MOCK_PROFILES.has(normalized)) {
      fieldErrors.mockConfigJson = 'profile 仅支持 mock-good / mock-sloppy / mock-slow / mock-flaky'
      return false
    }
  }
  if (obj.latencyMs !== undefined && obj.latencyMs !== null) {
    if (typeof obj.latencyMs !== 'number' || Number.isNaN(obj.latencyMs) || obj.latencyMs < 0) {
      fieldErrors.mockConfigJson = 'latencyMs 必须为非负数字'
      return false
    }
  }
  if (obj.failRate !== undefined && obj.failRate !== null) {
    if (
      typeof obj.failRate !== 'number' ||
      Number.isNaN(obj.failRate) ||
      obj.failRate < 0 ||
      obj.failRate > 1
    ) {
      fieldErrors.mockConfigJson = 'failRate 必须在 0 到 1 之间'
      return false
    }
  }
  return true
}

function validatePricingField(): boolean {
  fieldErrors.pricingJson = ''
  if (!form.pricingJson.trim()) return true
  let parsed: unknown
  try {
    parsed = JSON.parse(form.pricingJson)
  } catch {
    fieldErrors.pricingJson = '定价配置不是合法的 JSON'
    return false
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    fieldErrors.pricingJson = '定价配置必须是 JSON 对象（{...}）'
    return false
  }
  const obj = parsed as Record<string, unknown>
  for (const key of ['inputPer1k', 'outputPer1k']) {
    if (obj[key] !== undefined && obj[key] !== null) {
      if (typeof obj[key] !== 'number' || Number.isNaN(obj[key]) || (obj[key] as number) < 0) {
        fieldErrors.pricingJson = `${key} 必须为非负数字`
        return false
      }
    }
  }
  return true
}

async function refresh() {
  loadError.value = ''
  if (!store.providersLoaded) {
    slowTimer = window.setTimeout(() => {
      showSlowHint.value = true
    }, 180)
  }
  try {
    await store.fetchProviders()
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '加载模型渠道列表失败'
  } finally {
    if (slowTimer !== null) {
      window.clearTimeout(slowTimer)
      slowTimer = null
    }
    showSlowHint.value = false
  }
}

function openCreate(presetProfile?: string) {
  editingId.value = null
  editingApiKeyMask.value = null
  clearFieldErrors()
  form.name = presetProfile ? `${presetProfile}-local` : ''
  form.providerType = 'MOCK'
  form.baseUrl = ''
  form.apiKey = ''
  form.defaultModel = presetProfile || 'mock-good'
  form.pricingJson = ''
  const preset = mockPresets.find((p) => p.profile === presetProfile)
  form.mockConfigJson = preset ? preset.json : '{"profile":"mock-good","seed":1}'
  advancedOpen.value = false
  initialFormSnapshot = takeFormSnapshot()
  dialogVisible.value = true
}

function openEdit(row: ProviderView) {
  editingId.value = row.id
  editingApiKeyMask.value = row.apiKeyMask || null
  clearFieldErrors()
  form.name = row.name
  form.providerType = row.providerType
  form.baseUrl = row.baseUrl || ''
  form.apiKey = ''
  form.defaultModel = row.defaultModel
  form.pricingJson = row.pricingJson || ''
  form.mockConfigJson = row.mockConfigJson || ''
  advancedOpen.value = false
  initialFormSnapshot = takeFormSnapshot()
  dialogVisible.value = true
}

function onDialogOpened() {
  nextTick(() => {
    nameInputRef.value?.focus?.()
  })
}

function switchProviderType(nextType: ProviderType) {
  if (form.providerType === nextType) return
  form.providerType = nextType
  clearFieldErrors()
  if (nextType === 'MOCK') {
    if (!VALID_MOCK_PROFILES.has(form.defaultModel.trim().toLowerCase())) {
      form.defaultModel = 'mock-good'
    }
    if (!form.mockConfigJson.trim()) {
      form.mockConfigJson = '{"profile":"mock-good","seed":1}'
    }
  } else {
    if (VALID_MOCK_PROFILES.has(form.defaultModel.trim().toLowerCase())) {
      form.defaultModel = ''
    }
  }
}

function applyMockPreset(profile: string) {
  const found = mockPresets.find((p) => p.profile === profile)
  form.providerType = 'MOCK'
  form.defaultModel = profile
  form.mockConfigJson = found ? found.json : `{"profile":"${profile}","seed":1}`
  fieldErrors.mockConfigJson = ''
  if (!form.name.trim()) form.name = `${profile}-local`
}

async function handleDialogBeforeClose(done: () => void) {
  if (saving.value) return
  if (takeFormSnapshot() !== initialFormSnapshot) {
    try {
      await ElMessageBox.confirm('当前表单有未保存的修改，确定放弃并关闭吗？', '放弃修改', {
        confirmButtonText: '放弃并关闭',
        cancelButtonText: '继续编辑',
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

async function handleSave() {
  if (saving.value) return
  clearFieldErrors()

  let ok = true
  if (!form.name.trim()) {
    fieldErrors.name = '请输入渠道名称'
    ok = false
  }
  if (!form.defaultModel.trim()) {
    fieldErrors.defaultModel = '请输入默认模型名称'
    ok = false
  }
  if (!validateBaseUrlField()) ok = false

  if (form.providerType === 'OPENAI_COMPATIBLE') {
    const needsKey = editingId.value === null || !editingApiKeyMask.value
    if (needsKey && !form.apiKey.trim()) {
      fieldErrors.apiKey = 'OpenAI 兼容渠道必须填写 API Key'
      ok = false
    }
  }

  const mockOk = validateMockConfigField()
  const pricingOk = validatePricingField()
  if (!mockOk || !pricingOk) {
    advancedOpen.value = true
    ok = false
  }

  if (!ok) {
    formError.value = '请修正表单中的标注项后再保存。'
    await nextTick()
    formAlertRef.value?.focus()
    return
  }

  saving.value = true
  try {
    // Strictly send only fields applicable to the active providerType
    const payload =
      form.providerType === 'MOCK'
        ? {
            name: form.name.trim(),
            providerType: 'MOCK' as ProviderType,
            baseUrl: null,
            apiKey: null,
            defaultModel: form.defaultModel.trim(),
            pricingJson: form.pricingJson.trim() || null,
            mockConfigJson: form.mockConfigJson.trim() || null,
          }
        : {
            name: form.name.trim(),
            providerType: 'OPENAI_COMPATIBLE' as ProviderType,
            baseUrl: form.baseUrl.trim(),
            apiKey: form.apiKey.trim() || null,
            defaultModel: form.defaultModel.trim(),
            pricingJson: form.pricingJson.trim() || null,
            mockConfigJson: null,
          }

    if (editingId.value === null) {
      const created = await createProviderApi(payload)
      ElMessage.success(`已创建渠道「${created.name}」`)
      announce(`已创建模型渠道 ${created.name}`)
    } else {
      const updated = await updateProviderApi(editingId.value, payload)
      ElMessage.success(`已更新渠道「${updated.name}」`)
      announce(`已更新模型渠道 ${updated.name}`)
    }
    dialogVisible.value = false
    await store.fetchProviders()
  } catch (err) {
    formError.value = err instanceof Error ? err.message : '保存渠道失败，请检查配置后重试'
    await nextTick()
    formAlertRef.value?.focus()
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: ProviderView) {
  try {
    await ElMessageBox.confirm(
      `确认删除「${row.name}」（${formatTypeLabel(row.providerType)} · ${row.defaultModel}）？此操作不可撤销。`,
      '删除模型渠道',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }
  await deleteProviderApi(row.id)
  delete smokeSessionMap[row.id]
  ElMessage.success(`已删除「${row.name}」`)
  announce(`已删除模型渠道 ${row.name}`)
  await store.fetchProviders()
}

function handleMenu(command: string, row: ProviderView) {
  if (command === 'edit') openEdit(row)
  else if (command === 'delete') handleDelete(row)
}

function openSmokeTest(row: ProviderView) {
  smokeTarget.value = row
  smokeModel.value = row.defaultModel
  smokeResult.value = null
  smokeRequestError.value = ''
  smokeTestedAt.value = ''
  smokeRequestModel.value = ''
  smokeVisible.value = true
}

async function runSmokeTest() {
  if (!smokeTarget.value || smokeLoading.value) return
  if (!smokePrompt.value.trim()) {
    smokeRequestError.value = '请输入测试 Prompt'
    return
  }
  smokeRequestError.value = ''
  const target = smokeTarget.value
  const reqModel = smokeModel.value.trim() || target.defaultModel
  smokeLoading.value = true
  smokeSessionMap[target.id] = { state: 'testing', model: reqModel }
  try {
    const res = await smokeTestProviderApi(target.id, smokePrompt.value.trim(), reqModel)
    const testedAt = nowTimeString()
    smokeResult.value = res
    smokeTestedAt.value = testedAt
    smokeRequestModel.value = reqModel

    if (res.errorType === 'NONE') {
      smokeSessionMap[target.id] = {
        state: 'ok',
        latencyMs: res.latencyMs,
        testedAt,
        model: reqModel,
      }
      announce(`${target.name} 本次冒烟测试成功，耗时 ${res.latencyMs} 毫秒`)
    } else {
      const nextState =
        res.errorType === 'TIMEOUT'
          ? 'timeout'
          : res.errorType === 'RATE_LIMITED'
            ? 'rate_limited'
            : 'err'
      smokeSessionMap[target.id] = {
        state: nextState,
        latencyMs: res.latencyMs,
        errorType: res.errorType,
        errorMessage: res.errorMessage,
        testedAt,
        model: reqModel,
      }
      announce(`${target.name} 本次冒烟测试未通过：${mapErrorTypeLabel(res.errorType)}`)
    }
  } catch (err) {
    const testedAt = nowTimeString()
    const message = err instanceof Error ? err.message : '请求未能到达后端服务'
    smokeRequestError.value = message
    smokeSessionMap[target.id] = {
      state: 'err',
      errorType: 'REQUEST_FAILED',
      errorMessage: message,
      testedAt,
      model: reqModel,
    }
    announce(`${target.name} 本次冒烟请求失败`)
  } finally {
    smokeLoading.value = false
  }
}

const smokeOutputText = computed(() => {
  if (!smokeResult.value) return ''
  return smokeResult.value.errorType === 'NONE'
    ? smokeResult.value.content || '(空响应)'
    : smokeResult.value.errorMessage || mapErrorTypeLabel(smokeResult.value.errorType)
})

onMounted(() => {
  refresh()
})

onBeforeUnmount(() => {
  if (slowTimer !== null) window.clearTimeout(slowTimer)
})
</script>

<template>
  <div class="view-stack">
    <div class="sr-only" aria-live="polite">{{ liveAnnouncement }}</div>

    <header class="view-header">
      <h1 class="view-title">模型渠道</h1>
      <button type="button" class="btn btn--primary" @click="openCreate()">
        <TdIcon name="plus" :size="14" />
        <span>新增渠道</span>
      </button>
    </header>

    <div class="view-toolbar">
      <div class="view-toolbar-group">
        <div class="seg-control" role="radiogroup" aria-label="按渠道类型筛选">
          <button
            type="button"
            role="radio"
            class="seg-btn"
            :class="{ 'is-active': typeFilter === 'ALL' }"
            :aria-checked="typeFilter === 'ALL'"
            @click="typeFilter = 'ALL'"
          >
            <span>全部</span>
            <span class="seg-count mono">{{ providers.length }}</span>
          </button>
          <button
            type="button"
            role="radio"
            class="seg-btn"
            :class="{ 'is-active': typeFilter === 'MOCK' }"
            :aria-checked="typeFilter === 'MOCK'"
            @click="typeFilter = 'MOCK'"
          >
            <span>Mock</span>
            <span class="seg-count mono">{{ mockCount }}</span>
          </button>
          <button
            type="button"
            role="radio"
            class="seg-btn"
            :class="{ 'is-active': typeFilter === 'OPENAI_COMPATIBLE' }"
            :aria-checked="typeFilter === 'OPENAI_COMPATIBLE'"
            @click="typeFilter = 'OPENAI_COMPATIBLE'"
          >
            <span>OpenAI 兼容</span>
            <span class="seg-count mono">{{ openAiCount }}</span>
          </button>
        </div>

        <div class="search-box">
          <TdIcon name="search" :size="14" class="search-box-icon" />
          <input
            v-model="keyword"
            type="search"
            class="search-box-input"
            placeholder="按名称、模型或 Endpoint 搜索…"
            aria-label="按名称、模型或 Endpoint 搜索渠道"
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

      <span v-if="store.providersLoaded" class="view-count mono" aria-live="polite">
        {{ filteredProviders.length }}<template v-if="keyword.trim() || typeFilter !== 'ALL'"> / {{ providers.length }}</template> 个渠道
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

    <div v-if="!store.providersLoaded && !loadError">
      <p v-if="showSlowHint" class="slow-loading" aria-live="polite">正在加载模型渠道…</p>
    </div>

    <section v-else-if="store.providersLoaded && providers.length === 0" class="empty-panel">
      <div class="empty-panel-icon" aria-hidden="true">
        <TdIcon name="cpu" :size="18" />
      </div>
      <h2>尚无模型渠道</h2>
      <p>可先创建本地零费用的 <span class="mono">mock-good</span> 渠道，或配置 OpenAI 兼容端点。</p>
      <div class="empty-panel-actions">
        <button type="button" class="btn btn--primary" @click="openCreate('mock-good')">
          <TdIcon name="plus" :size="14" />
          <span>创建 mock-good</span>
        </button>
        <button type="button" class="btn btn--secondary" @click="openCreate()">自定义渠道</button>
      </div>
    </section>

    <section v-else-if="store.providersLoaded && filteredProviders.length === 0" class="empty-panel">
      <h2>无匹配渠道</h2>
      <p>当前筛选条件下未找到匹配的模型渠道。</p>
      <button
        type="button"
        class="btn btn--secondary"
        @click="
          () => {
            keyword = ''
            typeFilter = 'ALL'
          }
        "
      >
        重置筛选条件
      </button>
    </section>

    <div v-else-if="store.providersLoaded" class="data-table-wrap">
      <table class="data-table" aria-label="模型渠道列表">
        <thead>
          <tr>
            <th scope="col">渠道名称与类型</th>
            <th scope="col">模型与配置</th>
            <th scope="col">本次冒烟测试</th>
            <th scope="col" class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in filteredProviders" :key="item.id">
            <td class="provider-name-cell">
              <div class="provider-name-line">
                <span class="provider-name" :title="item.name">{{ item.name }}</span>
                <span class="micro-badge">{{ formatTypeLabel(item.providerType) }}</span>
              </div>
              <div v-if="getConfigWarning(item)" class="provider-warn-line">
                <span class="status-tag status-tag--warning">
                  <TdIcon name="alert-triangle" :size="12" />
                  <span>{{ getConfigWarning(item) }}</span>
                </span>
              </div>
            </td>

            <td class="provider-config-cell">
              <div class="config-primary">
                <span class="config-model mono" :title="`默认模型：${item.defaultModel}`">
                  {{ item.defaultModel }}
                </span>
                <span
                  v-if="item.providerType === 'MOCK'"
                  class="config-behavior"
                >
                  {{ describeMockConfig(item).behavior }}
                </span>
              </div>
              <div class="config-pills">
                <template v-if="item.providerType === 'OPENAI_COMPATIBLE'">
                  <span class="code-pill" :title="item.baseUrl || '未配置 Base URL'">
                    <TdIcon name="globe" :size="12" class="code-pill-icon" />
                    <span class="code-pill-text">{{ item.baseUrl || '未配置 Base URL' }}</span>
                  </span>
                  <span
                    class="code-pill"
                    :title="item.apiKeyMask ? '服务端加密存储，此处仅显示掩码' : '未配置 API Key'"
                  >
                    <TdIcon name="key" :size="12" class="code-pill-icon" />
                    <span>{{ item.apiKeyMask || '未配置 Key' }}</span>
                  </span>
                </template>
                <template v-else>
                  <span class="code-pill" :title="item.mockConfigJson || ''">
                    <TdIcon name="terminal" :size="12" class="code-pill-icon" />
                    <span class="code-pill-text">
                      {{ describeMockConfig(item).profile
                      }}<template v-if="describeMockConfig(item).params">
                        · {{ describeMockConfig(item).params }}</template
                      >
                    </span>
                  </span>
                </template>
              </div>
            </td>

            <td class="provider-smoke-cell">
              <template v-if="smokeSessionMap[item.id]?.state === 'testing'">
                <span class="status-tag">
                  <TdIcon name="clock" :size="12" />
                  <span>本次测试中…</span>
                </span>
              </template>
              <template v-else-if="smokeSessionMap[item.id]?.state === 'ok'">
                <span class="status-tag status-tag--success">
                  <TdIcon name="check-circle" :size="12" />
                  <span>
                    本次成功 · <span class="mono">{{ smokeSessionMap[item.id].latencyMs }}ms</span>
                  </span>
                </span>
                <div class="smoke-meta mono">
                  {{ smokeSessionMap[item.id].testedAt }} · {{ smokeSessionMap[item.id].model }}
                </div>
              </template>
              <template v-else-if="smokeSessionMap[item.id]?.state === 'timeout'">
                <span class="status-tag status-tag--danger">
                  <TdIcon name="alert-circle" :size="12" />
                  <span>本次超时</span>
                </span>
                <div class="smoke-meta mono">{{ smokeSessionMap[item.id].testedAt }}</div>
              </template>
              <template v-else-if="smokeSessionMap[item.id]?.state === 'rate_limited'">
                <span class="status-tag status-tag--warning">
                  <TdIcon name="alert-triangle" :size="12" />
                  <span>本次限流</span>
                </span>
                <div class="smoke-meta mono">{{ smokeSessionMap[item.id].testedAt }}</div>
              </template>
              <template v-else-if="smokeSessionMap[item.id]?.state === 'err'">
                <span
                  class="status-tag status-tag--danger"
                  :title="smokeSessionMap[item.id].errorMessage || ''"
                >
                  <TdIcon name="alert-circle" :size="12" />
                  <span>本次失败 · {{ mapErrorTypeLabel(smokeSessionMap[item.id].errorType) }}</span>
                </span>
                <div class="smoke-meta mono">{{ smokeSessionMap[item.id].testedAt }}</div>
              </template>
              <template v-else>
                <span class="smoke-untested">本次会话未测试</span>
              </template>
            </td>

            <td class="col-actions">
              <div class="table-actions">
                <button
                  type="button"
                  class="btn btn--secondary btn--sm"
                  :disabled="smokeSessionMap[item.id]?.state === 'testing'"
                  @click="openSmokeTest(item)"
                >
                  <TdIcon name="play" :size="12" />
                  <span>冒烟测试</span>
                </button>

                <el-dropdown
                  trigger="click"
                  placement="bottom-end"
                  @command="(cmd: string) => handleMenu(cmd, item)"
                >
                  <button
                    type="button"
                    class="btn btn--icon"
                    :aria-label="`更多操作：${item.name}`"
                    title="更多操作"
                  >
                    <TdIcon name="more-horizontal" :size="16" />
                  </button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="edit">
                        <TdIcon name="edit" :size="13" />
                        <span>编辑配置</span>
                      </el-dropdown-item>
                      <el-dropdown-item command="delete" class="menu-danger">
                        <TdIcon name="trash" :size="13" />
                        <span>删除渠道</span>
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

    <!-- Create / Edit Provider Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增模型渠道' : '编辑模型渠道'"
      width="540px"
      :before-close="handleDialogBeforeClose"
      @opened="onDialogOpened"
    >
      <div
        v-if="formError"
        ref="formAlertRef"
        class="form-alert"
        role="alert"
        tabindex="-1"
      >
        <TdIcon name="alert-circle" :size="15" />
        <span>{{ formError }}</span>
      </div>

      <el-form label-position="top">
        <el-form-item label="渠道名称" required>
          <el-input
            ref="nameInputRef"
            v-model="form.name"
            placeholder="例如 mock-good-local 或 deepseek-chat-prod"
            maxlength="128"
            @input="fieldErrors.name = ''"
          />
          <p v-if="fieldErrors.name" class="field-error-text" role="alert">{{ fieldErrors.name }}</p>
        </el-form-item>

        <el-form-item label="渠道类型" required>
          <div class="seg-control" role="radiogroup" aria-label="选择渠道类型">
            <button
              type="button"
              role="radio"
              class="seg-btn"
              :class="{ 'is-active': form.providerType === 'MOCK' }"
              :aria-checked="form.providerType === 'MOCK'"
              @click="switchProviderType('MOCK')"
            >
              Mock（本地确定性）
            </button>
            <button
              type="button"
              role="radio"
              class="seg-btn"
              :class="{ 'is-active': form.providerType === 'OPENAI_COMPATIBLE' }"
              :aria-checked="form.providerType === 'OPENAI_COMPATIBLE'"
              @click="switchProviderType('OPENAI_COMPATIBLE')"
            >
              OpenAI 兼容
            </button>
          </div>
        </el-form-item>

        <template v-if="form.providerType === 'MOCK'">
          <el-form-item label="预设档位">
            <div class="preset-grid">
              <button
                v-for="preset in mockPresets"
                :key="preset.profile"
                type="button"
                class="preset-tile"
                :class="{ 'is-selected': form.defaultModel === preset.profile }"
                :aria-pressed="form.defaultModel === preset.profile"
                @click="applyMockPreset(preset.profile)"
              >
                <span class="preset-name mono">{{ preset.profile }}</span>
                <span class="preset-desc">{{ preset.behavior }}</span>
              </button>
            </div>
          </el-form-item>

          <el-form-item label="默认模型" required>
            <el-input
              v-model="form.defaultModel"
              class="mono-field"
              placeholder="mock-good"
              @input="fieldErrors.defaultModel = ''"
            />
            <p v-if="fieldErrors.defaultModel" class="field-error-text" role="alert">
              {{ fieldErrors.defaultModel }}
            </p>
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="默认模型" required>
            <el-input
              v-model="form.defaultModel"
              class="mono-field"
              placeholder="例如 deepseek-chat / gpt-4o-mini"
              @input="fieldErrors.defaultModel = ''"
            />
            <p v-if="fieldErrors.defaultModel" class="field-error-text" role="alert">
              {{ fieldErrors.defaultModel }}
            </p>
          </el-form-item>

          <el-form-item label="Base URL" required>
            <el-input
              v-model="form.baseUrl"
              class="mono-field"
              placeholder="https://api.deepseek.com/v1"
              @blur="validateBaseUrlField"
              @input="fieldErrors.baseUrl = ''"
            />
            <p v-if="fieldErrors.baseUrl" class="field-error-text" role="alert">{{ fieldErrors.baseUrl }}</p>
          </el-form-item>

          <el-form-item :label="editingId === null ? 'API Key' : '更新 API Key（可选）'" :required="editingId === null || !editingApiKeyMask">
            <el-input
              v-model="form.apiKey"
              type="password"
              show-password
              autocomplete="new-password"
              class="mono-field"
              :placeholder="
                editingId === null || !editingApiKeyMask
                  ? '输入以 sk- 开头的密钥（服务端 AES-GCM 加密存储）'
                  : '留空将保留当前已加密存储的原密钥'
              "
              @input="fieldErrors.apiKey = ''"
            />
            <p v-if="editingId !== null && editingApiKeyMask" class="field-help-text">
              当前已存储掩码：<span class="mono">{{ editingApiKeyMask }}</span>（不会回填明文；留空保持不变）
            </p>
            <p v-if="fieldErrors.apiKey" class="field-error-text" role="alert">{{ fieldErrors.apiKey }}</p>
          </el-form-item>
        </template>

        <div class="advanced-section">
          <button
            type="button"
            class="advanced-toggle"
            :aria-expanded="advancedOpen"
            @click="advancedOpen = !advancedOpen"
          >
            <TdIcon
              name="chevron-down"
              :size="14"
              class="advanced-chevron"
              :class="{ 'is-open': advancedOpen }"
            />
            <span>高级配置（{{ form.providerType === 'MOCK' ? 'Mock 参数 JSON 与定价 JSON' : '定价 JSON' }}）</span>
          </button>

          <div v-if="advancedOpen" class="advanced-body">
            <el-form-item v-if="form.providerType === 'MOCK'" label="Mock 配置 JSON（可选）">
              <el-input
                v-model="form.mockConfigJson"
                type="textarea"
                :rows="3"
                class="mono-field"
                placeholder='{"profile":"mock-good","seed":1,"latencyMs":0,"failRate":0}'
                @blur="validateMockConfigField"
              />
              <p v-if="fieldErrors.mockConfigJson" class="field-error-text" role="alert">
                {{ fieldErrors.mockConfigJson }}
              </p>
              <p v-else class="field-help-text">
                支持字段：<span class="mono">profile</span>、<span class="mono">seed</span>、<span class="mono">latencyMs</span>（≥ 0）、<span class="mono">failRate</span>（0–1）。
              </p>
            </el-form-item>

            <el-form-item label="定价配置 JSON（可选）">
              <el-input
                v-model="form.pricingJson"
                type="textarea"
                :rows="2"
                class="mono-field"
                placeholder='{"inputPer1k":0.001,"outputPer1k":0.002}'
                @blur="validatePricingField"
              />
              <p v-if="fieldErrors.pricingJson" class="field-error-text" role="alert">
                {{ fieldErrors.pricingJson }}
              </p>
              <p v-else class="field-help-text">
                用于估算单次调用费用，<span class="mono">inputPer1k</span> 与 <span class="mono">outputPer1k</span> 须为非负数字。
              </p>
            </el-form-item>
          </div>
        </div>
      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <button
            type="button"
            class="btn btn--secondary btn--sm"
            :disabled="saving"
            @click="requestCloseDialog"
          >
            取消
          </button>
          <button
            type="button"
            class="btn btn--primary btn--sm"
            :disabled="saving"
            :aria-busy="saving"
            @click="handleSave"
          >
            {{ saving ? '保存中…' : '保存配置' }}
          </button>
        </div>
      </template>
    </el-dialog>

    <!-- Single-call Smoke Test Drawer -->
    <el-drawer
      v-model="smokeVisible"
      :title="smokeTarget ? `单次冒烟测试 · ${smokeTarget.name}` : '单次冒烟测试'"
      size="500px"
    >
      <div v-if="smokeTarget" class="drawer-stack">
        <div class="drawer-target">
          <div class="drawer-target-top">
            <span class="drawer-target-name">{{ smokeTarget.name }}</span>
            <span class="micro-badge">{{ formatTypeLabel(smokeTarget.providerType) }}</span>
            <span class="micro-badge mono">{{ smokeTarget.defaultModel }}</span>
          </div>
          <p class="drawer-target-note">
            <template v-if="smokeTarget.providerType === 'MOCK'">
              本地确定性 Mock 单次调用（零费用）；测试结果仅保留在当前会话，刷新页面后重置。
            </template>
            <template v-else>
              将向 <span class="mono">{{ smokeTarget.baseUrl }}</span> 发送一次真实请求（超时 30s），可能产生外部 API 费用；结果仅在当前会话展示。
            </template>
          </p>
        </div>

        <div v-if="smokeRequestError" class="form-alert" role="alert">
          <TdIcon name="alert-circle" :size="15" />
          <span>{{ smokeRequestError }}</span>
        </div>

        <el-form label-position="top">
          <el-form-item label="请求模型">
            <el-input v-model="smokeModel" class="mono-field" />
          </el-form-item>

          <el-form-item label="测试 Prompt">
            <div class="prompt-pills">
              <button
                v-for="p in promptPresets"
                :key="p.label"
                type="button"
                class="prompt-pill"
                @click="smokePrompt = p.text"
              >
                {{ p.label }}
              </button>
            </div>
            <el-input v-model="smokePrompt" type="textarea" :rows="4" />
          </el-form-item>

          <button
            type="button"
            class="btn btn--primary"
            :disabled="smokeLoading"
            :aria-busy="smokeLoading"
            @click="runSmokeTest"
          >
            <TdIcon name="play" :size="13" />
            <span>{{ smokeLoading ? '正在请求…' : '执行单次冒烟测试' }}</span>
          </button>
        </el-form>

        <div v-if="smokeResult" class="smoke-result" aria-live="polite">
          <div class="smoke-result-head">
            <span
              class="status-tag"
              :class="
                smokeResult.errorType === 'NONE'
                  ? 'status-tag--success'
                  : smokeResult.errorType === 'RATE_LIMITED'
                    ? 'status-tag--warning'
                    : 'status-tag--danger'
              "
            >
              <TdIcon
                :name="
                  smokeResult.errorType === 'NONE'
                    ? 'check-circle'
                    : smokeResult.errorType === 'RATE_LIMITED'
                      ? 'alert-triangle'
                      : 'alert-circle'
                "
                :size="12"
              />
              <span>
                本次测试：{{ mapErrorTypeLabel(smokeResult.errorType) }}
                <template v-if="smokeResult.errorType !== 'NONE'">
                  （<span class="mono">{{ smokeResult.errorType }}</span>）
                </template>
              </span>
            </span>
            <span class="smoke-result-time mono">{{ smokeTestedAt }} · {{ smokeRequestModel }}</span>
          </div>

          <div class="metric-grid">
            <div class="metric-cell">
              <span class="metric-label">单次耗时</span>
              <span class="metric-val mono">{{ smokeResult.latencyMs }} ms</span>
            </div>
            <div class="metric-cell">
              <span class="metric-label">Tokens（入 / 出）</span>
              <span class="metric-val mono">{{ smokeResult.inputTokens }} / {{ smokeResult.outputTokens }}</span>
            </div>
            <div class="metric-cell">
              <span class="metric-label">估算费用</span>
              <span class="metric-val mono">{{ smokeResult.estimatedCost }}</span>
            </div>
            <div class="metric-cell">
              <span class="metric-label">错误码</span>
              <span class="metric-val mono">{{ smokeResult.errorType }}</span>
            </div>
          </div>

          <div class="code-box">
            <div class="code-box-head">
              <span>{{ smokeResult.errorType === 'NONE' ? '真实响应内容' : '错误详情' }}</span>
              <button type="button" class="code-box-copy" @click="copyText(smokeOutputText, '已复制内容')">
                <TdIcon name="copy" :size="12" />
                <span>复制</span>
              </button>
            </div>
            <pre class="code-box-pre mono">{{ smokeOutputText }}</pre>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.provider-name-cell {
  min-width: 190px;
  max-width: 260px;
}

.provider-name-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.provider-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--td-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}

.provider-warn-line {
  margin-top: 6px;
}

.provider-config-cell {
  min-width: 280px;
}

.config-primary {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.config-model {
  font-size: 13px;
  font-weight: 600;
  color: var(--td-text-primary);
}

.config-behavior {
  font-size: 12px;
  color: var(--td-text-secondary);
}

.config-pills {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.provider-smoke-cell {
  min-width: 170px;
  white-space: nowrap;
}

.smoke-untested {
  font-size: 12.5px;
  color: var(--td-text-secondary);
}

.smoke-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--td-text-secondary);
}

.table-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* Mock preset tiles with clear behavior descriptions */
.preset-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  width: 100%;
}

.preset-tile {
  appearance: none;
  border: 1px solid var(--td-border-strong);
  background-color: var(--td-bg-surface);
  border-radius: var(--radius-sm);
  padding: 9px 11px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  text-align: left;
  font-family: inherit;
  transition: border-color var(--dur-fast) var(--ease), background-color var(--dur-fast) var(--ease);
}

.preset-tile:hover {
  border-color: var(--td-text-secondary);
  background-color: var(--td-bg-row-hover);
}

.preset-tile.is-selected {
  border-color: var(--td-focus);
  box-shadow: 0 0 0 1px var(--td-focus) inset;
  background-color: var(--td-bg-subtle);
}

.preset-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--td-text-primary);
}

.preset-desc {
  font-size: 12px;
  line-height: 1.4;
  color: var(--td-text-secondary);
}

/* Collapsible advanced configuration */
.advanced-section {
  border-top: 1px solid var(--td-border);
  padding-top: 12px;
  margin-top: 4px;
}

.advanced-toggle {
  appearance: none;
  border: none;
  background: transparent;
  padding: 4px 2px;
  border-radius: var(--radius-xs);
  color: var(--td-text-secondary);
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.advanced-toggle:hover {
  color: var(--td-text-primary);
}

.advanced-chevron {
  transition: transform var(--dur-fast) var(--ease);
  transform: rotate(-90deg);
}

.advanced-chevron.is-open {
  transform: rotate(0deg);
}

.advanced-body {
  margin-top: 12px;
}

.mono-field :deep(.el-input__inner),
.mono-field :deep(.el-textarea__inner) {
  font-family: var(--font-mono);
  font-size: 12.5px;
}

/* Smoke drawer */
.drawer-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.drawer-target {
  padding: 11px 12px;
  border-radius: var(--radius-sm);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.drawer-target-top {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.drawer-target-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--td-text-primary);
}

.drawer-target-note {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.45;
  color: var(--td-text-secondary);
}

.prompt-pills {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.prompt-pill {
  appearance: none;
  border: 1px solid var(--td-border-strong);
  background-color: var(--td-bg-surface);
  color: var(--td-text-secondary);
  padding: 4px 10px;
  border-radius: var(--radius-xs);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.prompt-pill:hover {
  border-color: var(--td-text-secondary);
  color: var(--td-text-primary);
}

.smoke-result {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 14px;
  border-top: 1px solid var(--td-border);
}

.smoke-result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.smoke-result-time {
  font-size: 12px;
  color: var(--td-text-secondary);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.metric-cell {
  padding: 9px 11px;
  border-radius: var(--radius-sm);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border);
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.metric-label {
  font-size: 12px;
  color: var(--td-text-secondary);
}

.metric-val {
  font-size: 13px;
  font-weight: 600;
  color: var(--td-text-primary);
}

@media (max-width: 640px) {
  .preset-grid {
    grid-template-columns: 1fr;
  }
}
</style>
