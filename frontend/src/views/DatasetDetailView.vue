<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  importDatasetVersionApi,
  listDatasetCasesApi,
  listDatasetsApi,
  listDatasetVersionsApi,
  type DatasetCaseItem,
  type DatasetItem,
  type DatasetVersionItem,
} from '../api/dataset'
import TdIcon from '../components/TdIcon.vue'
import { copyText, formatDateTime, parseJsonArray, prettyJson } from '../utils/format'

const route = useRoute()
const router = useRouter()
const datasetId = computed(() => Number(route.params.id))

const datasetMeta = ref<DatasetItem | null>(null)
const versions = ref<DatasetVersionItem[]>([])
const selectedVersion = ref<number | null>(null)
const cases = ref<DatasetCaseItem[]>([])
const caseSearch = ref('')

const loadingVersions = ref(false)
const loadingCases = ref(false)

const inspectingCase = ref<DatasetCaseItem | null>(null)
const inspectorVisible = ref(false)

const importVisible = ref(false)
const importing = ref(false)
const importForm = reactive({
  format: 'JSONL' as 'JSONL' | 'JSON' | 'CSV',
  note: '',
  content: '',
})

const SAMPLE_CONTENT: Record<'JSONL' | 'JSON' | 'CSV', { note: string; content: string }> = {
  JSONL: {
    note: '示例 JSONL 批次',
    content: `{"case_key":"case-01","input":{"question":"什么是 Java 21 虚拟线程？"},"reference_answer":"轻量级用户态线程，适合高并发 I/O 场景","assertions":[{"type":"contains","expected":"虚拟线程"}]}
{"case_key":"case-02","input":{"question":"返回合法 JSON 格式的退款状态"},"reference_answer":"{\\"status\\":\\"refunded\\"}","assertions":[{"type":"json_valid"}]}`,
  },
  JSON: {
    note: '示例 JSON 数组批次',
    content: JSON.stringify(
      [
        {
          case_key: 'case-01',
          input: { question: '简述 Redis 令牌桶相比固定窗口限流的优势' },
          reference_answer: '平滑处理突发流量，避免窗口边界双倍放行问题',
          assertions: [{ type: 'contains', expected: '突发' }],
        },
        {
          case_key: 'case-02',
          input: { question: '输出 JSON 包含字段 passed 和 reason' },
          reference_answer: '{"passed":true,"reason":"ok"}',
          assertions: [{ type: 'json_valid' }],
        },
      ],
      null,
      2,
    ),
  },
  CSV: {
    note: '示例 CSV 批次',
    content: `question,reference_answer
"什么是不可变数据集快照？","每次编辑生成新版本，历史版本不可修改，保证回归对比可复现"
"为什么外部模型调用只能保证 At-Least-Once？","上游 API 通常不提供幂等键，写库前崩溃重启会触发受控重试"`,
  },
}

const sortedVersions = computed(() => [...versions.value].sort((a, b) => b.versionNo - a.versionNo))

const activeVersionObj = computed(
  () => versions.value.find((v) => v.versionNo === selectedVersion.value) || null,
)

const filteredCases = computed(() => {
  const q = caseSearch.value.trim().toLowerCase()
  if (!q) return cases.value
  return cases.value.filter(
    (item) =>
      item.caseKey.toLowerCase().includes(q) ||
      item.inputJson.toLowerCase().includes(q) ||
      (item.referenceAnswer || '').toLowerCase().includes(q) ||
      item.caseHash.toLowerCase().includes(q),
  )
})

const nonEmptyLineCount = computed(() =>
  importForm.content
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean).length,
)

async function loadDatasetMeta() {
  try {
    const all = await listDatasetsApi()
    datasetMeta.value = all.find((d) => d.id === datasetId.value) || null
  } catch {
    datasetMeta.value = null
  }
}

async function loadVersions() {
  loadingVersions.value = true
  try {
    versions.value = await listDatasetVersionsApi(datasetId.value)
    if (versions.value.length > 0) {
      const latest = Math.max(...versions.value.map((v) => v.versionNo))
      if (!selectedVersion.value || !versions.value.some((v) => v.versionNo === selectedVersion.value)) {
        selectedVersion.value = latest
      } else {
        await loadCases(selectedVersion.value)
      }
    } else {
      selectedVersion.value = null
      cases.value = []
    }
  } finally {
    loadingVersions.value = false
  }
}

