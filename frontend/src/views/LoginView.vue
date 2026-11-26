<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import TdIcon from '../components/TdIcon.vue'
import ThemeSwitcher from '../components/ThemeSwitcher.vue'

const router = useRouter()
const auth = useAuthStore()

const form = reactive({
  username: 'admin',
  password: '',
})
const loading = ref(false)
const formError = ref('')

const pipelineSteps = [
  { tag: 'SNAPSHOT', title: '不可变数据集版本', detail: '规范化 JSON 生成 SHA-256 case_hash，保证跨版本公平对齐' },
  { tag: 'RUNNER', title: '并发调度与故障恢复', detail: '虚拟线程 + 三级并发控制 + EvalAttempt 幂等落库' },
  { tag: 'TRACE', title: '执行链路与五维回归', detail: 'OTel 语义 Span 记录与功能/质量/性能/成本/可靠性对比' },
]

async function handleSubmit() {
  formError.value = ''
  if (!form.username.trim() || !form.password.trim()) {
    formError.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    ElMessage.success('登录成功')
    router.push('/datasets')
  } catch (err) {
    formError.value = err instanceof Error ? err.message : '登录失败，请检查账号和密码'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-shell">
    <div class="login-theme-bar">
      <ThemeSwitcher :show-label="true" />
    </div>

    <!-- Left engineering workbench panel -->
    <section class="login-showcase" aria-label="平台概览">
      <div class="showcase-inner">
        <div class="showcase-brand">
          <span class="brand-mark">
            <TdIcon name="logo" :size="20" />
          </span>
          <span class="brand-title">TraceDelta</span>
        </div>

        <h1 class="showcase-heading">
          让大模型迭代像单元测试一样<br />可复现、可追溯
        </h1>
        <p class="showcase-lead">
          面向 Java 企业级 AI 应用的轻量化自动化质量保障控制台，用不可变题库、确定性 Mock 与五维回归替代凭感觉调优。
        </p>

        <div class="pipeline-list">
          <div v-for="step in pipelineSteps" :key="step.tag" class="pipeline-row">
            <span class="pipeline-tag td-mono">{{ step.tag }}</span>
            <div class="pipeline-copy">
              <div class="pipeline-title">{{ step.title }}</div>
              <div class="pipeline-detail">{{ step.detail }}</div>
            </div>
          </div>
        </div>

        <div class="snapshot-preview">
          <div class="preview-header">
            <span class="preview-file td-mono">dataset_case · v2 snapshot</span>
            <span class="td-pill td-pill--success">
              <span class="td-pill-dot" />
              SHA-256 Verified
            </span>
          </div>
          <pre class="preview-code td-mono"><code>{
  "case_key": "refund-edge-04",
  "input": { "question": "返回合法 JSON 格式的退款状态" },
  "assertions": [{ "type": "json_valid" }],
  "case_hash": "9f8c2a1e4d07..."
}</code></pre>
        </div>
      </div>
    </section>

    <!-- Right sign-in panel -->
    <section class="login-panel" aria-label="管理员登录">
      <div class="panel-inner">
        <div class="mobile-brand">
          <span class="brand-mark">
            <TdIcon name="logo" :size="18" />
          </span>
          <span class="brand-title">TraceDelta</span>
        </div>

        <div class="panel-head">
          <h2>登录控制台</h2>
          <p>使用本地初始化的管理员凭据进入评测工作台。</p>
        </div>

        <div v-if="formError" class="login-alert" role="alert">
          {{ formError }}
        </div>

        <el-form label-position="top" class="login-form" @submit.prevent="handleSubmit">
          <el-form-item label="用户名">
            <el-input
              v-model="form.username"
              size="large"
              placeholder="admin"
              autocomplete="username"
            />
          </el-form-item>

          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              size="large"
              type="password"
              show-password
              placeholder="请输入管理员密码"
              autocomplete="current-password"
              @keyup.enter="handleSubmit"
            />
          </el-form-item>

          <el-button
            type="primary"
            size="large"
            class="submit-btn"
            :loading="loading"
            @click="handleSubmit"
          >
            {{ loading ? '正在验证...' : '进入工作台' }}
          </el-button>
        </el-form>

        <div class="panel-footnote">
          <TdIcon name="lock" :size="13" />
          <span>初始密码取自本地 <code class="td-mono">.env</code> 中的 <code class="td-mono">TRACEDELTA_ADMIN_PASSWORD</code></span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-shell {
  position: relative;
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1.12fr) minmax(0, 0.88fr);
  background-color: var(--td-bg-canvas);
}

.login-theme-bar {
  position: absolute;
  top: 20px;
  right: 24px;
  z-index: 10;
}

.login-showcase {
  background-color: var(--td-bg-rail);
  border-right: 1px solid var(--td-border-subtle);
  background-image: radial-gradient(var(--td-border-default) 1px, transparent 1px);
  background-size: 24px 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 40px;
}

.showcase-inner {
  width: 100%;
  max-width: 520px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.showcase-brand,
.mobile-brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.mobile-brand {
  display: none;
  margin-bottom: 16px;
}

.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: var(--td-radius-sm);
  background-color: var(--td-accent);
  color: #ffffff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

html.dark .brand-mark {
  background-color: #2563eb;
}

.brand-title {
  font-size: var(--td-text-lg);
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--td-text-primary);
}

.showcase-heading {
  margin: 0;
  font-size: 1.75rem;
  line-height: 1.3;
  font-weight: 600;
  letter-spacing: -0.025em;
  text-wrap: balance;
  color: var(--td-text-primary);
}

.showcase-lead {
  margin: -10px 0 0;
  font-size: var(--td-text-base);
  line-height: 1.65;
  color: var(--td-text-secondary);
}

.pipeline-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.pipeline-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 14px;
  border-radius: var(--td-radius-md);
  background-color: var(--td-bg-surface);
  border: 1px solid var(--td-border-subtle);
}

