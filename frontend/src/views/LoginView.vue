<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()

const form = reactive({
  username: 'admin',
  password: '',
})
const loading = ref(false)

async function handleSubmit() {
  if (!form.username.trim() || !form.password.trim()) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    ElMessage.success('登录成功')
    router.push('/datasets')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="hover">
      <div class="login-header">
        <div class="logo-badge">Δ</div>
        <h2>TraceDelta</h2>
        <p>面向大模型应用的自动化回归评测与链路诊断平台</p>
      </div>
      <el-form label-position="top" @submit.prevent="handleSubmit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="admin" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入管理员密码"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>
        <el-button
          type="primary"
          :loading="loading"
          style="width: 100%; margin-top: 8px"
          @click="handleSubmit"
        >
          登录控制台
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
}
.login-card {
  width: 400px;
  border-radius: 12px;
}
.login-header {
  text-align: center;
  margin-bottom: 24px;
}
.logo-badge {
  width: 44px;
  height: 44px;
  margin: 0 auto 10px;
  border-radius: 10px;
  background: linear-gradient(135deg, #0284c7, #38bdf8);
  color: #fff;
  font-size: 22px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-header h2 {
  margin: 0;
  color: #0f172a;
}
.login-header p {
  margin: 6px 0 0;
  font-size: 13px;
  color: #64748b;
}
</style>
