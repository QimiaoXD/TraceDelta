<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import TdIcon, { type IconName } from '../components/TdIcon.vue'
import ThemeSwitcher from '../components/ThemeSwitcher.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const collapsed = ref(false)
const mobileOpen = ref(false)

interface NavItem {
  label: string
  to?: string
  icon: IconName
  badge?: string
  disabled?: boolean
}

const primaryNav: NavItem[] = [
  { label: '数据集与版本', to: '/datasets', icon: 'database' },
  { label: '模型渠道与调试', to: '/providers', icon: 'cpu' },
]

const roadmapNav: NavItem[] = [
  { label: '评测调度看板', icon: 'activity', badge: 'M2', disabled: true },
  { label: '回归对比报告', icon: 'git-compare', badge: 'M4', disabled: true },
  { label: '质量门禁', icon: 'shield-check', badge: 'M5', disabled: true },
]

const activePath = computed(() => {
  if (route.path.startsWith('/providers')) return '/providers'
  return '/datasets'
})

const breadcrumbs = computed(() => {
  if (route.name === 'dataset-detail') {
    return [
      { label: '数据集与版本', to: '/datasets' },
      { label: `数据集 #${route.params.id}` },
    ]
  }
  if (route.path.startsWith('/providers')) {
    return [{ label: '模型渠道与调试' }]
  }
  return [{ label: '数据集与版本' }]
})

const userInitial = computed(() => (auth.username || 'A').slice(0, 1).toUpperCase())

function navigate(to?: string) {
  if (!to) return
  mobileOpen.value = false
  router.push(to)
}

function handleLogout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="shell-root" :class="{ 'is-collapsed': collapsed }">
    <!-- Mobile backdrop -->
    <div v-if="mobileOpen" class="mobile-backdrop" @click="mobileOpen = false" />

    <!-- Sidebar -->
    <aside class="shell-aside" :class="{ 'is-mobile-open': mobileOpen }">
      <div class="aside-brand">
        <button type="button" class="brand-link" @click="navigate('/datasets')">
          <span class="brand-mark">
            <TdIcon name="logo" :size="18" />
          </span>
          <span v-if="!collapsed" class="brand-text">
            <span class="brand-name">TraceDelta</span>
            <span class="brand-ver td-mono">v0.1</span>
          </span>
        </button>
        <button
          type="button"
          class="mobile-close-btn"
          aria-label="关闭导航"
          @click="mobileOpen = false"
        >
          <TdIcon name="x" :size="16" />
        </button>
      </div>

      <nav class="aside-nav" aria-label="主导航">
        <div class="nav-section">
          <div v-if="!collapsed" class="nav-group-label">工作台</div>
          <button
            v-for="item in primaryNav"
            :key="item.label"
            type="button"
            class="nav-item"
            :class="{ 'is-active': activePath === item.to }"
            :title="collapsed ? item.label : undefined"
            @click="navigate(item.to)"
          >
            <TdIcon :name="item.icon" :size="16" />
            <span v-if="!collapsed" class="nav-item-label">{{ item.label }}</span>
          </button>
        </div>

        <div class="nav-section">
          <div v-if="!collapsed" class="nav-group-label">演进规划</div>
          <div
            v-for="item in roadmapNav"
            :key="item.label"
            class="nav-item is-disabled"
            :title="`${item.label}（计划于 ${item.badge} 阶段交付）`"
            aria-disabled="true"
          >
            <TdIcon :name="item.icon" :size="16" />
            <span v-if="!collapsed" class="nav-item-label">{{ item.label }}</span>
            <span v-if="!collapsed && item.badge" class="nav-badge td-mono">{{ item.badge }}</span>
          </div>
        </div>
      </nav>

      <div class="aside-footer">
        <div class="user-row" :title="auth.username || 'admin'">
          <span class="user-avatar td-mono">{{ userInitial }}</span>
          <div v-if="!collapsed" class="user-meta">
            <div class="user-name">{{ auth.username || 'admin' }}</div>
            <div class="user-role">本地操作员</div>
          </div>
          <button
            type="button"
            class="icon-action-btn"
            title="退出登录"
            aria-label="退出登录"
            @click="handleLogout"
          >
            <TdIcon name="logout" :size="15" />
          </button>
        </div>
      </div>
    </aside>

    <!-- Main area -->
    <div class="shell-body">
      <header class="shell-topbar">
        <div class="topbar-left">
          <button
            type="button"
            class="icon-action-btn desktop-collapse-btn"
            :title="collapsed ? '展开侧边栏' : '收起侧边栏'"
            :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'"
            @click="collapsed = !collapsed"
          >
            <TdIcon name="panel-left" :size="16" />
          </button>
          <button
            type="button"
            class="icon-action-btn mobile-menu-btn"
            aria-label="打开导航菜单"
            @click="mobileOpen = true"
          >
            <TdIcon name="menu" :size="17" />
          </button>

          <nav class="breadcrumbs" aria-label="面包屑导航">
            <template v-for="(crumb, idx) in breadcrumbs" :key="crumb.label">
              <TdIcon v-if="idx > 0" name="chevron-right" :size="14" class="crumb-sep" />
              <button
                v-if="crumb.to"
                type="button"
                class="crumb-link"
                @click="navigate(crumb.to)"
              >
                {{ crumb.label }}
              </button>
              <span v-else class="crumb-current">{{ crumb.label }}</span>
            </template>
          </nav>
        </div>

        <div class="topbar-right">
          <span class="td-pill td-pill--success env-pill">
            <span class="td-pill-dot" />
            Mock 就绪
          </span>
          <ThemeSwitcher :show-label="true" />
        </div>
      </header>

      <main class="shell-main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell-root {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background-color: var(--td-bg-canvas);
}

