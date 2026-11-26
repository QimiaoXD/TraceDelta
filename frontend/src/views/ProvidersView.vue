<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createProviderApi,
  deleteProviderApi,
  listProvidersApi,
  smokeTestProviderApi,
  updateProviderApi,
  type ProviderType,
  type ProviderView,
  type SmokeTestResponse,
} from '../api/provider'
import TdIcon from '../components/TdIcon.vue'
import { copyText } from '../utils/format'

const providers = ref<ProviderView[]>([])
const loading = ref(false)
const keyword = ref('')
const typeFilter = ref<'ALL' | ProviderType>('ALL')

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)

const form = reactive({
  name: '',
  providerType: 'MOCK' as ProviderType,
  baseUrl: '',
  apiKey: '',
  defaultModel: 'mock-good',
  pricingJson: '',
  mockConfigJson: '{"profile":"mock-good","seed":1}',
})

const smokeVisible = ref(false)
const smokeLoading = ref(false)
const smokeTarget = ref<ProviderView | null>(null)
const smokePrompt = ref('你好，请用一句话介绍 TraceDelta。')
const smokeModel = ref('')
const smokeResult = ref<SmokeTestResponse | null>(null)

const mockPresets = [
  {
    profile: 'mock-good',
    hint: '确定性正常返回，验证主链路',
    json: '{"profile":"mock-good","seed":1}',
  },
  {
    profile: 'mock-sloppy',
    hint: '约 40% 用例输出截断坏 JSON，验证回归检出',
    json: '{"profile":"mock-sloppy","seed":1}',
  },
  {
    profile: 'mock-slow',
    hint: '固定高延迟，验证性能退化与并发调度',
    json: '{"profile":"mock-slow","seed":1,"latencyMs":800}',
  },
  {
    profile: 'mock-flaky',
    hint: '按 (seed, case_key, attempt_no) 注入 429/超时，第 3 次必成功',
    json: '{"profile":"mock-flaky","seed":1,"failRate":0.5}',
  },
]

const promptPresets = [
  { label: '基础问答', text: '你好，请用一句话介绍 TraceDelta。' },
  { label: 'JSON 结构校验', text: '请仅返回合法 JSON，包含字段 status 和 latency_ok。' },
  { label: 'Java 并发问题', text: '简述 Java 21 虚拟线程在高并发 I/O 场景的优势。' },
]

const mockCount = computed(() => providers.value.filter((p) => p.providerType === 'MOCK').length)
const openAiCount = computed(
  () => providers.value.filter((p) => p.providerType === 'OPENAI_COMPATIBLE').length,
)

const filteredProviders = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return providers.value.filter((item) => {
    if (typeFilter.value !== 'ALL' && item.providerType !== typeFilter.value) {
      return false
    }
    if (!q) return true
    return (
      item.name.toLowerCase().includes(q) ||
      item.defaultModel.toLowerCase().includes(q) ||
      (item.baseUrl || '').toLowerCase().includes(q) ||
      (item.mockConfigJson || '').toLowerCase().includes(q)
    )
  })
})

function summarizeMockConfig(raw?: string | null): string {
  if (!raw) return '默认配置'
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    const parts: string[] = []
    if (parsed.profile) parts.push(String(parsed.profile))
    if (parsed.seed !== undefined) parts.push(`seed=${parsed.seed}`)
    if (parsed.latencyMs !== undefined) parts.push(`${parsed.latencyMs}ms`)
    if (parsed.failRate !== undefined) parts.push(`fail=${parsed.failRate}`)
    return parts.length > 0 ? parts.join(' · ') : raw
  } catch {
    return raw
  }
}

async function loadProviders() {
  loading.value = true
  try {
    providers.value = await listProvidersApi()
  } finally {
    loading.value = false
  }
}

function openCreate(presetProfile?: string) {
  editingId.value = null
  form.name = presetProfile ? `${presetProfile}-local` : ''
  form.providerType = 'MOCK'
  form.baseUrl = ''
  form.apiKey = ''
  form.defaultModel = presetProfile || 'mock-good'
  form.pricingJson = ''
  const preset = mockPresets.find((p) => p.profile === presetProfile)
  form.mockConfigJson = preset ? preset.json : '{"profile":"mock-good","seed":1}'
  dialogVisible.value = true
}

function openEdit(row: ProviderView) {
  editingId.value = row.id
  form.name = row.name
  form.providerType = row.providerType
  form.baseUrl = row.baseUrl || ''
  form.apiKey = ''
  form.defaultModel = row.defaultModel
  form.pricingJson = row.pricingJson || ''
  form.mockConfigJson = row.mockConfigJson || ''
  dialogVisible.value = true
}

