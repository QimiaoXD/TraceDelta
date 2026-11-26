<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { importDatasetVersionApi, type DatasetCaseItem } from '../api/dataset'
import { useConsoleDataStore } from '../stores/consoleData'
import TdIcon from '../components/TdIcon.vue'
import { copyText, formatDateTime, parseJsonArray, prettyJson } from '../utils/format'
import {
  validateDatasetImport,
  type ImportFormat,
  type ImportValidationResult,
} from '../utils/datasetImportValidator'

const route = useRoute()
const router = useRouter()
const store = useConsoleDataStore()
const datasetId = computed(() => Number(route.params.id))

const datasetMeta = computed(() => store.datasets.find((d) => d.id === datasetId.value) || null)
const versions = computed(() => store.getCachedVersions(datasetId.value) ?? [])
const versionsReady = computed(() => store.getCachedVersions(datasetId.value) !== undefined)

const selectedVersion = ref<number | null>(null)
const cases = computed(() =>
  selectedVersion.value === null
    ? []
    : (store.getCachedCases(datasetId.value, selectedVersion.value) ?? []),
)
const casesReady = computed(
  () =>
    selectedVersion.value !== null &&
    store.getCachedCases(datasetId.value, selectedVersion.value) !== undefined,
)
const caseSearch = ref('')
const loadError = ref('')
const liveAnnouncement = ref('')

const showSlowHint = ref(false)
let slowTimer: number | null = null

const copiedHashId = ref<number | null>(null)
const inspectorHashCopied = ref(false)
let hashCopyTimer: number | null = null

const inspectingCase = ref<DatasetCaseItem | null>(null)
const inspectorVisible = ref(false)

// Local version change summary (on-demand, strictly computed from fetched immutable versions)
const diffOpen = ref(false)
const diffLoading = ref(false)
const diffError = ref('')

// Import dialog state
const importVisible = ref(false)
const importing = ref(false)
const importServerError = ref('')
const importTouched = ref(false)
const importAlertRef = ref<HTMLElement | null>(null)
const importTextareaRef = ref<HTMLTextAreaElement | null>(null)
const importGutterRef = ref<HTMLElement | null>(null)

const importForm = reactive({
  format: 'JSONL' as ImportFormat,
  note: '',
  content: '',
})

