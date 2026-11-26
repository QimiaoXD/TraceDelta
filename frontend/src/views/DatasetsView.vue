<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createDatasetApi, deleteDatasetApi, type DatasetItem } from '../api/dataset'
import { useConsoleDataStore } from '../stores/consoleData'
import TdIcon from '../components/TdIcon.vue'
import { formatDateTime } from '../utils/format'

const store = useConsoleDataStore()

const datasets = computed(() => store.datasets)
const keyword = ref('')
const loadError = ref('')
const liveAnnouncement = ref('')

const showSlowHint = ref(false)
let slowTimer: number | null = null

const dialogVisible = ref(false)
const submitting = ref(false)
const formError = ref('')
const nameInputRef = ref<{ focus?: () => void } | null>(null)
const formAlertRef = ref<HTMLElement | null>(null)

const form = reactive({
  name: '',
  description: '',
})

const filteredDatasets = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return datasets.value
  return datasets.value.filter(
    (item) =>
      item.name.toLowerCase().includes(q) ||
      (item.description || '').toLowerCase().includes(q) ||
      String(item.id).includes(q),
  )
})

function getVersionSummary(item: DatasetItem) {
  const cached = store.getCachedVersions(item.id)
  if (cached === undefined) {
    return {
      versionLabel: store.isVersionFetchFailed(item.id) ? '读取失败' : '—',
      caseCountLabel: '—',
      updatedAt: item.createdAt,
      hasVersion: false,
      isUnimported: false,
    }
  }
  if (cached.length === 0) {
    return {
      versionLabel: '未导入',
      caseCountLabel: '0 条',
      updatedAt: item.createdAt,
      hasVersion: false,
      isUnimported: true,
    }
  }
  const latest = cached.reduce((acc, cur) => (cur.versionNo > acc.versionNo ? cur : acc), cached[0])
  return {
    versionLabel: `v${latest.versionNo}`,
    caseCountLabel: `${latest.caseCount} 条`,
    updatedAt: latest.createdAt || item.createdAt,
    hasVersion: true,
    isUnimported: false,
  }
}

async function refresh() {
  loadError.value = ''
  if (!store.datasetsLoaded) {
    slowTimer = window.setTimeout(() => {
      showSlowHint.value = true
    }, 180)
  }
  try {
    const list = await store.fetchDatasets()
    store.prefetchVersionSummaries(list.map((d) => d.id))
  } catch (err) {
    loadError.value = err instanceof Error ? err.message : '加载数据集列表失败'
  } finally {
    if (slowTimer !== null) {
      window.clearTimeout(slowTimer)
      slowTimer = null
    }
    showSlowHint.value = false
  }
}

function openCreateDialog() {
  formError.value = ''
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}

function onDialogOpened() {
  nextTick(() => {
    nameInputRef.value?.focus?.()
  })
}

