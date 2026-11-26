<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createDatasetApi, listDatasetsApi, type DatasetItem } from '../api/dataset'

const router = useRouter()
const datasets = ref<DatasetItem[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)

const form = reactive({
  name: '',
  description: '',
})

async function loadDatasets() {
  loading.value = true
  try {
    datasets.value = await listDatasetsApi()
  } finally {
    loading.value = false
  }
}

async function handleCreate() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入数据集名称')
    return
  }
  submitting.value = true
  try {
    await createDatasetApi(form.name.trim(), form.description.trim())
    ElMessage.success('创建成功')
    dialogVisible.value = false
    form.name = ''
    form.description = ''
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
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div>
          <h3 style="margin: 0">评测数据集</h3>
          <span class="sub">每个数据集采用不可变版本管理，便于跨版本公平回归对比</span>
        </div>
        <el-button type="primary" @click="dialogVisible = true">新建数据集</el-button>
      </div>
    </template>

    <el-table :data="datasets" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="description" label="描述" min-width="240" />
      <el-table-column prop="createdAt" label="创建时间" width="200" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row.id)">查看版本与用例</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="dialogVisible" title="新建数据集" width="460px">
    <el-form label-position="top">
      <el-form-item label="数据集名称" required>
        <el-input v-model="form.name" placeholder="例如：rag-qa-benchmark" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="3" placeholder="说明评测目标或语料来源" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleCreate">确定</el-button>
    </template>
  </el-dialog>
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
</style>
