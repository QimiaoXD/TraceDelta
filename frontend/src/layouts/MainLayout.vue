<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useConsoleDataStore } from '../stores/consoleData'
import TdIcon, { type IconName } from '../components/TdIcon.vue'
import ThemeSwitcher from '../components/ThemeSwitcher.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const consoleData = useConsoleDataStore()

const mobileOpen = ref(false)
const menuTriggerRef = ref<HTMLButtonElement | null>(null)
const closeDrawerRef = ref<HTMLButtonElement | null>(null)

interface NavItem {
  label: string
  to: string
  icon: IconName
}

const navItems: NavItem[] = [
  { label: '数据集', to: '/datasets', icon: 'database' },
  { label: '模型渠道', to: '/providers', icon: 'cpu' },
  { label: '外部接口', to: '/http-targets', icon: 'globe' },
  { label: '评测任务', to: '/runs', icon: 'activity' },
]

const activePath = computed(() => {
  if (route.path.startsWith('/providers')) return '/providers'
  if (route.path.startsWith('/http-targets')) return '/http-targets'
  if (route.path.startsWith('/runs') || route.path.startsWith('/traces')) return '/runs'
  return '/datasets'
})

const nestedCrumb = computed(() => {
  if (route.name === 'dataset-detail') {
    const id = Number(route.params.id)
    const meta = consoleData.datasets.find((d) => d.id === id)
    return {
      parentLabel: '数据集',
      parentTo: '/datasets',
      current: meta ? meta.name : `#${route.params.id}`,
      currentIsMono: !meta,
    }
  }
  if (route.name === 'run-detail') {
    const id = Number(route.params.id)
    const name = consoleData.getCachedRunName(id)
    return {
      parentLabel: '评测任务',
      parentTo: '/runs',
      current: name || `#${route.params.id}`,
      currentIsMono: !name,
    }
  }
  if (route.name === 'trace-detail') {
    const tid = String(route.params.traceId || '')
    return {
      parentLabel: '评测任务',
      parentTo: '/runs',
      current: `Trace ${tid.slice(0, 12)}…`,
      currentIsMono: true,
    }
  }
  return null
})

const userInitial = computed(() => (auth.username || 'A').slice(0, 1).toUpperCase())

async function openMobileDrawer() {
  mobileOpen.value = true
  await nextTick()
  closeDrawerRef.value?.focus()
}

async function closeMobileDrawer(restoreFocus = false) {
  mobileOpen.value = false
  if (restoreFocus) {
    await nextTick()
    menuTriggerRef.value?.focus()
  }
}

function onGlobalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && mobileOpen.value) {
    event.preventDefault()
    closeMobileDrawer(true)
  }
}

watch(
  () => route.fullPath,
  () => {
    mobileOpen.value = false
  },
)

function handleLogout() {
  consoleData.resetAll()
  auth.logout()
  router.push('/login')
}

