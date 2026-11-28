<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createHttpTargetApi,
  deleteHttpTargetApi,
  smokeTestHttpTargetApi,
  updateHttpTargetApi,
  type HttpMethodType,
  type HttpTargetSmokeTestResult,
  type HttpTargetView,
} from '../api/httpTarget'
import { useConsoleDataStore } from '../stores/consoleData'
import TdIcon from '../components/TdIcon.vue'
import { formatDateTime } from '../utils/format'
import { formatLatency } from '../utils/runFormat'

interface HeaderPair {
  key: string
  value: string
}

const store = useConsoleDataStore()

const targets = computed(() => store.httpTargets)
const keyword = ref('')
const loadError = ref('')
const liveAnnouncement = ref('')

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const submitting = ref(false)
const formError = ref('')
const formAlertRef = ref<HTMLElement | null>(null)

const form = reactive({
  name: '',
  description: '',
  url: '',
  httpMethod: 'POST' as HttpMethodType,
  headers: [] as HeaderPair[],
  bodyTemplate: '{"question": "{{question}}"}',
  responseJsonPath: '$.data.answer',
  defaultTimeoutMs: 30000,
})

const smokeVisible = ref(false)
const smokeTarget = ref<HttpTargetView | null>(null)
const smokeInputJson = ref('{"question": "你好，请介绍一下 TraceDelta"}')
const smokeRunning = ref(false)
const smokeResult = ref<HttpTargetSmokeTestResult | null>(null)
const smokeError = ref('')

const methods: HttpMethodType[] = ['POST', 'GET', 'PUT', 'PATCH']

const filteredTargets = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return targets.value
  return targets.value.filter(
    (t) =>
      t.name.toLowerCase().includes(q) ||
      t.url.toLowerCase().includes(q) ||
      (t.description || '').toLowerCase().includes(q),
  )
})

async function refresh() {
  loadError.value = ''
  try {
    await store.fetchHttpTargets()
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '加载外部接口目标失败'
  }
}

function openCreateDialog() {
  editingId.value = null
  formError.value = ''
  form.name = ''
  form.description = ''
  form.url = ''
  form.httpMethod = 'POST'
  form.headers = [{ key: 'Content-Type', value: 'application/json' }]
  form.bodyTemplate = '{"question": "{{question}}"}'
  form.responseJsonPath = '$.data.answer'
  form.defaultTimeoutMs = 30000
  dialogVisible.value = true
}

function openEditDialog(target: HttpTargetView) {
  editingId.value = target.id
  formError.value = ''
  form.name = target.name
  form.description = target.description ?? ''
  form.url = target.url
  form.httpMethod = target.httpMethod
  form.headers = Object.entries(target.maskedHeaders || {}).map(([key, value]) => ({
    key,
    value,
  }))
  form.bodyTemplate = target.bodyTemplate ?? ''
  form.responseJsonPath = target.responseJsonPath ?? ''
  form.defaultTimeoutMs = target.defaultTimeoutMs
  dialogVisible.value = true
}

function addHeaderRow() {
  form.headers.push({ key: '', value: '' })
}

function removeHeaderRow(index: number) {
  form.headers.splice(index, 1)
}

