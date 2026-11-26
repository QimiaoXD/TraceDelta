<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createDatasetApi, listDatasetsApi, type DatasetItem } from '../api/dataset'
import TdIcon from '../components/TdIcon.vue'
import { formatDateTime } from '../utils/format'

const router = useRouter()
const datasets = ref<DatasetItem[]>([])
const loading = ref(false)
const keyword = ref('')
const dialogVisible = ref(false)
const submitting = ref(false)

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

async function loadDatasets() {
  loading.value = true
  try {
    datasets.value = await listDatasetsApi()
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}

async function handleCreate() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入数据集名称')
    return
  }
  submitting.value = true
  try {
    const created = await createDatasetApi(form.name.trim(), form.description.trim())
    ElMessage.success(`数据集「${created.name}」已创建`)
    dialogVisible.value = false
    await loadDatasets()
  } finally {
    submitting.value = false
  }
}

function openDetail(id: number) {
  router.push(`/datasets/${id}`)
}

onMounted(loadDatasets)
</script>

<template>
  <div class="td-page">
    <div class="td-page-header">
      <div>
        <h1 class="td-page-title">评测数据集</h1>
        <p class="td-page-desc">
          每个数据集采用追加式不可变版本管理，用例导入时自动计算规范化 SHA-256 哈希，确保跨版本回归对比公平可追溯。
        </p>
      </div>
      <el-button type="primary" @click="openCreateDialog">
        <TdIcon name="plus" :size="15" style="margin-right: 6px" />
        新建数据集
      </el-button>
    </div>

    <section class="td-surface">
      <div class="td-toolbar">
        <div class="td-toolbar-group search-group">
          <el-input
            v-model="keyword"
            placeholder="搜索数据集名称、描述或 ID..."
            clearable
            class="search-input"
          >
            <template #prefix>
              <TdIcon name="search" :size="14" />
            </template>
          </el-input>
          <el-button :loading="loading" @click="loadDatasets">
            <TdIcon name="refresh" :size="14" style="margin-right: 5px" />
            刷新
          </el-button>
        </div>

        <div class="td-toolbar-group">
          <span class="count-meta td-num">
            共 <strong>{{ filteredDatasets.length }}</strong>
            <template v-if="keyword.trim()"> / {{ datasets.length }}</template>
            个数据集
          </span>
        </div>
      </div>

      <!-- Loading skeleton -->
      <div v-if="loading && datasets.length === 0" class="skeleton-wrap">
        <el-skeleton :rows="5" animated />
      </div>

      <!-- First-run empty state -->
      <div v-else-if="datasets.length === 0" class="empty-state">
        <div class="empty-icon">
          <TdIcon name="database" :size="22" />
        </div>
        <h3>还没有评测数据集</h3>
        <p>
          先创建一个数据集，随后导入 JSONL / JSON / CSV 题目即可生成首个不可变版本快照（v1）。
        </p>
        <el-button type="primary" @click="openCreateDialog">
          <TdIcon name="plus" :size="15" style="margin-right: 6px" />
          新建第一个数据集
        </el-button>
      </div>

      <!-- Filtered empty state -->
      <div v-else-if="filteredDatasets.length === 0" class="empty-state">
        <h3>未找到匹配「{{ keyword }}」的数据集</h3>
        <p>请调整搜索关键字，或清空筛选查看全部数据集。</p>
        <el-button @click="keyword = ''">清空搜索</el-button>
      </div>

      <!-- Data table -->
      <el-table
        v-else
        :data="filteredDatasets"
        class="clickable-table"
        @row-click="(row: DatasetItem) => openDetail(row.id)"
      >
        <el-table-column label="ID" width="84">
          <template #default="{ row }">
            <span class="id-chip td-mono">#{{ row.id }}</span>
          </template>
        </el-table-column>

        <el-table-column label="数据集名称" min-width="220">
          <template #default="{ row }">
            <div class="name-cell">
              <span class="dataset-name">{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="说明" min-width="260">
          <template #default="{ row }">
            <span class="desc-text">{{ row.description || '暂无说明' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="176">
          <template #default="{ row }">
            <span class="time-text td-mono">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="144" align="right" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row.id)">
              版本与用例
              <TdIcon name="chevron-right" :size="14" style="margin-left: 2px" />
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" title="新建数据集" width="460px">
      <el-form label-position="top" @submit.prevent="handleCreate">
        <el-form-item label="数据集名称" required>
          <el-input
            v-model="form.name"
            placeholder="例如：rag-qa-benchmark"
            maxlength="128"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="描述（可选）">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="说明评测目标、题型或语料来源"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">创建数据集</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.search-group {
  flex: 1;
  min-width: 220px;
}

.search-input {
  max-width: 320px;
}

.count-meta {
  font-size: var(--td-text-xs);
  color: var(--td-text-muted);
}

.count-meta strong {
  color: var(--td-text-primary);
  font-weight: 600;
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
  max-width: 46ch;
}

.clickable-table :deep(.el-table__row) {
  cursor: pointer;
}

.id-chip {
  font-size: var(--td-text-xs);
  color: var(--td-text-muted);
}

.dataset-name {
  font-weight: 600;
  color: var(--td-text-primary);
}

.desc-text {
  color: var(--td-text-secondary);
  font-size: var(--td-text-sm);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.time-text {
  font-size: var(--td-text-xs);
  color: var(--td-text-muted);
}
</style>