async function loadCases(versionNo: number) {
  loadingCases.value = true
  try {
    cases.value = await listDatasetCasesApi(datasetId.value, versionNo)
  } finally {
    loadingCases.value = false
  }
}

watch(selectedVersion, (v) => {
  if (v !== null) {
    loadCases(v)
  }
})

watch(datasetId, () => {
  selectedVersion.value = null
  cases.value = []
  loadDatasetMeta()
  loadVersions()
})

function openImportDialog(prefillSample = false) {
  if (prefillSample) {
    fillSample()
  }
  importVisible.value = true
}

function fillSample() {
  const sample = SAMPLE_CONTENT[importForm.format]
  importForm.note = sample.note
  importForm.content = sample.content
}

async function handleImport() {
  if (!importForm.content.trim()) {
    ElMessage.warning('请粘贴导入内容')
    return
  }
  importing.value = true
  try {
    const created = await importDatasetVersionApi(
      datasetId.value,
      importForm.format,
      importForm.content,
      importForm.note.trim(),
    )
    ElMessage.success(`成功导入 v${created.versionNo}（共 ${created.caseCount} 条用例）`)
    importVisible.value = false
    importForm.note = ''
    importForm.content = ''
    selectedVersion.value = created.versionNo
    await loadVersions()
  } finally {
    importing.value = false
  }
}

function summarizeInput(inputJson: string): string {
  try {
    const parsed = JSON.parse(inputJson) as Record<string, unknown>
    if (typeof parsed.question === 'string' && parsed.question.trim()) {
      return parsed.question
    }
    return inputJson.slice(0, 100)
  } catch {
    return inputJson.slice(0, 100)
  }
}

function assertionCount(assertionsJson: string): number {
  return parseJsonArray(assertionsJson).length
}

function openInspector(item: DatasetCaseItem) {
  inspectingCase.value = item
  inspectorVisible.value = true
}

onMounted(() => {
  loadDatasetMeta()
  loadVersions()
})
</script>