onMounted(() => {
  window.addEventListener('keydown', onGlobalKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
})
</script>

<template>
  <div class="shell">
    <a href="#main-content" class="skip-link">跳到主内容</a>

    <div
      v-if="mobileOpen"
      class="shell-scrim"
      aria-hidden="true"
      @click="closeMobileDrawer(true)"
    />

    <aside
      id="primary-sidebar"
      class="shell-sidebar"
      :class="{ 'is-open': mobileOpen }"
      aria-label="应用侧栏"
    >
      <div class="sidebar-top">
        <router-link to="/datasets" class="brand" @click="mobileOpen = false">
          <span class="brand-mark" aria-hidden="true">
            <TdIcon name="logo" :size="14" />
          </span>
          <span class="brand-name">TraceDelta</span>
        </router-link>

        <button
          ref="closeDrawerRef"
          type="button"
          class="btn btn--icon mobile-close"
          aria-label="关闭导航菜单"
          @click="closeMobileDrawer(true)"
        >
          <TdIcon name="x" :size="15" />
        </button>
      </div>

      <nav class="sidebar-nav" aria-label="主导航">
        <router-link
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="nav-link"
          :class="{ 'is-active': activePath === item.to }"
          :aria-current="activePath === item.to ? 'page' : undefined"
          @click="mobileOpen = false"
        >
          <TdIcon :name="item.icon" :size="15" class="nav-icon" />
          <span>{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="sidebar-foot">
        <div class="account-bar">
          <span class="account-avatar mono" aria-hidden="true">{{ userInitial }}</span>
          <span class="account-name" :title="auth.username || 'admin'">
            {{ auth.username || 'admin' }}
          </span>
          <button
            type="button"
            class="btn btn--icon"
            title="退出登录"
            aria-label="退出登录"
            @click="handleLogout"
          >
            <TdIcon name="logout" :size="14" />
          </button>
        </div>
      </div>
    </aside>

    <div class="shell-body">
      <header class="shell-topbar">
        <div class="topbar-left">
          <button
            ref="menuTriggerRef"
            type="button"
            class="btn btn--icon mobile-menu"
            aria-label="打开导航菜单"
            aria-controls="primary-sidebar"
            :aria-expanded="mobileOpen"
            @click="openMobileDrawer"
          >
            <TdIcon name="menu" :size="16" />
          </button>

          <nav v-if="nestedCrumb" class="crumb" aria-label="面包屑">
            <router-link :to="nestedCrumb.parentTo" class="crumb-link">
              {{ nestedCrumb.parentLabel }}
            </router-link>
            <span class="crumb-sep" aria-hidden="true">/</span>
            <span
              class="crumb-cur"
              :class="{ mono: nestedCrumb.currentIsMono }"
              :title="nestedCrumb.current"
            >
              {{ nestedCrumb.current }}
            </span>
          </nav>
        </div>

        <ThemeSwitcher :show-label="true" />
      </header>

      <main id="main-content" class="shell-main" tabindex="-1">
        <div class="shell-container">
          <router-view />
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.skip-link {
  position: fixed;
  top: -48px;
  left: 12px;
  z-index: 100;
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  background-color: var(--td-primary-bg);
  color: var(--td-primary-fg);
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
}

.skip-link:focus {
  top: 10px;
}

.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background-color: var(--td-bg-canvas);
}

.shell-sidebar {
  width: 228px;
  flex-shrink: 0;
  background-color: var(--td-bg-sidebar);
  border-right: 1px solid var(--td-border);
  display: flex;
  flex-direction: column;
  z-index: 30;
}

.sidebar-top {
  height: 48px;
  padding: 0 12px 0 14px;
  border-bottom: 1px solid var(--td-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--td-text-primary);
  border-radius: var(--radius-xs);
}

.brand-mark {
  width: 24px;
  height: 24px;
  border-radius: var(--radius-sm);
  background-color: var(--td-primary-bg);
  color: var(--td-primary-fg);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.brand-name {
  font-size: 14.5px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.sidebar-nav {
  flex: 1;
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.nav-link {
  position: relative;
  text-decoration: none;
  border: 1px solid transparent;
  background: transparent;
  color: var(--td-text-secondary);
  height: 36px;
  padding: 0 11px;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 13.5px;
  font-weight: 500;
  transition: background-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.nav-icon {
  color: var(--td-text-secondary);
  transition: color var(--dur-fast) var(--ease);
}

.nav-link:hover:not(.is-active) {
  background-color: var(--td-bg-nav-hover);
  color: var(--td-text-primary);
}

.nav-link:hover:not(.is-active) .nav-icon {
  color: var(--td-text-primary);
}

.nav-link.is-active {
  background-color: var(--td-bg-nav-active);
  border-color: var(--td-border-strong);
  color: var(--td-text-primary);
  box-shadow: var(--td-shadow-xs);
}

.nav-link.is-active::before {
  content: '';
  position: absolute;
  left: -1px;
  top: 7px;
  bottom: 7px;
  width: 3px;
  border-radius: 0 2px 2px 0;
  background-color: var(--td-text-primary);
}

.nav-link.is-active .nav-icon {
  color: var(--td-text-primary);
}

.sidebar-foot {
  padding: 10px;
  border-top: 1px solid var(--td-border);
}

.account-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 4px 2px 6px;
}

.account-avatar {
  width: 24px;
  height: 24px;
  border-radius: var(--radius-sm);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border-strong);
  color: var(--td-text-primary);
  font-size: 12px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.account-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--td-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shell-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.shell-topbar {
  height: 48px;
  flex-shrink: 0;
  padding: 0 24px;
  background-color: var(--td-bg-surface);
  border-bottom: 1px solid var(--td-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.crumb {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  min-width: 0;
}

.crumb-link {
  text-decoration: none;
  padding: 2px 6px;
  border-radius: var(--radius-xs);
  color: var(--td-text-secondary);
  white-space: nowrap;
}

.crumb-link:hover {
  background-color: var(--td-bg-subtle);
  color: var(--td-text-primary);
}

.crumb-sep {
  color: var(--td-text-secondary);
}

.crumb-cur {
  font-size: 13px;
  font-weight: 500;
  color: var(--td-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 240px;
}

.shell-main {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 24px 24px 48px;
}

.shell-main:focus {
  outline: none;
}

.shell-container {
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
}

.mobile-menu,
.mobile-close,
.shell-scrim {
  display: none;
}

@media (max-width: 840px) {
  .mobile-menu,
  .mobile-close {
    display: inline-flex;
  }

  .shell-scrim {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.52);
    z-index: 25;
  }

  .shell-sidebar {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: 240px;
    transform: translateX(-100%);
    transition: transform var(--dur-base) var(--ease);
  }

  .shell-sidebar.is-open {
    transform: translateX(0);
    box-shadow: var(--td-shadow-overlay);
  }

  .shell-topbar {
    padding: 0 12px;
  }

  .shell-main {
    padding: 18px 14px 36px;
  }
}
</style>
