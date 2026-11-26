import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type ThemeMode = 'light' | 'system' | 'dark'
export type ResolvedTheme = 'light' | 'dark'

const THEME_STORAGE_KEY = 'tracedelta_theme_mode'

function readStoredMode(): ThemeMode {
  try {
    const raw = localStorage.getItem(THEME_STORAGE_KEY)
    if (raw === 'light' || raw === 'dark' || raw === 'system') {
      return raw
    }
  } catch {
    // ignore storage errors in restricted contexts
  }
  return 'system'
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(readStoredMode())
  const systemDark = ref<boolean>(
    typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-color-scheme: dark)').matches,
  )

  let mediaQuery: MediaQueryList | null = null
  let initialized = false

  const resolvedTheme = computed<ResolvedTheme>(() => {
    if (mode.value === 'system') {
      return systemDark.value ? 'dark' : 'light'
    }
    return mode.value
  })

  const isDark = computed(() => resolvedTheme.value === 'dark')

  function applyDomTheme(resolved: ResolvedTheme) {
    if (typeof document === 'undefined') return
    const root = document.documentElement
    root.dataset.theme = resolved
    root.classList.toggle('dark', resolved === 'dark')
    root.style.colorScheme = resolved
  }

  function setMode(next: ThemeMode) {
    mode.value = next
    try {
      localStorage.setItem(THEME_STORAGE_KEY, next)
    } catch {
      // ignore storage errors
    }
    applyDomTheme(resolvedTheme.value)
  }

  function cycleMode() {
    const order: ThemeMode[] = ['light', 'system', 'dark']
    const idx = order.indexOf(mode.value)
    setMode(order[(idx + 1) % order.length])
  }

  function init() {
    if (initialized) {
      applyDomTheme(resolvedTheme.value)
      return
    }
    initialized = true
    if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
      mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
      systemDark.value = mediaQuery.matches
      const onChange = (event: MediaQueryListEvent) => {
        systemDark.value = event.matches
        if (mode.value === 'system') {
          applyDomTheme(resolvedTheme.value)
        }
      }
      if (typeof mediaQuery.addEventListener === 'function') {
        mediaQuery.addEventListener('change', onChange)
      } else {
        mediaQuery.addListener(onChange)
      }
    }
    applyDomTheme(resolvedTheme.value)
  }

  return {
    mode,
    systemDark,
    resolvedTheme,
    isDark,
    setMode,
    cycleMode,
    init,
  }
})