async function handleSubmit() {
  if (submitting.value) return
  formError.value = ''
  if (!form.name.trim()) {
    formError.value = '请填写外部接口名称'
    await nextTick()
    formAlertRef.value?.focus()
    return
  }
  if (!form.url.trim()) {
    formError.value = '请填写目标 URL（仅支持 http/https，默认拦截私网与回环地址）'
    await nextTick()
    formAlertRef.value?.focus()
    return
  }

  const headerMap: Record<string, string> = {}
  for (const pair of form.headers) {
    const k = pair.key.trim()
    const v = pair.value.trim()
    if (k && v) {
      headerMap[k] = v
    }
  }

  const payload = {
    name: form.name.trim(),
    description: form.description.trim() || null,
    url: form.url.trim(),
    httpMethod: form.httpMethod,
    headers: headerMap,
    bodyTemplate: form.httpMethod === 'GET' ? null : form.bodyTemplate.trim() || null,
    responseJsonPath: form.responseJsonPath.trim() || null,
    defaultTimeoutMs: form.defaultTimeoutMs,
  }

  submitting.value = true
  try {
    if (editingId.value === null) {
      await createHttpTargetApi(payload)
      ElMessage.success(`已创建外部接口目标「${payload.name}」`)
      liveAnnouncement.value = `已创建外部接口目标 ${payload.name}`
    } else {
      await updateHttpTargetApi(editingId.value, payload)
      ElMessage.success(`已更新外部接口目标「${payload.name}」`)
      liveAnnouncement.value = `已更新外部接口目标 ${payload.name}`
    }
    dialogVisible.value = false
    await store.fetchHttpTargets()
  } catch (err) {
    formError.value = err instanceof Error ? err.message : '保存外部接口目标失败'
    await nextTick()
    formAlertRef.value?.focus()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(target: HttpTargetView) {
  try {
    await ElMessageBox.confirm(
      `确认删除外部接口目标「${target.name}」？已冻结的历史评测快照不会受影响，但无法再以该目标发起新评测。`,
      '删除外部接口目标',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }
  await deleteHttpTargetApi(target.id)
  ElMessage.success(`已删除「${target.name}」`)
  liveAnnouncement.value = `已删除外部接口目标 ${target.name}`
  await store.fetchHttpTargets()
}

function openSmokeDialog(target: HttpTargetView) {
  smokeTarget.value = target
  smokeInputJson.value = '{"question": "你好，请介绍一下 TraceDelta"}'
  smokeResult.value = null
  smokeError.value = ''
  smokeVisible.value = true
}

async function runSmokeTest() {
  if (!smokeTarget.value || smokeRunning.value) return
  smokeError.value = ''
  smokeResult.value = null
  try {
    JSON.parse(smokeInputJson.value)
  } catch {
    smokeError.value = '测试输入必须是合法的 JSON 对象，例如 {"question": "你好"}'
    return
  }

  smokeRunning.value = true
  try {
    smokeResult.value = await smokeTestHttpTargetApi(smokeTarget.value.id, {
      inputJson: smokeInputJson.value,
    })
    liveAnnouncement.value = smokeResult.value.success
      ? `冒烟测试成功，状态码 ${smokeResult.value.httpStatus}`
      : `冒烟测试失败：${smokeResult.value.errorType}`
  } catch (err) {
    smokeError.value = err instanceof Error ? err.message : '执行冒烟测试失败'
  } finally {
    smokeRunning.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <div class="view-stack">
    <div class="sr-only" aria-live="polite">{{ liveAnnouncement }}</div>

    <header class="view-header">
      <div class="view-title-group">
        <h1 class="view-title">外部接口</h1>
        <span class="micro-badge">
          <TdIcon name="shield-check" :size="12" />
          <span>SSRF 防护与请求头 AES-GCM 加密</span>
        </span>
      </div>
      <div class="view-actions">
        <button type="button" class="btn btn--secondary" @click="refresh">
          <TdIcon name="refresh" :size="14" />
          <span>刷新</span>
        </button>
        <button type="button" class="btn btn--primary" @click="openCreateDialog">
          <TdIcon name="plus" :size="14" />
          <span>新建外部接口</span>
        </button>
      </div>
    </header>

    <div class="view-toolbar">
      <div class="search-box">
        <TdIcon name="search" :size="14" class="search-box-icon" />
        <input
          v-model="keyword"
          type="search"
          class="search-box-input"
          placeholder="按名称、URL 或说明搜索…"
          aria-label="搜索外部接口目标"
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

      <span v-if="store.httpTargetsLoaded" class="view-count mono">
        {{ filteredTargets.length }} 个目标
      </span>
    </div>

    <div v-if="loadError" class="error-banner" role="alert">
      <span class="error-banner-body">
        <TdIcon name="alert-circle" :size="15" />
        <span>{{ loadError }}</span>
      </span>
      <button type="button" class="btn btn--secondary btn--sm" @click="refresh">重试</button>
    </div>

    <section v-else-if="store.httpTargetsLoaded && targets.length === 0" class="empty-panel">
      <div class="empty-panel-icon" aria-hidden="true">
        <TdIcon name="globe" :size="18" />
      </div>
      <h2>尚无外部 HTTP 接口目标</h2>
      <p>
        接入已有的 AI / RAG HTTP 接口（支持 JSON 请求模板与 JSONPath 提取），直接复用 TraceDelta 的并发调度、限流与链路追踪。
      </p>
      <button type="button" class="btn btn--primary" @click="openCreateDialog">
        <TdIcon name="plus" :size="14" />
        <span>新建外部接口</span>
      </button>
    </section>

    <div v-else-if="store.httpTargetsLoaded" class="data-table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>目标名称</th>
            <th>方法与 Endpoint</th>
            <th>JSONPath / 请求头</th>
            <th class="col-num">默认超时</th>
            <th>更新时间</th>
            <th class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in filteredTargets" :key="item.id">
            <td>
              <div class="target-name">{{ item.name }}</div>
              <div v-if="item.description" class="cell-sub">{{ item.description }}</div>
            </td>
            <td class="mono">
              <span class="micro-badge">{{ item.httpMethod }}</span>
              <span class="url-text" :title="item.url">{{ item.url }}</span>
            </td>
            <td class="mono">
              <div>{{ item.responseJsonPath || '原始响应体' }}</div>
              <div class="cell-sub">
                {{ Object.keys(item.maskedHeaders || {}).length }} 个请求头
              </div>
            </td>
            <td class="col-num mono">{{ formatLatency(item.defaultTimeoutMs) }}</td>
            <td class="mono cell-muted">{{ formatDateTime(item.updatedAt) }}</td>
            <td class="col-actions">
              <div class="row-actions">
                <button
                  type="button"
                  class="btn btn--secondary btn--sm"
                  @click="openSmokeDialog(item)"
                >
                  <TdIcon name="play" :size="12" />
                  <span>测试</span>
                </button>
                <button
                  type="button"
                  class="btn btn--ghost btn--sm"
                  @click="openEditDialog(item)"
                >
                  编辑
                </button>
                <button
                  type="button"
                  class="btn btn--ghost btn--sm btn--danger-text"
                  @click="handleDelete(item)"
                >
                  删除
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Create / Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建外部接口目标' : '编辑外部接口目标'"
      width="640px"
    >
      <div
        v-if="formError"
        ref="formAlertRef"
        class="error-banner"
        role="alert"
        tabindex="-1"
        style="margin-bottom: 12px"
      >
        <span class="error-banner-body">
          <TdIcon name="alert-circle" :size="15" />
          <span>{{ formError }}</span>
        </span>
      </div>

      <el-form label-position="top" @submit.prevent="handleSubmit">
        <div class="form-grid-2">
          <el-form-item label="目标名称" required>
            <el-input v-model="form.name" placeholder="例如：企业客服 RAG API" />
          </el-form-item>
          <el-form-item label="HTTP 方法" required>
            <el-select v-model="form.httpMethod" style="width: 100%">
              <el-option v-for="m in methods" :key="m" :label="m" :value="m" />
            </el-select>
          </el-form-item>
        </div>

        <el-form-item label="目标 URL（默认拦截私网/回环/元数据地址与 3xx 重定向）" required>
          <el-input v-model="form.url" placeholder="https://api.example.com/v1/qa" />
        </el-form-item>

        <el-form-item label="说明（可选）">
          <el-input v-model="form.description" placeholder="简述该接口的用途或环境" />
        </el-form-item>

        <div class="form-grid-2">
          <el-form-item label="响应 JSONPath（留空则直接取响应体原文）">
            <el-input v-model="form.responseJsonPath" placeholder="例如 $.data.answer" />
          </el-form-item>
          <el-form-item label="默认超时 (ms)">
            <el-input-number
              v-model="form.defaultTimeoutMs"
              :min="1000"
              :max="300000"
              :step="1000"
              style="width: 100%"
            />
          </el-form-item>
        </div>

        <el-form-item v-if="form.httpMethod !== 'GET'" label="JSON 请求体模板（支持 {{变量名}}，自动 JSON 转义）">
          <el-input
            v-model="form.bodyTemplate"
            type="textarea"
            :rows="3"
            placeholder='{"question": "{{question}}"}'
          />
        </el-form-item>

        <el-form-item label="自定义请求头（敏感头如 Authorization / X-Api-Key 将以 AES-GCM 加密存储）">
          <div class="header-editor">
            <div v-for="(pair, idx) in form.headers" :key="idx" class="header-row">
              <el-input v-model="pair.key" placeholder="Header 名称，如 Authorization" />
              <el-input v-model="pair.value" placeholder="Header 值（编辑保留 [REDACTED] 则不修改原密钥）" />
              <button
                type="button"
                class="btn btn--icon"
                aria-label="删除请求头"
                @click="removeHeaderRow(idx)"
              >
                <TdIcon name="x" :size="14" />
              </button>
            </div>
            <button type="button" class="btn btn--secondary btn--sm" @click="addHeaderRow">
              <TdIcon name="plus" :size="12" />
              <span>添加请求头</span>
            </button>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <button type="button" class="btn btn--secondary" :disabled="submitting" @click="dialogVisible = false">
            取消
          </button>
          <button type="button" class="btn btn--primary" :disabled="submitting" @click="handleSubmit">
            <span>{{ submitting ? '保存中…' : '保存目标' }}</span>
          </button>
        </div>
      </template>
    </el-dialog>

    <!-- Single Smoke Test Dialog -->
    <el-dialog
      v-model="smokeVisible"
      :title="smokeTarget ? `冒烟测试 · ${smokeTarget.name}` : '冒烟测试'"
      width="580px"
    >
      <div class="smoke-stack">
        <div v-if="smokeTarget" class="mono smoke-url">
          <span class="micro-badge">{{ smokeTarget.httpMethod }}</span>
          <span>{{ smokeTarget.url }}</span>
        </div>

        <el-form label-position="top">
          <el-form-item label="样例用例输入（JSON 对象，用于替换 {{变量名}}）">
            <el-input v-model="smokeInputJson" type="textarea" :rows="3" />
          </el-form-item>
        </el-form>

        <div v-if="smokeError" class="error-banner" role="alert">
          <span class="error-banner-body">
            <TdIcon name="alert-circle" :size="14" />
            <span>{{ smokeError }}</span>
          </span>
        </div>

        <div v-if="smokeResult" class="smoke-result">
          <div class="smoke-res-head mono">
            <span
              class="status-tag"
              :class="smokeResult.success ? 'status-tag--success' : 'status-tag--danger'"
            >
              <TdIcon :name="smokeResult.success ? 'check-circle' : 'alert-circle'" :size="12" />
              <span>{{ smokeResult.success ? '调用成功' : smokeResult.errorType || '调用失败' }}</span>
            </span>
            <span v-if="smokeResult.httpStatus !== null">HTTP {{ smokeResult.httpStatus }}</span>
            <span>{{ formatLatency(smokeResult.latencyMs) }}</span>
          </div>

          <div v-if="smokeResult.errorMessage" class="smoke-err mono">
            {{ smokeResult.errorMessage }}
          </div>

          <div v-if="smokeResult.extractedOutput" class="smoke-block">
            <h5>JSONPath 提取结果</h5>
            <pre class="code-block mono">{{ smokeResult.extractedOutput }}</pre>
          </div>

          <div v-if="smokeResult.rawResponsePreview" class="smoke-block">
            <h5>原始响应预览（已脱敏）</h5>
            <pre class="code-block mono">{{ smokeResult.rawResponsePreview }}</pre>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <button type="button" class="btn btn--secondary" @click="smokeVisible = false">关闭</button>
          <button type="button" class="btn btn--primary" :disabled="smokeRunning" @click="runSmokeTest">
            <TdIcon name="play" :size="14" />
            <span>{{ smokeRunning ? '请求中…' : '发送单次测试' }}</span>
          </button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.target-name {
  font-weight: 500;
  color: var(--td-text-primary);
}

.url-text {
  margin-left: 8px;
  font-size: 12.5px;
  color: var(--td-text-secondary);
  word-break: break-all;
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

.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.btn--danger-text {
  color: var(--td-danger-text);
}

.form-grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.header-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.header-row {
  display: grid;
  grid-template-columns: minmax(140px, 0.9fr) minmax(0, 1.4fr) auto;
  gap: 8px;
  align-items: center;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.smoke-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.smoke-url {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--td-text-secondary);
  word-break: break-all;
}

.smoke-result {
  border: 1px solid var(--td-border);
  border-radius: var(--radius-md);
  padding: 12px;
  background-color: var(--td-bg-subtle);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.smoke-res-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
}

.smoke-err {
  font-size: 12.5px;
  color: var(--td-danger-text);
}

.smoke-block h5 {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--td-text-secondary);
}

@media (max-width: 640px) {
  .form-grid-2,
  .header-row {
    grid-template-columns: 1fr;
  }
}
</style>