function applyMockPreset(profile: string) {
  const found = mockPresets.find((p) => p.profile === profile)
  form.providerType = 'MOCK'
  form.defaultModel = profile
  form.mockConfigJson = found ? found.json : `{"profile":"${profile}","seed":1}`
  if (!form.name.trim()) {
    form.name = `${profile}-local`
  }
}

async function handleSave() {
  if (!form.name.trim() || !form.defaultModel.trim()) {
    ElMessage.warning('请填写渠道名称和默认模型')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      providerType: form.providerType,
      baseUrl: form.baseUrl.trim() || null,
      apiKey: form.apiKey.trim() || null,
      defaultModel: form.defaultModel.trim(),
      pricingJson: form.pricingJson.trim() || null,
      mockConfigJson: form.mockConfigJson.trim() || null,
    }
    if (editingId.value === null) {
      await createProviderApi(payload)
      ElMessage.success(`渠道「${payload.name}」已创建`)
    } else {
      await updateProviderApi(editingId.value, payload)
      ElMessage.success(`渠道「${payload.name}」已更新`)
    }
    dialogVisible.value = false
    await loadProviders()
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: ProviderView) {
  await ElMessageBox.confirm(`确认删除渠道「${row.name}」吗？该操作无法撤销。`, '删除渠道', {
    type: 'warning',
    confirmButtonText: '确认删除',
    cancelButtonText: '取消',
  })
  await deleteProviderApi(row.id)
  ElMessage.success('渠道已删除')
  await loadProviders()
}

function openSmokeTest(row: ProviderView) {
  smokeTarget.value = row
  smokeModel.value = row.defaultModel
  smokeResult.value = null
  smokeVisible.value = true
}

async function runSmokeTest() {
  if (!smokeTarget.value || !smokePrompt.value.trim()) {
    ElMessage.warning('请输入测试 Prompt')
    return
  }
  smokeLoading.value = true
  try {
    smokeResult.value = await smokeTestProviderApi(
      smokeTarget.value.id,
      smokePrompt.value.trim(),
      smokeModel.value.trim() || undefined,
    )
  } finally {
    smokeLoading.value = false
  }
}

const smokeOutputText = computed(() => {
  if (!smokeResult.value) return ''
  return smokeResult.value.errorType === 'NONE'
    ? smokeResult.value.content || '(空响应)'
    : smokeResult.value.errorMessage || '调用失败'
})

onMounted(loadProviders)
</script>

