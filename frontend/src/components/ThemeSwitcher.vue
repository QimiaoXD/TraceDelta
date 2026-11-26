<script setup lang="ts">
import { useThemeStore, type ThemeMode } from '../stores/theme'
import TdIcon, { type IconName } from './TdIcon.vue'

withDefaults(
  defineProps<{
    compact?: boolean
    showLabel?: boolean
  }>(),
  {
    compact: false,
    showLabel: false,
  },
)

const theme = useThemeStore()

const options: Array<{ value: ThemeMode; label: string; title: string; icon: IconName }> = [
  { value: 'light', label: '浅色', title: '切换为浅色模式', icon: 'sun' },
  { value: 'system', label: '自动', title: '跟随设备系统主题', icon: 'monitor' },
  { value: 'dark', label: '深色', title: '切换为深色模式', icon: 'moon' },
]
</script>

<template>
  <div
    class="theme-switcher"
    :class="{ 'theme-switcher--compact': compact }"
    role="group"
    aria-label="外观主题切换"
  >
    <button
      v-for="opt in options"
      :key="opt.value"
      type="button"
      class="theme-btn"
      :class="{ 'is-active': theme.mode === opt.value }"
      :title="opt.title"
      :aria-label="opt.title"
      :aria-pressed="theme.mode === opt.value"
      @click="theme.setMode(opt.value)"
    >
      <TdIcon :name="opt.icon" :size="14" />
      <span v-if="showLabel" class="theme-btn-label">{{ opt.label }}</span>
    </button>
  </div>
</template>

<style scoped>
.theme-switcher {
  display: inline-flex;
  align-items: center;
  padding: 2px;
  gap: 2px;
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border-subtle);
  border-radius: var(--td-radius-sm);
}

.theme-btn {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--td-text-muted);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  height: 26px;
  padding: 0 8px;
  border-radius: var(--td-radius-xs);
  font-family: inherit;
  font-size: var(--td-text-xs);
  font-weight: 500;
  cursor: pointer;
  transition: background-color var(--td-duration-fast) var(--td-ease-out),
    color var(--td-duration-fast) var(--td-ease-out),
    box-shadow var(--td-duration-fast) var(--td-ease-out);
}

.theme-switcher--compact .theme-btn {
  padding: 0 7px;
}

.theme-btn:hover {
  color: var(--td-text-primary);
}

.theme-btn.is-active {
  background-color: var(--td-bg-surface);
  color: var(--td-text-primary);
  box-shadow: var(--td-shadow-sm);
}
</style>