const SAMPLE_CONTENT: Record<ImportFormat, string> = {
  JSONL: `{"case_key":"case-01","input":{"question":"什么是 Java 21 虚拟线程？"},"reference_answer":"轻量级用户态线程，适合高并发 I/O 场景","assertions":[{"type":"contains","expected":"虚拟线程"}]}
{"case_key":"case-02","input":{"question":"返回合法 JSON 格式的退款状态"},"reference_answer":"{\\"status\\":\\"refunded\\"}","assertions":[{"type":"json_valid"}]}`,
  JSON: JSON.stringify(
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
  CSV: `question,reference_answer
"什么是不可变数据集快照？","每次编辑生成新版本，历史版本不可修改，保证回归对比可复现"
"为什么外部模型调用只能保证 At-Least-Once？","上游 API 通常不提供幂等键，写库前崩溃重启会触发受控重试"`,
}

const sortedVersions = computed(() => [...versions.value].sort((a, b) => b.versionNo - a.versionNo))
const latestVersionNo = computed(() =>
  sortedVersions.value.length > 0 ? sortedVersions.value[0].versionNo : 0,
)
const activeVersion = computed(
  () => versions.value.find((v) => v.versionNo === selectedVersion.value) || null,
)
const previousVersion = computed(() => {
  if (selectedVersion.value === null) return null
  const idx = sortedVersions.value.findIndex((v) => v.versionNo === selectedVersion.value)
  if (idx < 0 || idx + 1 >= sortedVersions.value.length) return null
  return sortedVersions.value[idx + 1]
})

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

// Local validation (only displayed once user interacts or attempts submit)
const validationResult = computed<ImportValidationResult>(() => {
  if (!importForm.content.trim()) {
    return { valid: false, issues: [], cases: [] }
  }
  return validateDatasetImport(importForm.format, importForm.content)
})

const showValidationErrors = computed(
  () => importTouched.value && !validationResult.value.valid && validationResult.value.issues.length > 0,
)

const errorLineSet = computed(() => new Set(validationResult.value.issues.map((i) => i.line)))

const totalEditorLines = computed(() => {
  const count = importForm.content.split(/\r?\n/).length
  return Math.max(count, 10)
})

const nonEmptyLineCount = computed(
  () =>
    importForm.content
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter(Boolean).length,
)

const canSubmitImport = computed(
  () =>
    !importing.value &&
    Boolean(importForm.content.trim()) &&
    validationResult.value.valid &&
    validationResult.value.cases.length > 0,
)

// Local version diff (strictly based on fetched immutable cases of current vs previous version)
const localDiffSummary = computed(() => {
  if (!diffOpen.value || !activeVersion.value || !previousVersion.value) return null
  const prevCases = store.getCachedCases(datasetId.value, previousVersion.value.versionNo)
  if (!prevCases || !casesReady.value) return null

  const prevMap = new Map(prevCases.map((c) => [c.caseKey, c]))
  const curMap = new Map(cases.value.map((c) => [c.caseKey, c]))

  const added: string[] = []
  const modified: string[] = []
  const unchanged: string[] = []
  const removed: string[] = []

  for (const [key, cur] of curMap.entries()) {
    const prev = prevMap.get(key)
    if (!prev) {
      added.push(key)
    } else if (prev.caseHash !== cur.caseHash) {
      modified.push(key)
    } else {
      unchanged.push(key)
    }
  }
  for (const key of prevMap.keys()) {
    if (!curMap.has(key)) {
      removed.push(key)
    }
  }

  return {
    baseVersionNo: previousVersion.value.versionNo,
    targetVersionNo: activeVersion.value.versionNo,
    added,
    removed,
    modified,
    unchanged,
  }
})

function announce(text: string) {
  liveAnnouncement.value = ''
  nextTick(() => {
    liveAnnouncement.value = text
  })
}

function startSlowTimer() {
  if (slowTimer !== null) window.clearTimeout(slowTimer)
  slowTimer = window.setTimeout(() => {
    showSlowHint.value = true
  }, 180)
}

function stopSlowTimer() {
  if (slowTimer !== null) {
    window.clearTimeout(slowTimer)
    slowTimer = null
  }
  showSlowHint.value = false
}

async function loadVersionsAndCases() {
  loadError.value = ''
  const id = datasetId.value
  if (!versionsReady.value) startSlowTimer()
  try {
    const fetched = await store.fetchVersions(id)
    if (fetched.length > 0) {
      const latest = Math.max(...fetched.map((v) => v.versionNo))
      if (selectedVersion.value === null || !fetched.some((v) => v.versionNo === selectedVersion.value)) {
        selectedVersion.value = latest
      } else {
        await store.fetchCases(id, selectedVersion.value)
      }
    } else {
      selectedVersion.value = null
    }
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '加载版本列表失败'
  } finally {
    stopSlowTimer()
  }
}

watch(selectedVersion, async (v) => {
  diffOpen.value = false
  diffError.value = ''
  if (v === null) return
  const id = datasetId.value
  if (store.getCachedCases(id, v) === undefined) startSlowTimer()
  try {
    await store.fetchCases(id, v)
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : `加载 v${v} 用例失败`
  } finally {
    stopSlowTimer()
  }
})

watch(datasetId, () => {
  selectedVersion.value = null
  caseSearch.value = ''
  diffOpen.value = false
  loadVersionsAndCases()
})

async function toggleLocalDiff() {
  if (diffOpen.value) {
    diffOpen.value = false
    return
  }
  if (!previousVersion.value) return
  diffOpen.value = true
  diffError.value = ''
  const prevNo = previousVersion.value.versionNo
  if (store.getCachedCases(datasetId.value, prevNo) === undefined) {
    diffLoading.value = true
    try {
      await store.fetchCases(datasetId.value, prevNo)
    } catch (err) {
      diffError.value = err instanceof Error ? err.message : `无法加载 v${prevNo} 数据进行对比`
    } finally {
      diffLoading.value = false
    }
  }
}

function openImportDialog() {
  importServerError.value = ''
  importTouched.value = false
  importVisible.value = true
}

function onImportDialogOpened() {
  nextTick(() => {
    importTextareaRef.value?.focus()
  })
}

function setImportFormat(fmt: ImportFormat) {
  importForm.format = fmt
  importServerError.value = ''
  if (importForm.content.trim()) {
    importTouched.value = true
  }
}

function fillSample() {
  importForm.content = SAMPLE_CONTENT[importForm.format]
  importServerError.value = ''
  importTouched.value = true
  announce(`已填入 ${importForm.format} 示例，共 ${validationResult.value.cases.length} 条用例`)
}

function onEditorInput() {
  importTouched.value = true
  importServerError.value = ''
}

function onEditorBlur() {
  if (importForm.content.trim()) {
    importTouched.value = true
  }
}

function syncEditorScroll() {
  if (importTextareaRef.value && importGutterRef.value) {
    importGutterRef.value.scrollTop = importTextareaRef.value.scrollTop
  }
}

function jumpToEditorLine(lineNo: number) {
  const el = importTextareaRef.value
  if (!el) return
  const lines = importForm.content.split('\n')
  let offset = 0
  for (let i = 0; i < Math.min(lineNo - 1, lines.length); i++) {
    offset += lines[i].length + 1
  }
  const targetLineLen = lines[Math.max(0, lineNo - 1)]?.length ?? 0
  el.focus()
  el.setSelectionRange(offset, offset + targetLineLen)
  const lineHeight = 20
  el.scrollTop = Math.max(0, (lineNo - 3) * lineHeight)
  syncEditorScroll()
}

async function handleImportBeforeClose(done: () => void) {
  if (importing.value) return
  if (importForm.content.trim() || importForm.note.trim()) {
    try {
      await ElMessageBox.confirm('当前有未导入的编辑内容，确定关闭弹窗吗？', '放弃导入', {
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

async function requestCloseImport() {
  await handleImportBeforeClose(() => {
    importVisible.value = false
  })
}

async function handleImport() {
  if (importing.value) return
  importServerError.value = ''
  importTouched.value = true

  if (!importForm.content.trim()) {
    importServerError.value = '请粘贴或填入待导入的用例内容。'
    await nextTick()
    importTextareaRef.value?.focus()
    return
  }

  if (!validationResult.value.valid) {
    await nextTick()
    importAlertRef.value?.focus()
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
    const msg = `服务端已创建 v${created.versionNo}（共 ${created.caseCount} 条用例）`
    ElMessage.success(msg)
    announce(msg)
    importVisible.value = false
    importForm.note = ''
    importForm.content = ''
    importTouched.value = false
    await store.fetchVersions(datasetId.value)
    if (selectedVersion.value === created.versionNo) {
      await store.fetchCases(datasetId.value, created.versionNo)
    } else {
      selectedVersion.value = created.versionNo
    }
  } catch (err) {
    importServerError.value = err instanceof Error ? err.message : '服务端导入失败，请根据提示修改后重试'
    await nextTick()
    importAlertRef.value?.focus()
  } finally {
    importing.value = false
  }
}

async function handleCopyHash(item: DatasetCaseItem, fromInspector = false) {
  await copyText(item.caseHash, '已复制完整 SHA-256 哈希')
  announce(`已复制 ${item.caseKey} 的完整 SHA-256 哈希`)
  if (fromInspector) {
    inspectorHashCopied.value = true
  } else {
    copiedHashId.value = item.id
  }
  if (hashCopyTimer !== null) window.clearTimeout(hashCopyTimer)
  hashCopyTimer = window.setTimeout(() => {
    copiedHashId.value = null
    inspectorHashCopied.value = false
  }, 1800)
}

function summarizeInput(inputJson: string): string {
  try {
    const parsed = JSON.parse(inputJson) as Record<string, unknown>
    if (typeof parsed.question === 'string' && parsed.question.trim()) return parsed.question
    return inputJson.slice(0, 140)
  } catch {
    return inputJson.slice(0, 140)
  }
}

function assertionCount(assertionsJson: string): number {
  return parseJsonArray(assertionsJson).length
}

function openInspector(item: DatasetCaseItem) {
  inspectingCase.value = item
  inspectorHashCopied.value = false
  inspectorVisible.value = true
}

onMounted(async () => {
  if (!store.datasetsLoaded) {
    try {
      await store.fetchDatasets()
    } catch {
      // meta is optional; page still works with id fallback
    }
  }
  loadVersionsAndCases()
})

onBeforeUnmount(() => {
  stopSlowTimer()
  if (hashCopyTimer !== null) window.clearTimeout(hashCopyTimer)
})
</script>

<template>
  <div class="view-stack">
    <div class="sr-only" aria-live="polite">{{ liveAnnouncement }}</div>

    <header class="view-header">
      <div class="view-title-group">
        <h1 class="view-title" :title="datasetMeta?.name || `数据集 #${datasetId}`">
          {{ datasetMeta?.name || `数据集 #${datasetId}` }}
        </h1>
        <span class="micro-badge mono">#{{ datasetId }}</span>
        <span v-if="activeVersion" class="micro-badge mono">当前 v{{ activeVersion.versionNo }}</span>
      </div>

      <div class="view-actions">
        <button type="button" class="btn btn--secondary" @click="router.push('/datasets')">
          <TdIcon name="arrow-left" :size="14" />
          <span>返回列表</span>
        </button>
        <button type="button" class="btn btn--primary" @click="openImportDialog">
          <TdIcon name="upload" :size="14" />
          <span>导入新版本</span>
        </button>
      </div>
    </header>

    <div v-if="loadError" class="error-banner" role="alert">
      <span class="error-banner-body">
        <TdIcon name="alert-circle" :size="15" />
        <span>{{ loadError }}</span>
      </span>
      <button type="button" class="btn btn--secondary btn--sm" @click="loadVersionsAndCases">
        <TdIcon name="refresh" :size="13" />
        <span>重试</span>
      </button>
    </div>

    <div v-if="!versionsReady && !loadError">
      <p v-if="showSlowHint" class="slow-loading" aria-live="polite">正在加载版本快照…</p>
    </div>

    <section v-else-if="versionsReady && versions.length === 0" class="empty-panel">
      <div class="empty-panel-icon" aria-hidden="true">
        <TdIcon name="layers" :size="18" />
      </div>
      <h2>尚无版本快照</h2>
      <p>导入 JSONL、JSON 或 CSV 文件以生成不可变版本 v1。</p>
      <div class="empty-panel-actions">
        <button type="button" class="btn btn--primary" @click="openImportDialog">
          <TdIcon name="upload" :size="14" />
          <span>导入首个版本</span>
        </button>
      </div>
    </section>

    <div v-else-if="versionsReady" class="detail-grid">
      <aside class="list-panel version-panel" aria-label="不可变版本历史">
        <div class="panel-head">
          <div class="panel-head-left">
            <span class="panel-head-title">版本历史</span>
            <span class="micro-badge">
              <TdIcon name="lock" :size="11" />
              <span>不可变</span>
            </span>
          </div>
          <span class="panel-head-meta mono">{{ versions.length }} 个</span>
        </div>

        <div class="version-list" role="listbox" aria-label="选择要查看的只读版本">
          <button
            v-for="v in sortedVersions"
            :key="v.id"
            type="button"
            class="version-row"
            :class="{ 'is-active': selectedVersion === v.versionNo }"
            role="option"
            :aria-selected="selectedVersion === v.versionNo"
            @click="selectedVersion = v.versionNo"
          >
            <div class="version-top">
              <div class="version-top-left">
                <span class="version-no mono">v{{ v.versionNo }}</span>
                <span v-if="v.versionNo === latestVersionNo" class="micro-badge">最新</span>
                <span v-if="selectedVersion === v.versionNo" class="micro-badge is-current">当前</span>
              </div>
              <span class="version-count mono">{{ v.caseCount }} 条</span>
            </div>
            <div v-if="v.note && v.note.trim()" class="version-note" :title="v.note">
              {{ v.note.trim() }}
            </div>
            <div class="version-date mono">{{ formatDateTime(v.createdAt) }}</div>
          </button>
        </div>
      </aside>

      <section class="cases-column" aria-label="版本用例内容">
        <div v-if="activeVersion" class="snapshot-bar">
          <div class="snapshot-bar-info">
            <span class="snapshot-version mono">v{{ activeVersion.versionNo }}</span>
            <span class="micro-badge">
              <TdIcon name="lock" :size="11" />
              <span>只读快照</span>
            </span>
            <span class="snapshot-meta mono">{{ activeVersion.caseCount }} 条用例</span>
            <span class="snapshot-meta mono">创建于 {{ formatDateTime(activeVersion.createdAt) }}</span>
            <span v-if="activeVersion.note && activeVersion.note.trim()" class="snapshot-note">
              · {{ activeVersion.note.trim() }}
            </span>
          </div>

          <div class="snapshot-bar-action">
            <button
              v-if="previousVersion"
              type="button"
              class="btn btn--secondary btn--sm"
              :aria-expanded="diffOpen"
              @click="toggleLocalDiff"
            >
              <TdIcon name="git-compare" :size="13" />
              <span>{{ diffOpen ? '收起版本变化' : `对比 v${previousVersion.versionNo}（本地）` }}</span>
            </button>
            <span v-else class="snapshot-initial-tag">初始版本</span>
          </div>
        </div>

        <section v-if="diffOpen" class="local-diff-panel" aria-label="本地计算的版本变化摘要">
          <div class="local-diff-head">
            <span class="local-diff-title">
              本地计算的版本变化摘要（v{{ previousVersion?.versionNo }} → v{{ activeVersion?.versionNo }}）
            </span>
            <span class="local-diff-source">基于已获取用例的 case_key 与 case_hash 本地比对</span>
          </div>

          <p v-if="diffLoading" class="local-diff-note">正在加载 v{{ previousVersion?.versionNo }} 用例…</p>
          <p v-else-if="diffError" class="field-error-text" role="alert">{{ diffError }}</p>
          <div v-else-if="localDiffSummary" class="local-diff-body">
            <div class="local-diff-counts mono">
              <span class="status-tag status-tag--success">新增 {{ localDiffSummary.added.length }}</span>
              <span class="status-tag status-tag--warning">修改 {{ localDiffSummary.modified.length }}</span>
              <span class="status-tag status-tag--danger">删除 {{ localDiffSummary.removed.length }}</span>
              <span class="micro-badge">未变 {{ localDiffSummary.unchanged.length }}</span>
            </div>
            <div
              v-if="
                localDiffSummary.added.length === 0 &&
                localDiffSummary.modified.length === 0 &&
                localDiffSummary.removed.length === 0
              "
              class="local-diff-note"
            >
              两个版本的全部用例 case_key 与规范化哈希完全一致。
            </div>
            <div v-else class="local-diff-keys mono">
              <div v-if="localDiffSummary.added.length">
                新增：{{ localDiffSummary.added.join(', ') }}
              </div>
              <div v-if="localDiffSummary.modified.length">
                修改：{{ localDiffSummary.modified.join(', ') }}
              </div>
              <div v-if="localDiffSummary.removed.length">
                删除：{{ localDiffSummary.removed.join(', ') }}
              </div>
            </div>
          </div>
        </section>

        <div class="view-toolbar">
          <div class="view-toolbar-group">
            <label class="mobile-version-wrap">
              <span class="sr-only">选择版本</span>
              <el-select v-model="selectedVersion" class="mobile-version-select" placeholder="选择版本">
                <el-option
                  v-for="v in sortedVersions"
                  :key="v.id"
                  :label="`v${v.versionNo}（${v.caseCount} 条 · 只读）`"
                  :value="v.versionNo"
                />
              </el-select>
            </label>

            <div class="search-box search-box--sm">
              <TdIcon name="search" :size="14" class="search-box-icon" />
              <input
                v-model="caseSearch"
                type="search"
                class="search-box-input"
                placeholder="筛选 case_key、问题、参考答案或哈希…"
                aria-label="筛选当前版本的用例"
              />
              <button
                v-if="caseSearch"
                type="button"
                class="search-box-clear"
                aria-label="清空用例筛选"
                @click="caseSearch = ''"
              >
                <TdIcon name="x" :size="12" />
              </button>
            </div>
          </div>

          <span v-if="casesReady" class="view-count mono" aria-live="polite">
            {{ filteredCases.length }} / {{ cases.length }} 条
          </span>
        </div>

        <div v-if="!casesReady">
          <p v-if="showSlowHint" class="slow-loading" aria-live="polite">正在加载用例列表…</p>
        </div>

        <section v-else-if="cases.length === 0" class="empty-panel">
          <h2>该版本暂无用例</h2>
        </section>

        <section v-else-if="filteredCases.length === 0" class="empty-panel">
          <h2>无匹配用例</h2>
          <p>当前筛选词「{{ caseSearch }}」在该版本中无匹配记录。</p>
          <button type="button" class="btn btn--secondary btn--sm" @click="caseSearch = ''">清空筛选</button>
        </section>

        <div v-else class="data-table-wrap">
          <table class="data-table" aria-label="版本用例列表">
            <thead>
              <tr>
                <th scope="col">case_key</th>
                <th scope="col">问题摘要与参考答案</th>
                <th scope="col" class="col-num">断言</th>
                <th scope="col">case_hash</th>
                <th scope="col" class="col-actions">详情</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in filteredCases" :key="item.id">
                <td class="cell-nowrap">
                  <button
                    type="button"
                    class="case-key-btn mono"
                    @click="openInspector(item)"
                  >
                    {{ item.caseKey }}
                  </button>
                </td>
                <td class="case-summary-cell">
                  <div class="case-question" :title="summarizeInput(item.inputJson)">
                    {{ summarizeInput(item.inputJson) }}
                  </div>
                  <div v-if="item.referenceAnswer" class="table-subtext" :title="item.referenceAnswer">
                    {{ item.referenceAnswer }}
                  </div>
                </td>
                <td class="col-num mono">{{ assertionCount(item.assertionsJson) }} 项</td>
                <td class="cell-nowrap">
                  <button
                    type="button"
                    class="code-pill code-pill--copy"
                    :class="{ 'is-copied': copiedHashId === item.id }"
                    :title="`复制完整哈希：${item.caseHash}`"
                    :aria-label="`复制 ${item.caseKey} 的完整 SHA-256 哈希`"
                    @click="handleCopyHash(item, false)"
                  >
                    <span>{{ item.caseHash.slice(0, 8) }}</span>
                    <TdIcon :name="copiedHashId === item.id ? 'check' : 'copy'" :size="12" class="code-pill-icon" />
                  </button>
                </td>
                <td class="col-actions">
                  <button
                    type="button"
                    class="btn btn--secondary btn--sm"
                    :aria-label="`查看 ${item.caseKey} 完整内容`"
                    @click="openInspector(item)"
                  >
                    查看
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <el-dialog
      v-model="importVisible"
      title="导入新版本"
      width="680px"
      :before-close="handleImportBeforeClose"
      @opened="onImportDialogOpened"
    >
      <div
        v-if="importServerError"
        ref="importAlertRef"
        class="form-alert"
        role="alert"
        tabindex="-1"
      >
        <TdIcon name="alert-circle" :size="15" />
        <span>服务端校验未通过：{{ importServerError }}</span>
      </div>

      <div
        v-else-if="showValidationErrors"
        ref="importAlertRef"
        class="validation-summary"
        role="alert"
        tabindex="-1"
      >
        <div class="validation-summary-head">
          <TdIcon name="alert-circle" :size="15" />
          <span>本地预检发现 {{ validationResult.issues.length }} 处问题，修复后方可提交</span>
        </div>
        <ul class="validation-issue-list">
          <li v-for="(issue, idx) in validationResult.issues.slice(0, 8)" :key="idx">
            <button
              type="button"
              class="issue-jump-btn"
              @click="jumpToEditorLine(issue.line)"
            >
              <span class="mono issue-loc">
                第 {{ issue.line }} 行<template v-if="issue.column"> 列 {{ issue.column }}</template>
              </span>
              <span>{{ issue.message }}</span>
            </button>
          </li>
        </ul>
        <p v-if="validationResult.issues.length > 8" class="validation-more">
          其余 {{ validationResult.issues.length - 8 }} 处问题将在修复前序错误后更新。
        </p>
      </div>

      <el-form label-position="top">
        <el-form-item label="导入格式">
          <div class="import-format-row">
            <div class="seg-control seg-control--sm" role="radiogroup" aria-label="选择导入格式">
              <button
                v-for="fmt in (['JSONL', 'JSON', 'CSV'] as const)"
                :key="fmt"
                type="button"
                role="radio"
                class="seg-btn"
                :class="{ 'is-active': importForm.format === fmt }"
                :aria-checked="importForm.format === fmt"
                @click="setImportFormat(fmt)"
              >
                {{ fmt }}
              </button>
            </div>
            <button type="button" class="btn btn--secondary btn--sm" @click="fillSample">
              <TdIcon name="code" :size="13" />
              <span>填入 {{ importForm.format }} 示例</span>
            </button>
          </div>
          <p class="field-help-text">
            <template v-if="importForm.format === 'JSONL'">
              每行一个 JSON 对象，必填 <span class="mono">case_key</span>（批内忽略大小写唯一）与对象类型 <span class="mono">input</span>。
            </template>
            <template v-else-if="importForm.format === 'JSON'">
              顶层为 JSON 数组，每项必填 <span class="mono">case_key</span> 与对象类型 <span class="mono">input</span>。
            </template>
            <template v-else>
              首行表头须为 <span class="mono">question,reference_answer</span>，每行两列（含逗号请用双引号包裹），导入时自动生成 <span class="mono">case-0001</span> 序号。
            </template>
          </p>
        </el-form-item>

        <el-form-item label="版本备注（可选）">
          <el-input v-model="importForm.note" placeholder="例如：补充边界断言与异常用例" maxlength="200" />
        </el-form-item>

        <el-form-item label="用例内容" required>
          <div class="code-editor-shell" :class="{ 'has-error': showValidationErrors }">
            <div ref="importGutterRef" class="code-editor-gutter mono" aria-hidden="true">
              <div
                v-for="lineNo in totalEditorLines"
                :key="lineNo"
                class="gutter-line"
                :class="{ 'is-error-line': showValidationErrors && errorLineSet.has(lineNo) }"
              >
                {{ lineNo }}
              </div>
            </div>
            <textarea
              ref="importTextareaRef"
              v-model="importForm.content"
              class="code-editor-textarea mono"
              rows="10"
              spellcheck="false"
              :placeholder="`在此粘贴 ${importForm.format} 内容…`"
              aria-label="待导入用例内容"
              :aria-invalid="showValidationErrors"
              @input="onEditorInput"
              @blur="onEditorBlur"
              @scroll="syncEditorScroll"
            />
          </div>

          <div class="editor-status-bar">
            <span class="mono">非空行：{{ nonEmptyLineCount }}</span>
            <span v-if="importForm.content.trim() && validationResult.valid" class="status-tag status-tag--success">
              <TdIcon name="check-circle" :size="12" />
              <span>本地预检通过 · 解析 {{ validationResult.cases.length }} 条</span>
            </span>
            <span v-else-if="showValidationErrors" class="status-tag status-tag--danger">
              <TdIcon name="alert-circle" :size="12" />
              <span>本地预检未通过（{{ validationResult.issues.length }} 处错误）</span>
            </span>
          </div>
        </el-form-item>
      </el-form>

      <div v-if="importForm.content.trim() && validationResult.valid" class="import-preview-bar">
        <div class="import-preview-title">
          <TdIcon name="info" :size="14" />
          <span>
            即将创建不可变新版本（预计 <span class="mono">v{{ latestVersionNo + 1 }}</span>，共
            <span class="mono">{{ validationResult.cases.length }}</span> 条用例<template v-if="importForm.note.trim()"> · 备注：{{ importForm.note.trim() }}</template>）
          </span>
        </div>
        <div class="import-preview-note">
          此结果仅代表浏览器本地预检；最终版本号、入库条数与规范化 SHA-256 哈希以服务端响应为准。
        </div>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <button
            type="button"
            class="btn btn--secondary btn--sm"
            :disabled="importing"
            @click="requestCloseImport"
          >
            取消
          </button>
          <button
            type="button"
            class="btn btn--primary btn--sm"
            :disabled="!canSubmitImport"
            :aria-busy="importing"
            :title="!canSubmitImport && !importing ? '请先输入并通过本地格式校验' : undefined"
            @click="handleImport"
          >
            {{ importing ? '正在提交服务端…' : '确认导入新版本' }}
          </button>
        </div>
      </template>
    </el-dialog>

    <el-drawer
      v-model="inspectorVisible"
      :title="inspectingCase ? `只读用例 · ${inspectingCase.caseKey}` : '只读用例详情'"
      size="540px"
    >
      <div v-if="inspectingCase" class="drawer-stack">
        <div class="readonly-banner">
          <TdIcon name="lock" :size="13" />
          <span>
            属于 <span class="mono">v{{ activeVersion?.versionNo }}</span> 不可变快照，仅供核查与复制，不可原地修改。
          </span>
        </div>

        <div class="meta-grid">
          <div class="meta-cell">
            <span class="meta-label">case_key</span>
            <span class="meta-val mono">{{ inspectingCase.caseKey }}</span>
          </div>
          <div class="meta-cell">
            <span class="meta-label">断言数量</span>
            <span class="meta-val mono">{{ assertionCount(inspectingCase.assertionsJson) }} 项</span>
          </div>
        </div>

        <div class="code-box">
          <div class="code-box-head">
            <span>完整 case_hash（SHA-256）</span>
            <button
              type="button"
              class="code-box-copy"
              @click="handleCopyHash(inspectingCase, true)"
            >
              <TdIcon :name="inspectorHashCopied ? 'check' : 'copy'" :size="12" />
              <span>{{ inspectorHashCopied ? '已复制' : '复制完整哈希' }}</span>
            </button>
          </div>
          <pre class="code-box-pre mono hash-full">{{ inspectingCase.caseHash }}</pre>
        </div>

        <div class="code-box">
          <div class="code-box-head">
            <span>Input</span>
            <button
              type="button"
              class="code-box-copy"
              @click="copyText(prettyJson(inspectingCase.inputJson), '已复制 Input JSON')"
            >
              <TdIcon name="copy" :size="12" />
              <span>复制</span>
            </button>
          </div>
          <pre class="code-box-pre mono">{{ prettyJson(inspectingCase.inputJson) }}</pre>
        </div>

        <div v-if="inspectingCase.referenceAnswer" class="code-box">
          <div class="code-box-head"><span>Reference Answer</span></div>
          <pre class="code-box-pre">{{ inspectingCase.referenceAnswer }}</pre>
        </div>

        <div class="code-box">
          <div class="code-box-head">
            <span>Assertions</span>
            <span class="mono">{{ assertionCount(inspectingCase.assertionsJson) }} 项</span>
          </div>
          <pre class="code-box-pre mono">{{ prettyJson(inspectingCase.assertionsJson) }}</pre>
        </div>

        <div v-if="inspectingCase.messagesJson" class="code-box">
          <div class="code-box-head"><span>Messages</span></div>
          <pre class="code-box-pre mono">{{ prettyJson(inspectingCase.messagesJson) }}</pre>
        </div>

        <div v-if="inspectingCase.expectedDocIdsJson" class="code-box">
          <div class="code-box-head"><span>Expected Doc IDs</span></div>
          <pre class="code-box-pre mono">{{ prettyJson(inspectingCase.expectedDocIdsJson) }}</pre>
        </div>

        <div v-if="inspectingCase.tagsJson" class="code-box">
          <div class="code-box-head"><span>Tags</span></div>
          <pre class="code-box-pre mono">{{ prettyJson(inspectingCase.tagsJson) }}</pre>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.detail-grid {
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.version-panel {
  display: flex;
  flex-direction: column;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--td-border);
  background-color: var(--td-bg-subtle);
}

.panel-head-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.panel-head-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--td-text-primary);
}

.panel-head-meta {
  font-size: 12px;
  color: var(--td-text-secondary);
}

.version-list {
  display: flex;
  flex-direction: column;
  max-height: 560px;
  overflow-y: auto;
}

.version-row {
  position: relative;
  appearance: none;
  border: none;
  border-bottom: 1px solid var(--td-border);
  background: transparent;
  width: 100%;
  padding: 12px 14px;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
  transition: background-color var(--dur-fast) var(--ease);
}

.version-row:last-child {
  border-bottom: none;
}

.version-row:hover {
  background-color: var(--td-bg-row-hover);
}

.version-row.is-active {
  background-color: var(--td-bg-subtle);
}

.version-row.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background-color: var(--td-text-primary);
}

.version-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.version-top-left {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.version-no {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--td-text-primary);
}

.micro-badge.is-current {
  border-color: var(--td-border-strong);
  background-color: var(--td-bg-surface);
  color: var(--td-text-primary);
}

.version-count {
  font-size: 12px;
  color: var(--td-text-secondary);
  white-space: nowrap;
}

.version-note {
  margin-top: 4px;
  font-size: 12.5px;
  color: var(--td-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.version-date {
  margin-top: 4px;
  font-size: 12px;
  color: var(--td-text-secondary);
}

.cases-column {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.snapshot-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  min-height: 54px;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  background-color: var(--td-bg-surface);
  border: 1px solid var(--td-border);
}

.snapshot-bar-action {
  display: inline-flex;
  align-items: center;
  min-height: var(--ctrl-sm);
  flex-shrink: 0;
}

.snapshot-initial-tag {
  font-size: 12px;
  color: var(--td-text-secondary);
  padding: 0 4px;
  white-space: nowrap;
}

.snapshot-bar-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.snapshot-version {
  font-size: 14px;
  font-weight: 600;
  color: var(--td-text-primary);
}

.snapshot-meta {
  font-size: 12.5px;
  color: var(--td-text-secondary);
}

.snapshot-note {
  font-size: 13px;
  color: var(--td-text-secondary);
}

.local-diff-panel {
  padding: 12px 14px;
  border-radius: var(--radius-md);
  background-color: var(--td-bg-surface);
  border: 1px solid var(--td-border-strong);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.local-diff-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.local-diff-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--td-text-primary);
}

.local-diff-source {
  font-size: 12px;
  color: var(--td-text-secondary);
}

.local-diff-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.local-diff-counts {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.local-diff-keys {
  font-size: 12px;
  color: var(--td-text-secondary);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.local-diff-note {
  margin: 0;
  font-size: 12.5px;
  color: var(--td-text-secondary);
}

.mobile-version-wrap {
  display: none;
}

.mobile-version-select {
  width: 180px;
}

.cell-nowrap {
  white-space: nowrap;
}

.case-key-btn {
  appearance: none;
  border: none;
  background: transparent;
  padding: 2px 4px;
  margin: -2px -4px;
  border-radius: var(--radius-xs);
  color: var(--td-text-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  text-align: left;
}

.case-key-btn:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}

.case-summary-cell {
  min-width: 220px;
  max-width: 460px;
}

.case-question {
  font-size: 13.5px;
  color: var(--td-text-primary);
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* Import editor with synchronized line gutter */
.import-format-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 10px;
  flex-wrap: wrap;
}

.validation-summary {
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background-color: var(--td-danger-bg);
  border: 1px solid var(--td-danger-border);
  color: var(--td-danger-text);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.validation-summary-head {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
}

.validation-issue-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.issue-jump-btn {
  appearance: none;
  width: 100%;
  border: 1px solid transparent;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--td-danger-text);
  padding: 4px 6px;
  text-align: left;
  font-family: inherit;
  font-size: 12.5px;
  display: flex;
  align-items: baseline;
  gap: 8px;
  cursor: pointer;
}

.issue-jump-btn:hover {
  background-color: var(--td-bg-surface);
  border-color: var(--td-danger-border);
}

.issue-loc {
  flex-shrink: 0;
  font-weight: 600;
}

.validation-more {
  margin: 0;
  font-size: 12px;
}

.code-editor-shell {
  width: 100%;
  display: flex;
  border-radius: var(--radius-sm);
  border: 1px solid var(--td-border-strong);
  background-color: var(--td-bg-surface);
  overflow: hidden;
  transition: border-color var(--dur-fast) var(--ease);
}

.code-editor-shell:focus-within {
  border-color: var(--td-focus);
  box-shadow: 0 0 0 1px var(--td-focus);
}

.code-editor-shell.has-error {
  border-color: var(--td-danger);
}

.code-editor-gutter {
  width: 42px;
  flex-shrink: 0;
  padding: 10px 6px 10px 0;
  background-color: var(--td-bg-subtle);
  border-right: 1px solid var(--td-border);
  text-align: right;
  font-size: 12px;
  line-height: 20px;
  color: var(--td-text-secondary);
  user-select: none;
  max-height: 230px;
  overflow: hidden;
}

.gutter-line {
  height: 20px;
  padding-right: 4px;
}

.gutter-line.is-error-line {
  background-color: var(--td-danger-bg);
  color: var(--td-danger-text);
  font-weight: 600;
}

.code-editor-textarea {
  flex: 1;
  min-width: 0;
  height: 230px;
  padding: 10px 12px;
  border: none;
  resize: vertical;
  background-color: transparent;
  color: var(--td-text-primary);
  font-size: 12.5px;
  line-height: 20px;
  white-space: pre;
  overflow: auto;
}

.code-editor-textarea:focus {
  outline: none;
}

.code-editor-textarea::placeholder {
  color: var(--td-text-muted);
}

.editor-status-bar {
  width: 100%;
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--td-text-secondary);
}

.import-preview-bar {
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.import-preview-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--td-text-primary);
}

.import-preview-note {
  font-size: 12px;
  color: var(--td-text-secondary);
}

/* Inspector drawer */
.drawer-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.readonly-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-radius: var(--radius-sm);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border);
  font-size: 12.5px;
  color: var(--td-text-secondary);
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.meta-cell {
  padding: 9px 11px;
  border-radius: var(--radius-sm);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.meta-label {
  font-size: 12px;
  color: var(--td-text-secondary);
}

.meta-val {
  font-size: 13px;
  font-weight: 600;
  color: var(--td-text-primary);
}

.hash-full {
  word-break: break-all;
}

@media (max-width: 960px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .version-panel {
    display: none;
  }

  .mobile-version-wrap {
    display: inline-flex;
  }
}
</style>