<template>
  <div class="td-page">
    <div class="td-page-header">
      <div>
        <h1 class="td-page-title">模型渠道与确定性 Mock</h1>
        <p class="td-page-desc">
          管理上游 OpenAI 兼容接口与零费用确定性 Mock 渠道；API Key 经 AES-GCM 加密存储，前端列表与详情仅返回脱敏掩码。
        </p>
      </div>
      <el-button type="primary" @click="openCreate()">
        <TdIcon name="plus" :size="15" style="margin-right: 6px" />
        新增渠道
      </el-button>
    </div>

    <section class="td-surface">
      <div class="td-toolbar">
        <div class="td-toolbar-group filter-group">
          <div class="segment-group" role="group" aria-label="渠道类型筛选">
            <button
              type="button"
              class="segment-btn"
              :class="{ 'is-active': typeFilter === 'ALL' }"
              @click="typeFilter = 'ALL'"
            >
              全部 <span class="seg-count td-mono">{{ providers.length }}</span>
            </button>
            <button
              type="button"
              class="segment-btn"
              :class="{ 'is-active': typeFilter === 'MOCK' }"
              @click="typeFilter = 'MOCK'"
            >
              MOCK <span class="seg-count td-mono">{{ mockCount }}</span>
            </button>
            <button
              type="button"
              class="segment-btn"
              :class="{ 'is-active': typeFilter === 'OPENAI_COMPATIBLE' }"
              @click="typeFilter = 'OPENAI_COMPATIBLE'"
            >
              OpenAI 兼容 <span class="seg-count td-mono">{{ openAiCount }}</span>
            </button>
          </div>

          <el-input
            v-model="keyword"
            placeholder="搜索渠道名、模型或端点..."
            clearable
            class="search-input"
          >
            <template #prefix>
              <TdIcon name="search" :size="14" />
            </template>
          </el-input>

          <el-button :loading="loading" @click="loadProviders">
            <TdIcon name="refresh" :size="14" style="margin-right: 5px" />
            刷新
          </el-button>
        </div>

        <div class="td-toolbar-group">
          <span class="security-note">
            <TdIcon name="lock" :size="13" />
            AES-GCM 加密托管
          </span>
        </div>
      </div>

      <!-- Loading skeleton -->
      <div v-if="loading && providers.length === 0" class="skeleton-wrap">
        <el-skeleton :rows="5" animated />
      </div>

      <!-- First-run empty state -->
      <div v-else-if="providers.length === 0" class="empty-state">
        <div class="empty-icon">
          <TdIcon name="cpu" :size="22" />
        </div>
        <h3>尚未配置模型渠道</h3>
        <p>
          无需真实 API Key 即可开始：推荐先创建一个 <code class="td-mono">mock-good</code> 确定性模拟器，立即体验单次冒烟调用与后续并发评测。
        </p>
        <div class="empty-actions">
          <el-button type="primary" @click="openCreate('mock-good')">
            <TdIcon name="plus" :size="15" style="margin-right: 6px" />
            快速创建 mock-good
          </el-button>
          <el-button @click="openCreate()">自定义新增渠道</el-button>
        </div>
      </div>

      <!-- Filtered empty state -->
      <div v-else-if="filteredProviders.length === 0" class="empty-state">
        <h3>没有符合条件的模型渠道</h3>
        <p>请切换类型筛选或清空搜索关键字。</p>
        <el-button
          @click="
            () => {
              keyword = ''
              typeFilter = 'ALL'
            }
          "
        >
          重置筛选
        </el-button>
      </div>

      <!-- Providers table -->
      <el-table v-else :data="filteredProviders">
        <el-table-column label="渠道名称" min-width="180">
          <template #default="{ row }">
            <div class="provider-name-cell">
              <span class="provider-name">{{ row.name }}</span>
              <span class="id-chip td-mono">#{{ row.id }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="166">
          <template #default="{ row }">
            <span
              class="td-pill td-mono"
              :class="row.providerType === 'MOCK' ? 'td-pill--success' : 'td-pill--accent'"
            >
              <span class="td-pill-dot" />
              {{ row.providerType === 'MOCK' ? 'MOCK' : 'OPENAI_COMPAT' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="默认模型" min-width="150">
          <template #default="{ row }">
            <span class="model-chip td-mono">{{ row.defaultModel }}</span>
          </template>
        </el-table-column>

        <el-table-column label="端点 / Mock 档位" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.providerType === 'MOCK'" class="config-summary td-mono">
              {{ summarizeMockConfig(row.mockConfigJson) }}
            </span>
            <span v-else class="config-summary td-mono">{{ row.baseUrl || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="API Key 掩码" width="150">
          <template #default="{ row }">
            <span v-if="row.apiKeyMask" class="key-mask td-mono">
              <TdIcon name="lock" :size="12" />
              {{ row.apiKeyMask }}
            </span>
            <span v-else class="key-none">无需密钥</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="218" align="right" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" @click="openSmokeTest(row)">
                <TdIcon name="play" :size="13" style="margin-right: 4px" />
                冒烟测试
              </el-button>
              <el-button link @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- Create / Edit dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增模型渠道' : '编辑模型渠道'"
      width="580px"
    >
      <el-form label-position="top">
        <el-form-item label="渠道名称" required>
          <el-input v-model="form.name" placeholder="例如：mock-good-local 或 deepseek-prod" />
        </el-form-item>

        <el-form-item label="渠道类型" required>
          <el-radio-group v-model="form.providerType">
            <el-radio-button value="MOCK">MOCK（确定性零费用模拟器）</el-radio-button>
            <el-radio-button value="OPENAI_COMPATIBLE">OPENAI_COMPATIBLE</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="form.providerType === 'MOCK'" label="预设 Mock 档位">
          <div class="preset-grid">
            <button
              v-for="item in mockPresets"
              :key="item.profile"
              type="button"
              class="preset-card"
              :class="{ 'is-selected': form.defaultModel === item.profile }"
              @click="applyMockPreset(item.profile)"
            >
              <span class="preset-name td-mono">{{ item.profile }}</span>
              <span class="preset-hint">{{ item.hint }}</span>
            </button>
          </div>
        </el-form-item>

        <el-form-item label="默认模型" required>
          <el-input
            v-model="form.defaultModel"
            class="mono-input"
            placeholder="例如：mock-good / deepseek-chat / gpt-4o-mini"
          />
        </el-form-item>

        <el-form-item v-if="form.providerType === 'OPENAI_COMPATIBLE'" label="Base URL" required>
          <el-input
            v-model="form.baseUrl"
            class="mono-input"
            placeholder="https://api.deepseek.com/v1"
          />
        </el-form-item>

        <el-form-item v-if="form.providerType === 'OPENAI_COMPATIBLE'" label="API Key">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="editingId === null ? '请输入 API Key（后端 AES-GCM 加密存储）' : '留空表示保留原加密密钥不变'"
          />
        </el-form-item>

        <el-form-item v-if="form.providerType === 'MOCK'" label="Mock 配置 JSON">
          <el-input
            v-model="form.mockConfigJson"
            type="textarea"
            :rows="3"
            class="mono-input"
          />
        </el-form-item>

        <el-form-item label="定价配置 JSON（可选，用于成本估算）">
          <el-input
            v-model="form.pricingJson"
            class="mono-input"
            placeholder='{"inputPer1k":0.001,"outputPer1k":0.002}'
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存配置</el-button>
      </template>
    </el-dialog>

    <!-- Smoke test drawer -->
    <el-drawer
      v-model="smokeVisible"
      :title="`单次冒烟调试 · ${smokeTarget?.name || ''}`"
      size="540px"
    >
      <div v-if="smokeTarget" class="smoke-container">
        <div class="smoke-target-bar">
          <span
            class="td-pill td-mono"
            :class="smokeTarget.providerType === 'MOCK' ? 'td-pill--success' : 'td-pill--accent'"
          >
            <span class="td-pill-dot" />
            {{ smokeTarget.providerType }}
          </span>
          <span class="smoke-target-model td-mono">{{ smokeTarget.defaultModel }}</span>
        </div>

        <el-form label-position="top">
          <el-form-item label="模型名称（可选覆盖）">
            <el-input v-model="smokeModel" class="mono-input" />
          </el-form-item>

          <el-form-item label="测试 Prompt">
            <div class="prompt-presets">
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

          <el-button type="primary" :loading="smokeLoading" @click="runSmokeTest">
            <TdIcon v-if="!smokeLoading" name="play" :size="14" style="margin-right: 6px" />
            {{ smokeLoading ? '调用中...' : '发起单次调用' }}
          </el-button>
        </el-form>

        <div v-if="smokeResult" class="smoke-output">
          <div class="output-status-row">
            <span
              class="td-pill td-mono"
              :class="smokeResult.errorType === 'NONE' ? 'td-pill--success' : 'td-pill--danger'"
            >
              <span class="td-pill-dot" />
              {{ smokeResult.errorType === 'NONE' ? 'SUCCEEDED (NONE)' : smokeResult.errorType }}
            </span>
            <span class="latency-badge td-mono">{{ smokeResult.latencyMs }} ms</span>
          </div>

          <div class="metrics-strip">
            <div class="metric-item">
              <span class="metric-label">耗时</span>
              <span class="metric-value td-mono">{{ smokeResult.latencyMs }} ms</span>
            </div>
            <div class="metric-item">
              <span class="metric-label">Input Tokens</span>
              <span class="metric-value td-mono">{{ smokeResult.inputTokens }}</span>
            </div>
            <div class="metric-item">
              <span class="metric-label">Output Tokens</span>
              <span class="metric-value td-mono">{{ smokeResult.outputTokens }}</span>
            </div>
            <div class="metric-item">
              <span class="metric-label">估算费用</span>
              <span class="metric-value td-mono">{{ smokeResult.estimatedCost }}</span>
            </div>
          </div>

          <div class="response-code-card">
            <div class="response-code-head">
              <span>{{ smokeResult.errorType === 'NONE' ? '模型输出内容' : '错误详情' }}</span>
              <button
                type="button"
                class="copy-mini"
                @click="copyText(smokeOutputText, '已复制响应内容')"
              >
                <TdIcon name="copy" :size="12" />
                复制
              </button>
            </div>
            <pre class="response-code-body td-mono">{{ smokeOutputText }}</pre>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.filter-group {
  flex: 1;
  min-width: 240px;
}

.segment-group {
  display: inline-flex;
  padding: 2px;
  gap: 2px;
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border-subtle);
  border-radius: var(--td-radius-sm);
}

.segment-btn {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--td-text-secondary);
  padding: 4px 10px;
  border-radius: var(--td-radius-xs);
  font-family: inherit;
  font-size: var(--td-text-xs);
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  transition: background-color var(--td-duration-fast) var(--td-ease-out),
    color var(--td-duration-fast) var(--td-ease-out);
}

.segment-btn:hover {
  color: var(--td-text-primary);
}

.segment-btn.is-active {
  background-color: var(--td-bg-surface);
  color: var(--td-text-primary);
  box-shadow: var(--td-shadow-sm);
}

.seg-count {
  font-size: 11px;
  color: var(--td-text-muted);
}

.search-input {
  max-width: 260px;
}

.security-note {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--td-text-xs);
  color: var(--td-text-muted);
}

.skeleton-wrap {
  padding: 20px;
}

.empty-state {
  padding: 56px 24px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.empty-icon {
  width: 44px;
  height: 44px;
  border-radius: var(--td-radius-md);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border-subtle);
  color: var(--td-text-muted);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 4px;
}

.empty-state h3 {
  margin: 0;
  font-size: var(--td-text-md);
  font-weight: 600;
  color: var(--td-text-primary);
}

.empty-state p {
  margin: 0 0 8px;
  font-size: var(--td-text-sm);
  color: var(--td-text-secondary);
  max-width: 48ch;
}

.empty-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.provider-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.provider-name {
  font-weight: 600;
  color: var(--td-text-primary);
}

.id-chip {
  font-size: 11px;
  color: var(--td-text-muted);
}

.model-chip {
  display: inline-block;
  padding: 2px 7px;
  border-radius: var(--td-radius-xs);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border-subtle);
  font-size: var(--td-text-xs);
  color: var(--td-text-primary);
}

