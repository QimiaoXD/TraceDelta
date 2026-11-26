<script setup lang="ts">
import { useThemeStore, type ThemeMode } from '../stores/theme'
import TdIcon, { type IconName } from './TdIcon.vue'

withDefaults(
  defineProps<{
    showLabel?: boolean
  }>(),
  {
    showLabel: false,
  },
)

const theme = useThemeStore()

const options: Array<{ value: ThemeMode; label: string; title: string; icon: IconName }> = [
  { value: 'light', label: '浅色', title: '浅色模式', icon: 'sun' },
  { value: 'system', label: '自动', title: '跟随系统主题', icon: 'monitor' },
  { value: 'dark', label: '深色', title: '深色模式', icon: 'moon' },
]

function onKeydown(event: KeyboardEvent, index: number) {
  let nextIdx = -1
  if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
    nextIdx = (index + 1) % options.length
  } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
    nextIdx = (index - 1 + options.length) % options.length
  }
  if (nextIdx >= 0) {
    event.preventDefault()
    theme.setMode(options[nextIdx].value)
    const parent = (event.currentTarget as HTMLElement)?.parentElement
    const buttons = parent?.querySelectorAll<HTMLButtonElement>('[role="radio"]')
    buttons?.[nextIdx]?.focus()
  }
}
</script>

<template>
  <div class="theme-switch" role="radiogroup" aria-label="外观主题">
    <button
      v-for="(opt, idx) in options"
      :key="opt.value"
      type="button"
      role="radio"
      class="theme-opt"
      :class="{ 'is-active': theme.mode === opt.value }"
      :tabindex="theme.mode === opt.value ? 0 : -1"
      :title="opt.title"
      :aria-label="opt.title"
      :aria-checked="theme.mode === opt.value"
      @click="theme.setMode(opt.value)"
      @keydown="onKeydown($event, idx)"
    >
      <TdIcon :name="opt.icon" :size="13" />
      <span v-if="showLabel">{{ opt.label }}</span>
    </button>
  </div>
</template>

<style scoped>
.theme-switch {
  display: inline-flex;
  align-items: center;
  height: 30px;
  padding: 2px;
  gap: 2px;
  border-radius: var(--radius-sm);
  background-color: var(--td-bg-subtle);
  border: 1px solid var(--td-border);
}

.theme-opt {
  appearance: none;
  border: 1px solid transparent;
  background: transparent;
  color: var(--td-text-secondary);
  height: 24px;
  padding: 0 8px;
  border-radius: var(--radius-xs);
  font-family: inherit;
  font-size: 12px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  white-space: nowrap;
  transition: background-color var(--dur-fast) var(--ease),
    color var(--dur-fast) var(--ease),
    border-color var(--dur-fast) var(--ease);
}

.theme-opt:hover {
  color: var(--td-text-primary);
}

.theme-opt.is-active {
  background-color: var(--td-bg-surface);
  color: var(--td-text-primary);
  border-color: var(--td-border-strong);
  box-shadow: var(--td-shadow-xs);
}
</style>