<template>
  <div class="td-page">
    <div class="td-page-header">
      <div class="header-main">
        <button type="button" class="back-btn" @click="router.push('/datasets')">
          <TdIcon name="arrow-left" :size="14" />
          返回数据集列表
        </button>
        <div class="title-row">
          <h1 class="td-page-title">
            {{ datasetMeta?.name || `数据集 #${datasetId}` }}
          </h1>
          <span class="id-badge td-mono">#{{ datasetId }}</span>
          <span v-if="activeVersionObj" class="td-pill td-pill--accent td-mono">
            v{{ activeVersionObj.versionNo }}
          </span>
        </div>
        <p v-if="datasetMeta?.description" class="td-page-desc">
          {{ datasetMeta.description }}
        </p>
      </div>

      <div class="header-actions">
        <el-button type="primary" @click="openImportDialog(false)">
          <TdIcon name="upload" :size="15" style="margin-right: 6px" />
          导入新版本
        </el-button>
      </div>
    </div>

    <!-- Empty versions state -->
    <section v-if="!loadingVersions && versions.length === 0" class="td-surface empty-state">
      <div class="empty-icon">
        <TdIcon name="layers" :size="22" />
      </div>
      <h3>该数据集尚无版本快照</h3>
      <p>
        导入一批 JSONL、JSON 或 CSV 用例后，系统会自动生成不可变版本 <code class="td-mono">v1</code> 并为每条用例计算规范化 <code class="td-mono">case_hash</code>。
      </p>
      <div class="empty-actions">
        <el-button type="primary" @click="openImportDialog(true)">
          <TdIcon name="upload" :size="15" style="margin-right: 6px" />
          使用示例导入 v1
        </el-button>
        <el-button @click="openImportDialog(false)">手动粘贴导入</el-button>
      </div>
    </section>

    <!-- Two-column version + cases workbench -->
    <div v-else class="detail-grid">
      <!-- Left: Immutable version history rail -->
      <aside class="td-surface version-rail">
        <div class="rail-header">
          <span class="rail-title">版本快照</span>
          <span class="rail-count td-mono">{{ versions.length }}</span>
        </div>

        <div v-if="loadingVersions && versions.length === 0" class="rail-skeleton">
          <el-skeleton :rows="4" animated />
        </div>

        <div v-else class="version-list" role="listbox" aria-label="数据集版本列表">
          <button
            v-for="v in sortedVersions"
            :key="v.id"
            type="button"
            class="version-item"
            :class="{ 'is-active': selectedVersion === v.versionNo }"
            role="option"
            :aria-selected="selectedVersion === v.versionNo"
            @click="selectedVersion = v.versionNo"
          >
            <div class="version-top">
              <span class="version-tag td-mono">v{{ v.versionNo }}</span>
              <span class="version-cases td-num">{{ v.caseCount }} 条用例</span>
            </div>
            <div class="version-note">{{ v.note || '无版本备注' }}</div>
            <div class="version-time td-mono">{{ formatDateTime(v.createdAt) }}</div>
          </button>
        </div>

        <div class="rail-footer">
          <TdIcon name="lock" :size="13" />
          <span>历史版本不可修改，确保评测可复现</span>
        </div>
      </aside>

      <!-- Right: Cases table -->
      <section class="td-surface cases-panel">
        <div class="td-toolbar">
          <div class="td-toolbar-group search-group">
            <!-- Compact version switcher on mobile -->
            <el-select
              v-model="selectedVersion"
              class="mobile-version-select"
              placeholder="选择版本"
            >
              <el-option
                v-for="v in sortedVersions"
                :key="v.id"
                :label="`v${v.versionNo} (${v.caseCount} 条)`"
                :value="v.versionNo"
              />
            </el-select>

            <el-input
              v-model="caseSearch"
              placeholder="搜索 case_key、输入内容、参考答案或哈希..."
              clearable
              class="case-search-input"
            >
              <template #prefix>
                <TdIcon name="search" :size="14" />
              </template>
            </el-input>
          </div>

          <div v-if="activeVersionObj" class="td-toolbar-group version-summary">
            <span class="summary-text td-num">
              当前 <strong>v{{ activeVersionObj.versionNo }}</strong> · 显示
              <strong>{{ filteredCases.length }}</strong> / {{ cases.length }} 条
            </span>
          </div>
        </div>

        <div v-if="loadingCases" class="skeleton-wrap">
          <el-skeleton :rows="6" animated />
        </div>

        <div v-else-if="filteredCases.length === 0" class="empty-state compact-empty">
          <h3>没有匹配的用例</h3>
          <p>尝试更换关键字或清空搜索条件。</p>
          <el-button @click="caseSearch = ''">清空搜索</el-button>
        </div>

        <el-table
          v-else
          :data="filteredCases"
          class="clickable-table"
          @row-click="(row: DatasetCaseItem) => openInspector(row)"
        >
          <el-table-column label="Case Key" width="148">
            <template #default="{ row }">
              <span class="key-chip td-mono">{{ row.caseKey }}</span>
            </template>
          </el-table-column>

          <el-table-column label="输入摘要" min-width="240">
            <template #default="{ row }">
              <span class="input-summary">{{ summarizeInput(row.inputJson) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="断言" width="96">
            <template #default="{ row }">
              <span
                class="td-pill td-num"
                :class="assertionCount(row.assertionsJson) > 0 ? 'td-pill--accent' : 'td-pill--neutral'"
              >
                {{ assertionCount(row.assertionsJson) }} 项
              </span>
            </template>
          </el-table-column>

          <el-table-column label="参考答案" min-width="190" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="ref-text">{{ row.referenceAnswer || '—' }}</span>
            </template>
          </el-table-column>

          <el-table-column label="Case Hash" width="136">
            <template #default="{ row }">
              <el-tooltip :content="`点击复制完整哈希：${row.caseHash}`" placement="top">
                <button
                  type="button"
                  class="hash-btn td-mono"
                  @click.stop="copyText(row.caseHash, '已复制完整 SHA-256 哈希')"
                >
                  <span>{{ row.caseHash.slice(0, 8) }}</span>
                  <TdIcon name="copy" :size="12" />
                </button>
              </el-tooltip>
            </template>
          </el-table-column>

          <el-table-column label="详情" width="84" align="right" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="openInspector(row)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <!-- Import version dialog -->
    <el-dialog v-model="importVisible" title="导入新版本（不可变快照）" width="680px">
      <el-form label-position="top">
        <el-form-item label="数据格式">
          <div class="format-bar">
            <el-radio-group v-model="importForm.format">
              <el-radio-button value="JSONL">JSONL</el-radio-button>
              <el-radio-button value="JSON">JSON 数组</el-radio-button>
              <el-radio-button value="CSV">CSV（两列）</el-radio-button>
            </el-radio-group>
            <el-button link type="primary" @click="fillSample">
              <TdIcon name="code" :size="14" style="margin-right: 4px" />
              填入 {{ importForm.format }} 示例
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="版本备注">
          <el-input
            v-model="importForm.note"
            maxlength="255"
            placeholder="例如：补充退款边界用例与 JSON 格式断言"
          />
        </el-form-item>

        <el-form-item label="用例内容" required>
          <el-input
            v-model="importForm.content"
            type="textarea"
            :rows="10"
            class="code-textarea"
            placeholder="粘贴 JSONL / JSON 数组 / CSV（首行 question,reference_answer）内容"
          />
          <div class="editor-meta td-num">
            <span>非空行数：{{ nonEmptyLineCount }}</span>
            <span>批内出现重复 case_key 时整批原子回滚</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="handleImport">
          导入并生成新版本
        </el-button>
      </template>
    </el-dialog>

    <!-- Case inspector drawer -->
    <el-drawer
      v-model="inspectorVisible"
      :title="inspectingCase ? `用例快照 · ${inspectingCase.caseKey}` : '用例快照'"
      size="540px"
    >
      <div v-if="inspectingCase" class="inspector-body">
        <div class="inspector-meta-grid">
          <div class="meta-cell">
            <span class="meta-label">Case Key</span>
            <span class="meta-value td-mono">{{ inspectingCase.caseKey }}</span>
          </div>
          <div class="meta-cell">
            <span class="meta-label">SHA-256 Case Hash</span>
            <button
              type="button"
              class="hash-copy-inline td-mono"
              @click="copyText(inspectingCase.caseHash, '已复制完整 Case Hash')"
            >
              <span>{{ inspectingCase.caseHash.slice(0, 16) }}…</span>
              <TdIcon name="copy" :size="12" />
            </button>
          </div>
        </div>

        <div class="code-section">
          <div class="code-section-head">
            <span>Input（输入变量 JSON）</span>
            <button
              type="button"
              class="copy-mini"
              @click="copyText(prettyJson(inspectingCase.inputJson))"
            >
              <TdIcon name="copy" :size="12" />
              复制
            </button>
          </div>
          <pre class="code-block td-mono">{{ prettyJson(inspectingCase.inputJson) }}</pre>
        </div>

        <div v-if="inspectingCase.referenceAnswer" class="code-section">
          <div class="code-section-head">
            <span>Reference Answer（参考答案）</span>
          </div>
          <pre class="code-block">{{ inspectingCase.referenceAnswer }}</pre>
        </div>

        <div class="code-section">
          <div class="code-section-head">
            <span>Assertions（断言规则）</span>
            <span class="td-mono">{{ assertionCount(inspectingCase.assertionsJson) }} 项</span>
          </div>
          <pre class="code-block td-mono">{{ prettyJson(inspectingCase.assertionsJson) }}</pre>
        </div>

        <div v-if="inspectingCase.messagesJson" class="code-section">
          <div class="code-section-head">
            <span>Messages（多轮对话上下文）</span>
          </div>
          <pre class="code-block td-mono">{{ prettyJson(inspectingCase.messagesJson) }}</pre>
        </div>

        <div v-if="inspectingCase.expectedDocIdsJson" class="code-section">
          <div class="code-section-head">
            <span>Expected Doc IDs（RAG 标准片段）</span>
          </div>
          <pre class="code-block td-mono">{{ prettyJson(inspectingCase.expectedDocIdsJson) }}</pre>
        </div>

        <div v-if="inspectingCase.tagsJson" class="code-section">
          <div class="code-section-head">
            <span>Tags（不参与 case_hash 计算）</span>
          </div>
          <pre class="code-block td-mono">{{ prettyJson(inspectingCase.tagsJson) }}</pre>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.header-main {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.back-btn {
  appearance: none;
  border: none;
  background: transparent;
  padding: 0;
  color: var(--td-text-muted);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: inherit;
  font-size: var(--td-text-xs);
  font-weight: 500;
  cursor: pointer;
  width: fit-content;
}

.back-btn:hover {
  color: var(--td-accent);
}

.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.id-badge {
  font-size: var(--td-text-xs);
  color: var(--td-text-muted);
}

.detail-grid {
  display: grid;
  grid-template-columns: 268px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.version-rail {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.rail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--td-border-subtle);
}

.rail-title {
  font-size: var(--td-text-sm);
  font-weight: 600;
  color: var(--td-text-primary);
}

.rail-count {
  font-size: var(--td-text-xs);
  color: var(--td-text-muted);
  padding: 1px 7px;
  border-radius: 999px;
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border-subtle);
}

.rail-skeleton {
  padding: 14px;
}

.version-list {
  display: flex;
  flex-direction: column;
  padding: 8px;
  gap: 4px;
  max-height: 560px;
  overflow-y: auto;
}

.version-item {
  appearance: none;
  width: 100%;
  text-align: left;
  padding: 10px 12px;
  border-radius: var(--td-radius-sm);
  border: 1px solid transparent;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  transition: background-color var(--td-duration-fast) var(--td-ease-out),
    border-color var(--td-duration-fast) var(--td-ease-out);
}

.version-item:hover {
  background-color: var(--td-bg-hover);
}

.version-item.is-active {
  background-color: var(--td-accent-soft);
  border-color: var(--td-accent-border);
}

.version-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.version-tag {
  font-size: var(--td-text-sm);
  font-weight: 600;
  color: var(--td-text-primary);
}

.version-item.is-active .version-tag {
  color: var(--td-accent);
}

.version-cases {
  font-size: var(--td-text-xs);
  color: var(--td-text-secondary);
}

.version-note {
  margin-top: 4px;
  font-size: var(--td-text-xs);
  color: var(--td-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.version-time {
  margin-top: 4px;
  font-size: 11px;
  color: var(--td-text-muted);
}

.rail-footer {
  padding: 10px 14px;
  border-top: 1px solid var(--td-border-subtle);
  background-color: var(--td-bg-subtle);
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--td-text-muted);
}

.cases-panel {
  min-width: 0;
  overflow: hidden;
}

.search-group {
  flex: 1;
  min-width: 220px;
}

.case-search-input {
  max-width: 360px;
}

.mobile-version-select {
  display: none;
  width: 160px;
}

.summary-text {
  font-size: var(--td-text-xs);
  color: var(--td-text-muted);
}

.summary-text strong {
  color: var(--td-text-primary);
}

.clickable-table :deep(.el-table__row) {
  cursor: pointer;
}

.key-chip {
  display: inline-block;
  padding: 2px 7px;
  border-radius: var(--td-radius-xs);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border-subtle);
  font-size: var(--td-text-xs);
  color: var(--td-text-primary);
}

.input-summary {
  color: var(--td-text-primary);
  font-size: var(--td-text-sm);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.ref-text {
  color: var(--td-text-secondary);
  font-size: var(--td-text-sm);
}

.hash-btn {
  appearance: none;
  border: 1px solid var(--td-border-subtle);
  background-color: var(--td-bg-subtle);
  color: var(--td-text-secondary);
  border-radius: var(--td-radius-xs);
  padding: 2px 7px;
  font-size: var(--td-text-xs);
  display: inline-flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  transition: border-color var(--td-duration-fast) var(--td-ease-out),
    color var(--td-duration-fast) var(--td-ease-out);
}

.hash-btn:hover {
  border-color: var(--td-accent-border);
  color: var(--td-accent);
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

.compact-empty {
  padding: 40px 24px;
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
  max-width: 50ch;
}

.empty-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.format-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 12px;
  flex-wrap: wrap;
}

.code-textarea :deep(.el-textarea__inner) {
  font-family: var(--td-font-mono);
  font-size: 12.5px;
  line-height: 1.55;
}

.editor-meta {
  width: 100%;
  margin-top: 6px;
  display: flex;
  justify-content: space-between;
  font-size: var(--td-text-xs);
  color: var(--td-text-muted);
}

/* Inspector drawer */
.inspector-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.inspector-meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.meta-cell {
  padding: 10px 12px;
  border-radius: var(--td-radius-sm);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border-subtle);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.meta-label {
  font-size: 11px;
  color: var(--td-text-muted);
}

.meta-value {
  font-size: var(--td-text-sm);
  font-weight: 600;
  color: var(--td-text-primary);
}

.hash-copy-inline {
  appearance: none;
  border: none;
  background: transparent;
  padding: 0;
  color: var(--td-accent);
  font-size: var(--td-text-xs);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  width: fit-content;
}

.code-section {
  border-radius: var(--td-radius-sm);
  border: 1px solid var(--td-border-subtle);
  background-color: var(--td-bg-code);
  overflow: hidden;
}

.code-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 12px;
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

.code-block {
  margin: 0;
  padding: 12px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--td-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow-y: auto;
}

@media (max-width: 980px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .version-rail {
    display: none;
  }

  .mobile-version-select {
    display: inline-block;
  }
}
</style>