.config-summary {
  font-size: var(--td-text-xs);
  color: var(--td-text-secondary);
}

.key-mask {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--td-text-xs);
  color: var(--td-text-secondary);
}

.key-none {
  font-size: var(--td-text-xs);
  color: var(--td-text-muted);
}

.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.preset-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  width: 100%;
}

.preset-card {
  appearance: none;
  text-align: left;
  padding: 9px 11px;
  border-radius: var(--td-radius-sm);
  border: 1px solid var(--td-border-default);
  background-color: var(--td-bg-surface);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-family: inherit;
  transition: border-color var(--td-duration-fast) var(--td-ease-out),
    background-color var(--td-duration-fast) var(--td-ease-out);
}

.preset-card:hover {
  border-color: var(--td-border-strong);
  background-color: var(--td-bg-hover);
}

.preset-card.is-selected {
  border-color: var(--td-accent-border);
  background-color: var(--td-accent-soft);
}

.preset-name {
  font-size: var(--td-text-xs);
  font-weight: 600;
  color: var(--td-text-primary);
}

.preset-card.is-selected .preset-name {
  color: var(--td-accent);
}

.preset-hint {
  font-size: 11px;
  line-height: 1.4;
  color: var(--td-text-muted);
}

.mono-input :deep(.el-input__inner),
.mono-input :deep(.el-textarea__inner) {
  font-family: var(--td-font-mono);
  font-size: 12.5px;
}