async function handleDialogBeforeClose(done: () => void) {
  if (submitting.value) return
  if (form.name.trim() || form.description.trim()) {
    try {
      await ElMessageBox.confirm('当前有未保存的输入内容，确定关闭吗？', '放弃新建', {
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
  if (!form.name.trim()) {
    formError.value = '请输入数据集名称'
    await nextTick()
    nameInputRef.value?.focus?.()
    return
  }
  submitting.value = true
  try {
    const created = await createDatasetApi(form.name.trim(), form.description.trim())
    ElMessage.success(`已创建数据集「${created.name}」`)
    dialogVisible.value = false
    const list = await store.fetchDatasets()
    store.prefetchVersionSummaries(list.map((d) => d.id))
  } catch (err) {
    formError.value = err instanceof Error ? err.message : '创建数据集失败，请重试'
    await nextTick()
    formAlertRef.value?.focus()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(item: DatasetItem) {
  const summary = getVersionSummary(item)
  const detail = summary.hasVersion
    ? `其下所有不可变版本（当前最新 ${summary.versionLabel}）与用例将一并清除，此操作不可撤销。`
    : '此操作不可撤销。'
  try {
    await ElMessageBox.confirm(`确认删除数据集「${item.name}」？${detail}`, '删除数据集', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await deleteDatasetApi(item.id)
  store.removeDatasetCache(item.id)
  ElMessage.success(`已删除数据集「${item.name}」`)
  liveAnnouncement.value = `已删除数据集 ${item.name}`
  await store.fetchDatasets()
}

function handleRowMenu(command: string, item: DatasetItem) {
  if (command === 'delete') {
    handleDelete(item)
  }
}

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
      <h1 class="view-title">数据集</h1>
      <button type="button" class="btn btn--primary" @click="openCreateDialog">
        <TdIcon name="plus" :size="14" />
        <span>新建数据集</span>
      </button>
    </header>

    <div class="view-toolbar">
      <div class="view-toolbar-group">
        <div class="search-box">
          <TdIcon name="search" :size="14" class="search-box-icon" />
          <input
            v-model="keyword"
            type="search"
            class="search-box-input"
            placeholder="按名称、说明或 ID 搜索…"
            aria-label="按名称、说明或 ID 搜索数据集"
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

      <span v-if="store.datasetsLoaded" class="view-count mono" aria-live="polite">
        {{ filteredDatasets.length }}<template v-if="keyword.trim()"> / {{ datasets.length }}</template> 个数据集
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

    <div v-if="!store.datasetsLoaded && !loadError">
      <p v-if="showSlowHint" class="slow-loading" aria-live="polite">正在加载数据集列表…</p>
    </div>

    <section v-else-if="store.datasetsLoaded && datasets.length === 0" class="empty-panel">
      <div class="empty-panel-icon" aria-hidden="true">
        <TdIcon name="database" :size="18" />
      </div>
      <h2>尚无数据集</h2>
      <p>新建数据集后，可导入 JSONL、JSON 或 CSV 生成不可变版本快照。</p>
      <button type="button" class="btn btn--primary" @click="openCreateDialog">
        <TdIcon name="plus" :size="14" />
        <span>新建数据集</span>
      </button>
    </section>

    <section v-else-if="store.datasetsLoaded && filteredDatasets.length === 0" class="empty-panel">
      <h2>无匹配数据集</h2>
      <p>当前搜索词「{{ keyword }}」未匹配到任何名称、说明或 ID。</p>
      <button type="button" class="btn btn--secondary" @click="keyword = ''">清除搜索条件</button>
    </section>

    <div v-else-if="store.datasetsLoaded" class="data-table-wrap">
      <table class="data-table" aria-label="数据集列表">
        <thead>
          <tr>
            <th scope="col">名称与说明</th>
            <th scope="col">最新版本</th>
            <th scope="col" class="col-num">用例数</th>
            <th scope="col">更新时间</th>
            <th scope="col" class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in filteredDatasets" :key="item.id">
            <td class="dataset-name-cell">
              <div class="dataset-name-line">
                <router-link :to="`/datasets/${item.id}`" class="table-row-link" :title="item.name">
                  <span class="table-row-link-text">{{ item.name }}</span>
                </router-link>
                <span class="micro-badge mono">#{{ item.id }}</span>
              </div>
              <div v-if="item.description" class="table-subtext" :title="item.description">
                {{ item.description }}
              </div>
            </td>
            <td class="cell-nowrap">
              <span
                class="micro-badge"
                :class="{ mono: getVersionSummary(item).hasVersion }"
              >
                {{ getVersionSummary(item).versionLabel }}
              </span>
            </td>
            <td class="col-num mono">
              {{ getVersionSummary(item).caseCountLabel }}
            </td>
            <td class="cell-nowrap mono cell-secondary">
              {{ formatDateTime(getVersionSummary(item).updatedAt) }}
            </td>
            <td class="col-actions">
              <div class="table-actions">
                <router-link :to="`/datasets/${item.id}`" class="btn btn--secondary btn--sm">
                  <span>进入版本</span>
                  <TdIcon name="chevron-right" :size="13" />
                </router-link>

                <el-dropdown
                  trigger="click"
                  placement="bottom-end"
                  @command="(cmd: string) => handleRowMenu(cmd, item)"
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
                      <el-dropdown-item command="delete" class="menu-danger">
                        <TdIcon name="trash" :size="13" />
                        <span>删除数据集</span>
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
      title="新建数据集"
      width="460px"
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

      <el-form label-position="top" @submit.prevent="handleCreate">
        <el-form-item label="数据集名称" required>
          <el-input
            ref="nameInputRef"
            v-model="form.name"
            placeholder="例如 rag-qa-benchmark"
            maxlength="128"
          />
        </el-form-item>
        <el-form-item label="说明（可选）">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="500"
            placeholder="描述题库用途或评测范围"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <button
            type="button"
            class="btn btn--secondary btn--sm"
            :disabled="submitting"
            @click="requestCloseDialog"
          >
            取消
          </button>
          <button
            type="button"
            class="btn btn--primary btn--sm"
            :disabled="submitting"
            :aria-busy="submitting"
            @click="handleCreate"
          >
            {{ submitting ? '创建中…' : '创建' }}
          </button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dataset-name-cell {
  min-width: 220px;
  max-width: 460px;
}

.dataset-name-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.cell-nowrap {
  white-space: nowrap;
}

.cell-secondary {
  color: var(--td-text-secondary);
  font-size: 13px;
}

.table-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
</style>
