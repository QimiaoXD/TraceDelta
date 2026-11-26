<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
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

const providers = ref<ProviderView[]>([])
const loading = ref(false)

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

async function loadProviders() {
  loading.value = true
  try {
    providers.value = await listProvidersApi()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.name = ''
  form.providerType = 'MOCK'
  form.baseUrl = ''
  form.apiKey = ''
  form.defaultModel = 'mock-good'
  form.pricingJson = ''
  form.mockConfigJson = '{"profile":"mock-good","seed":1}'
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
  form.providerType = 'MOCK'
  form.defaultModel = profile
  if (profile === 'mock-slow') {
    form.mockConfigJson = '{"profile":"mock-slow","seed":1,"latencyMs":800}'
  } else if (profile === 'mock-flaky') {
    form.mockConfigJson = '{"profile":"mock-flaky","seed":1,"failRate":0.5}'
  } else {
    form.mockConfigJson = `{"profile":"${profile}","seed":1}`
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
      ElMessage.success('创建成功')
    } else {
      await updateProviderApi(editingId.value, payload)
      ElMessage.success('更新成功')
    }
    dialogVisible.value = false
    await loadProviders()
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: ProviderView) {
  await ElMessageBox.confirm(`确认删除渠道「${row.name}」吗？`, '提示', { type: 'warning' })
  await deleteProviderApi(row.id)
  ElMessage.success('已删除')
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

onMounted(loadProviders)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div>
          <h3 style="margin: 0">模型渠道与确定性 Mock</h3>
          <span class="sub">API Key 经 AES-GCM 加密存储，列表仅展示脱敏掩码；支持零费用 Mock 调试</span>
        </div>
        <el-button type="primary" @click="openCreate">新增渠道</el-button>
      </div>
    </template>

    <el-table :data="providers" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="渠道名称" min-width="140" />
      <el-table-column label="类型" width="160">
        <template #default="{ row }">
          <el-tag :type="row.providerType === 'MOCK' ? 'success' : 'primary'">
            {{ row.providerType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="defaultModel" label="默认模型" min-width="140" />
      <el-table-column label="API Key" width="140">
        <template #default="{ row }">
          <span>{{ row.apiKeyMask || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="baseUrl" label="Base URL" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link type="success" @click="openSmokeTest(row)">冒烟测试</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="dialogVisible" :title="editingId === null ? '新增模型渠道' : '编辑模型渠道'" width="560px">
    <el-form label-position="top">
      <el-form-item label="渠道名称" required>
        <el-input v-model="form.name" placeholder="例如：mock-good-local 或 deepseek-prod" />
      </el-form-item>
      <el-form-item label="渠道类型" required>
        <el-radio-group v-model="form.providerType">
          <el-radio-button value="MOCK">MOCK（零费用模拟器）</el-radio-button>
          <el-radio-button value="OPENAI_COMPATIBLE">OPENAI_COMPATIBLE</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.providerType === 'MOCK'" label="快速填充 Mock 档位">
        <div style="display: flex; gap: 8px; flex-wrap: wrap">
          <el-button size="small" @click="applyMockPreset('mock-good')">mock-good</el-button>
          <el-button size="small" @click="applyMockPreset('mock-sloppy')">mock-sloppy</el-button>
          <el-button size="small" @click="applyMockPreset('mock-slow')">mock-slow</el-button>
          <el-button size="small" @click="applyMockPreset('mock-flaky')">mock-flaky</el-button>
        </div>
      </el-form-item>
      <el-form-item label="默认模型" required>
        <el-input v-model="form.defaultModel" placeholder="例如：mock-good / deepseek-chat" />
      </el-form-item>
      <el-form-item v-if="form.providerType === 'OPENAI_COMPATIBLE'" label="Base URL" required>
        <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com/v1" />
      </el-form-item>
      <el-form-item v-if="form.providerType === 'OPENAI_COMPATIBLE'" label="API Key">
        <el-input
          v-model="form.apiKey"
          type="password"
          show-password
          :placeholder="editingId === null ? '请输入 API Key' : '留空表示保留原加密密钥不变'"
        />
      </el-form-item>
      <el-form-item v-if="form.providerType === 'MOCK'" label="Mock 配置 JSON">
        <el-input v-model="form.mockConfigJson" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="定价配置 JSON（可选）">
        <el-input
          v-model="form.pricingJson"
          placeholder='{"inputPer1k":0.001,"outputPer1k":0.002}'
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </template>
  </el-dialog>

  <el-drawer v-model="smokeVisible" :title="`单次冒烟测试 · ${smokeTarget?.name || ''}`" size="480px">
    <el-form label-position="top">
      <el-form-item label="模型（可选覆盖）">
        <el-input v-model="smokeModel" />
      </el-form-item>
      <el-form-item label="测试 Prompt">
        <el-input v-model="smokePrompt" type="textarea" :rows="4" />
      </el-form-item>
      <el-button type="primary" :loading="smokeLoading" @click="runSmokeTest">发起调用</el-button>
    </el-form>

    <div v-if="smokeResult" class="smoke-result">
      <el-descriptions :column="2" border size="small" style="margin-top: 20px">
        <el-descriptions-item label="错误类型">
          <el-tag :type="smokeResult.errorType === 'NONE' ? 'success' : 'danger'">
            {{ smokeResult.errorType }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="耗时">{{ smokeResult.latencyMs }} ms</el-descriptions-item>
        <el-descriptions-item label="Input Tokens">{{ smokeResult.inputTokens }}</el-descriptions-item>
        <el-descriptions-item label="Output Tokens">{{ smokeResult.outputTokens }}</el-descriptions-item>
        <el-descriptions-item label="估算费用" :span="2">{{ smokeResult.estimatedCost }}</el-descriptions-item>
      </el-descriptions>
      <div class="result-box">
        <div class="result-title">响应内容 / 错误信息</div>
        <pre>{{ smokeResult.errorType === 'NONE' ? smokeResult.content : smokeResult.errorMessage }}</pre>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.sub {
  font-size: 12px;
  color: #64748b;
}
.result-box {
  margin-top: 16px;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 8px;
  padding: 12px 14px;
}
.result-title {
  font-size: 12px;
  color: #94a3b8;
  margin-bottom: 6px;
}
.result-box pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
}
</style>