/* Smoke drawer */
.smoke-container {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.smoke-target-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--td-radius-sm);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border-subtle);
}

.smoke-target-model {
  font-size: var(--td-text-xs);
  color: var(--td-text-primary);
  font-weight: 500;
}

.prompt-presets {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.prompt-pill {
  appearance: none;
  border: 1px solid var(--td-border-default);
  background-color: var(--td-bg-subtle);
  color: var(--td-text-secondary);
  padding: 3px 9px;
  border-radius: 999px;
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
  transition: border-color var(--td-duration-fast) var(--td-ease-out),
    color var(--td-duration-fast) var(--td-ease-out);
}

.prompt-pill:hover {
  border-color: var(--td-accent-border);
  color: var(--td-accent);
}

.smoke-output {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid var(--td-border-subtle);
}

.output-status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.latency-badge {
  font-size: var(--td-text-xs);
  color: var(--td-text-secondary);
}

.metrics-strip {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.metric-item {
  padding: 10px 12px;
  border-radius: var(--td-radius-sm);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border-subtle);
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.metric-label {
  font-size: 11px;
  color: var(--td-text-muted);
}

.metric-value {
  font-size: var(--td-text-sm);
  font-weight: 600;
  color: var(--td-text-primary);
}

.response-code-card {
  border-radius: var(--td-radius-sm);
  border: 1px solid var(--td-border-subtle);
  background-color: var(--td-bg-code);
  overflow: hidden;
}

.response-code-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background-color: var(--td-bg-code-header);
  border-bottom: 1px solid var(--td-border-subtle);
  font-size: var(--td-text-xs);
  font-weight: 500;
  color: var(--td-text-secondary);
}

.copy-mini {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--td-text-muted);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: inherit;
  font-size: var(--td-text-xs);
  cursor: pointer;
}

.copy-mini:hover {
  color: var(--td-accent);
}

.response-code-body {
  margin: 0;
  padding: 12px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--td-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 280px;
  overflow-y: auto;
}

@media (max-width: 640px) {
  .preset-grid {
    grid-template-columns: 1fr;
  }
}
</style>
