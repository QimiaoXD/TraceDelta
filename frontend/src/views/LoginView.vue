<script setup lang="ts">
import { nextTick, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import TdIcon from '../components/TdIcon.vue'
import ThemeSwitcher from '../components/ThemeSwitcher.vue'

const router = useRouter()
const auth = useAuthStore()
const devHint = import.meta.env.DEV ? '本地开发模式：使用本地环境配置的管理员凭据登录' : ''

const form = reactive({
  username: '',
  password: '',
})
const loading = ref(false)
const errorText = ref('')
const usernameRef = ref<HTMLInputElement | null>(null)
const passwordRef = ref<HTMLInputElement | null>(null)

function mapLoginError(err: unknown): string {
  const status = (err as { response?: { status?: number } })?.response?.status
  if (status === 401 || status === 403) {
    return '用户名或密码不正确，请检查后重试。'
  }
  if (status && status >= 500) {
    return '服务端暂时不可用，请稍后重试。'
  }
  const msg = err instanceof Error ? err.message : ''
  if (msg.toLowerCase().includes('invalid username or password')) {
    return '用户名或密码不正确，请检查后重试。'
  }
  if (msg.toLowerCase().includes('network') || msg.toLowerCase().includes('timeout')) {
    return '无法连接到服务端，请确认本地后端已启动。'
  }
  return '登录未成功，请检查凭据或网络连接后重试。'
}

async function handleSubmit() {
  if (loading.value) return
  errorText.value = ''

  if (!form.username.trim()) {
    errorText.value = '请输入用户名。'
    await nextTick()
    usernameRef.value?.focus()
    return
  }
  if (!form.password) {
    errorText.value = '请输入密码。'
    await nextTick()
    passwordRef.value?.focus()
    return
  }

  loading.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    router.push('/datasets')
  } catch (err) {
    errorText.value = mapLoginError(err)
    await nextTick()
    passwordRef.value?.focus()
    passwordRef.value?.select()
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-theme">
      <ThemeSwitcher :show-label="true" />
    </div>

    <main class="login-container">
      <div class="login-brand-block">
        <div class="login-brand">
          <span class="login-mark" aria-hidden="true">
            <TdIcon name="logo" :size="18" />
          </span>
          <span class="login-name">TraceDelta</span>
        </div>
        <p class="login-positioning">数据集版本与模型渠道验证工作台</p>
      </div>

      <form class="login-card" novalidate @submit.prevent="handleSubmit">
        <h1 class="login-title">登录控制台</h1>

        <div
          v-if="errorText"
          id="login-error"
          class="form-alert login-alert"
          role="alert"
          aria-live="assertive"
        >
          <TdIcon name="alert-circle" :size="15" />
          <span>{{ errorText }}</span>
        </div>

        <div class="field">
          <label class="field-label" for="login-user">用户名</label>
          <input
            id="login-user"
            ref="usernameRef"
            v-model="form.username"
            type="text"
            class="field-input"
            placeholder="请输入操作员用户名"
            autocomplete="username"
            required
            :aria-invalid="Boolean(errorText && !form.username.trim())"
            :aria-describedby="errorText ? 'login-error' : undefined"
          />
        </div>

        <div class="field">
          <label class="field-label" for="login-pass">密码</label>
          <input
            id="login-pass"
            ref="passwordRef"
            v-model="form.password"
            type="password"
            class="field-input"
            placeholder="请输入登录密码"
            autocomplete="current-password"
            required
            :aria-invalid="Boolean(errorText)"
            :aria-describedby="errorText ? 'login-error' : undefined"
          />
        </div>

        <button
          type="submit"
          class="btn btn--primary login-submit"
          :disabled="loading"
          :aria-busy="loading"
        >
          {{ loading ? '验证中…' : '登录' }}
        </button>
      </form>

      <p v-if="devHint" class="login-dev-hint">{{ devHint }}</p>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 64px 20px 40px;
  background-color: var(--td-bg-canvas);
}

.login-theme {
  position: absolute;
  top: 16px;
  right: 20px;
}

.login-container {
  width: 100%;
  max-width: 372px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
}

.login-brand-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  text-align: center;
}

.login-brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
}

.login-mark {
  width: 30px;
  height: 30px;
  border-radius: var(--radius-md);
  background-color: var(--td-primary-bg);
  color: var(--td-primary-fg);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.login-name {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--td-text-primary);
}

.login-positioning {
  margin: 0;
  font-size: 13px;
  color: var(--td-text-secondary);
}

.login-card {
  width: 100%;
  padding: 24px;
  border-radius: var(--radius-md);
  background-color: var(--td-bg-surface);
  border: 1px solid var(--td-border);
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.login-title {
  margin: 0 0 2px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--td-text-primary);
}

.login-alert {
  margin-bottom: 0;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--td-text-primary);
}

.field-input {
  width: 100%;
  height: var(--ctrl-h);
  padding: 0 11px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--td-border-strong);
  background-color: var(--td-bg-surface);
  color: var(--td-text-primary);
  font-family: inherit;
  font-size: 14px;
  transition: border-color var(--dur-fast) var(--ease);
}

.field-input::placeholder {
  color: var(--td-text-muted);
}

.field-input:hover {
  border-color: var(--td-text-secondary);
}

.field-input:focus {
  outline: 2px solid var(--td-focus);
  outline-offset: 1px;
  border-color: var(--td-focus);
}

.login-submit {
  width: 100%;
  margin-top: 4px;
}

.login-dev-hint {
  margin: 0;
  font-size: 12px;
  color: var(--td-text-secondary);
  text-align: center;
}
</style>