.shell-aside {
  width: 236px;
  flex-shrink: 0;
  background-color: var(--td-bg-rail);
  border-right: 1px solid var(--td-border-subtle);
  display: flex;
  flex-direction: column;
  transition: width var(--td-duration-base) var(--td-ease-out);
  z-index: 30;
}

.shell-root.is-collapsed .shell-aside {
  width: 64px;
}

.aside-brand {
  height: 52px;
  padding: 0 14px;
  border-bottom: 1px solid var(--td-border-subtle);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.brand-link {
  appearance: none;
  border: none;
  background: transparent;
  padding: 0;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  color: var(--td-text-primary);
  min-width: 0;
}

.brand-mark {
  width: 30px;
  height: 30px;
  border-radius: var(--td-radius-sm);
  background-color: var(--td-accent);
  color: #ffffff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

html.dark .brand-mark {
  background-color: #2563eb;
}

.brand-text {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
}

.brand-name {
  font-size: var(--td-text-md);
  font-weight: 600;
  letter-spacing: -0.02em;
}

.brand-ver {
  font-size: 11px;
  color: var(--td-text-muted);
  padding: 1px 5px;
  border-radius: var(--td-radius-xs);
  background-color: var(--td-bg-surface);
  border: 1px solid var(--td-border-subtle);
}

.aside-nav {
  flex: 1;
  overflow-y: auto;
  padding: 14px 10px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.nav-section {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.nav-group-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--td-text-muted);
  padding: 0 10px 6px;
  letter-spacing: 0.03em;
}

.nav-item {
  appearance: none;
  border: 1px solid transparent;
  background: transparent;
  color: var(--td-text-secondary);
  width: 100%;
  height: 36px;
  padding: 0 10px;
  border-radius: var(--td-radius-sm);
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: inherit;
  font-size: var(--td-text-sm);
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  transition: background-color var(--td-duration-fast) var(--td-ease-out),
    color var(--td-duration-fast) var(--td-ease-out),
    border-color var(--td-duration-fast) var(--td-ease-out);
}

.shell-root.is-collapsed .nav-item {
  justify-content: center;
  padding: 0;
}

.nav-item:hover:not(.is-disabled) {
  background-color: var(--td-bg-hover);
  color: var(--td-text-primary);
}

.nav-item.is-active {
  background-color: var(--td-bg-surface);
  border-color: var(--td-border-subtle);
  color: var(--td-accent);
  box-shadow: var(--td-shadow-sm);
}

.nav-item.is-disabled {
  opacity: 0.56;
  cursor: not-allowed;
}

.nav-item-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-badge {
  font-size: 11px;
  color: var(--td-text-muted);
  padding: 1px 5px;
  border-radius: var(--td-radius-xs);
  border: 1px solid var(--td-border-subtle);
  background-color: var(--td-bg-subtle);
}

.aside-footer {
  padding: 10px;
  border-top: 1px solid var(--td-border-subtle);
}

.user-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border-radius: var(--td-radius-sm);
}

.shell-root.is-collapsed .user-row {
  flex-direction: column;
  padding: 4px 0;
  gap: 8px;
}

.user-avatar {
  width: 28px;
  height: 28px;
  border-radius: var(--td-radius-sm);
  background-color: var(--td-accent-soft);
  border: 1px solid var(--td-accent-border);
  color: var(--td-accent);
  font-size: var(--td-text-xs);
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.user-meta {
  flex: 1;
  min-width: 0;
}

.user-name {
  font-size: var(--td-text-sm);
  font-weight: 600;
  color: var(--td-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-role {
  font-size: 11px;
  color: var(--td-text-muted);
}

.icon-action-btn {
  appearance: none;
  border: 1px solid transparent;
  background: transparent;
  color: var(--td-text-muted);
  width: 30px;
  height: 30px;
  border-radius: var(--td-radius-sm);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: background-color var(--td-duration-fast) var(--td-ease-out),
    color var(--td-duration-fast) var(--td-ease-out);
}

.icon-action-btn:hover {
  background-color: var(--td-bg-hover);
  color: var(--td-text-primary);
}

.shell-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.shell-topbar {
  height: 52px;
  flex-shrink: 0;
  padding: 0 24px;
  background-color: var(--td-bg-surface);
  border-bottom: 1px solid var(--td-border-subtle);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.topbar-left,
.topbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.breadcrumbs {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--td-text-sm);
  min-width: 0;
}

.crumb-sep {
  color: var(--td-text-muted);
}

.crumb-link {
  appearance: none;
  border: none;
  background: transparent;
  padding: 2px 4px;
  border-radius: var(--td-radius-xs);
  color: var(--td-text-secondary);
  font-family: inherit;
  font-size: var(--td-text-sm);
  cursor: pointer;
}

.crumb-link:hover {
  color: var(--td-text-primary);
  background-color: var(--td-bg-hover);
}

.crumb-current {
  font-weight: 600;
  color: var(--td-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.shell-main {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
}

.mobile-menu-btn,
.mobile-close-btn,
.mobile-backdrop {
  display: none;
}

@media (max-width: 860px) {
  .desktop-collapse-btn,
  .env-pill {
    display: none;
  }

  .mobile-menu-btn,
  .mobile-close-btn {
    display: inline-flex;
  }

  .mobile-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    background-color: rgba(11, 14, 20, 0.48);
    z-index: 25;
  }

  .shell-aside {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: 250px !important;
    transform: translateX(-100%);
    transition: transform var(--td-duration-base) var(--td-ease-out);
  }

  .shell-aside.is-mobile-open {
    transform: translateX(0);
    box-shadow: var(--td-shadow-overlay);
  }

  .shell-topbar {
    padding: 0 14px;
  }

  .shell-main {
    padding: 16px;
  }
}
</style>