.pipeline-tag {
  font-size: 11px;
  font-weight: 600;
  color: var(--td-accent);
  background-color: var(--td-accent-soft);
  border: 1px solid var(--td-accent-border);
  border-radius: var(--td-radius-xs);
  padding: 2px 7px;
  min-width: 78px;
  text-align: center;
  margin-top: 2px;
}

.pipeline-title {
  font-size: var(--td-text-sm);
  font-weight: 600;
  color: var(--td-text-primary);
}

.pipeline-detail {
  margin-top: 2px;
  font-size: var(--td-text-xs);
  color: var(--td-text-secondary);
}

.snapshot-preview {
  border-radius: var(--td-radius-md);
  border: 1px solid var(--td-border-default);
  background-color: var(--td-bg-code);
  overflow: hidden;
}

.preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background-color: var(--td-bg-code-header);
  border-bottom: 1px solid var(--td-border-subtle);
}

.preview-file {
  font-size: var(--td-text-xs);
  color: var(--td-text-secondary);
}

.preview-code {
  margin: 0;
  padding: 12px 14px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--td-text-primary);
  overflow-x: auto;
}

.login-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 64px 32px 32px;
}

.panel-inner {
  width: 100%;
  max-width: 368px;
}

.panel-head h2 {
  margin: 0;
  font-size: 1.375rem;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--td-text-primary);
}

.panel-head p {
  margin: 6px 0 0;
  font-size: var(--td-text-sm);
  color: var(--td-text-secondary);
}

.login-alert {
  margin-top: 16px;
  padding: 10px 12px;
  border-radius: var(--td-radius-sm);
  background-color: var(--td-danger-soft);
  border: 1px solid var(--td-danger-border);
  color: var(--td-danger);
  font-size: var(--td-text-sm);
}

.login-form {
  margin-top: 24px;
}

.submit-btn {
  width: 100%;
  margin-top: 8px;
  height: 40px;
}

.panel-footnote {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--td-border-subtle);
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: var(--td-text-xs);
  color: var(--td-text-muted);
  line-height: 1.5;
}

.panel-footnote code {
  padding: 1px 4px;
  border-radius: var(--td-radius-xs);
  background-color: var(--td-bg-subtle);
  color: var(--td-text-secondary);
}

@media (max-width: 960px) {
  .login-shell {
    grid-template-columns: 1fr;
  }

  .login-showcase {
    display: none;
  }

  .mobile-brand {
    display: inline-flex;
  }
}
</style>
