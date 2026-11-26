<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  importDatasetVersionApi,
  listDatasetCasesApi,
  listDatasetVersionsApi,
  type DatasetCaseItem,
  type DatasetVersionItem,
} from '../api/dataset'

const route = useRoute()
const router = useRouter()
const datasetId = computed(() => Number(route.params.id))

const versions = ref<DatasetVersionItem[]>([])
const selectedVersion = ref<number | null>(null)
const cases = ref<DatasetCaseItem[]>([])
const loadingVersions = ref(false)
const loadingCases = ref(false)

const importVisible = ref(false)
const importing = ref(false)
const importForm = reactive({
  format: 'JSONL',
  note: '',
  content: '',
})

const SAMPLE_JSONL = `{"case_key":"case-01","input":{"question":"什么是 Java 21 虚拟线程？"},"reference_answer":"轻量级用户态线程，适合高并发 I/O 场景","assertions":[{"type":"contains","expected":"虚拟线程"}]}
{"case_key":"case-02","input":{"question":"返回合法 JSON 格式的退款状态"},"reference_answer":"{\\"status\\":\\"refunded\\"}","assertions":[{"type":"json_valid"}]}`

async function loadVersions() {
  loadingVersions.value = true
  try {
    versions.value = await listDatasetVersionsApi(datasetId.value)
    if (versions.value.length > 0) {
      const latest = versions.value[versions.value.length - 1].versionNo
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

function fillSample() {
  importForm.format = 'JSONL'
  importForm.note = '示例导入'
  importForm.content = SAMPLE_JSONL
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
    if (typeof parsed.question === 'string') {
      return parsed.question
    }
    return inputJson.slice(0, 80)
  } catch {
    return inputJson.slice(0, 80)
  }
}

onMounted(loadVersions)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="detail-header">
        <div class="left">
          <el-button link @click="router.push('/datasets')">← 返回数据集列表</el-button>
          <h3 style="margin: 0">数据集 #{{ datasetId }}</h3>
        </div>
        <div class="right">
          <el-select
            v-model="selectedVersion"
            placeholder="暂无版本"
            style="width: 220px"
            :loading="loadingVersions"
          >
            <el-option
              v-for="v in versions"
              :key="v.id"
              :label="`v${v.versionNo} (${v.caseCount} 条)${v.note ? ' - ' + v.note : ''}`"
              :value="v.versionNo"
            />
          </el-select>
          <el-button type="primary" @click="importVisible = true">导入新版本</el-button>
        </div>
      </div>
    </template>

    <el-empty v-if="!loadingVersions && versions.length === 0" description="暂无版本，请点击右上角导入新版本" />

    <el-table v-else :data="cases" v-loading="loadingCases" stripe>
      <el-table-column prop="caseKey" label="Case Key" width="150" />
      <el-table-column label="输入摘要" min-width="240">
        <template #default="{ row }">
          <span>{{ summarizeInput(row.inputJson) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="referenceAnswer" label="参考答案" min-width="200" show-overflow-tooltip />
      <el-table-column label="Case Hash" width="130">
        <template #default="{ row }">
          <el-tooltip :content="row.caseHash" placement="top">
            <el-tag size="small" type="info">{{ row.caseHash.slice(0, 8) }}</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="importVisible" title="导入新版本（不可变快照）" width="640px">
    <el-form label-position="top">
      <el-form-item label="格式">
        <div style="display: flex; gap: 12px; width: 100%; align-items: center">
          <el-radio-group v-model="importForm.format">
            <el-radio-button value="JSONL">JSONL</el-radio-button>
            <el-radio-button value="JSON">JSON 数组</el-radio-button>
            <el-radio-button value="CSV">CSV（question,reference_answer）</el-radio-button>
          </el-radio-group>
          <el-button link type="primary" @click="fillSample">填入 JSONL 示例</el-button>
        </div>
      </el-form-item>
      <el-form-item label="版本备注">
        <el-input v-model="importForm.note" placeholder="例如：新增退款边界用例" />
      </el-form-item>
      <el-form-item label="用例内容" required>
        <el-input
          v-model="importForm.content"
          type="textarea"
          :rows="9"
          placeholder="粘贴 JSON / JSONL / CSV 内容"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="importVisible = false">取消</el-button>
      <el-button type="primary" :loading="importing" @click="handleImport">导入并生成新版本</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.left,
.right {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
