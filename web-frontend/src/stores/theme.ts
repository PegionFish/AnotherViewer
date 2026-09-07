import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
// 循环依赖说明：preferences.ts 也 import 本模块（load() 回灌主题）。两边的
// store 实例都只在函数体内互相获取（useXxxStore()），模块顶层不触碰对方
// 导出——ESM 循环加载下安全（defineStore 的 setup 到首次调用时才执行）。
import { usePreferencesStore } from '@/stores/preferences'

export type Theme = 'light' | 'dark' | 'black'

const THEMES: Theme[] = ['light', 'dark', 'black']
const STORAGE_KEY = 'anotherviewer-theme'

/** Meta theme-color values per theme (matches --color-toolbar in tokens.css) */
const META_THEME_COLORS: Record<Theme, string> = {
  light: '#009688',
  dark: '#323232',
  black: '#000000',
}

/** 服务器偏好 `general.theme` 的合法性守卫（preferences.load() 回灌用）。 */
export function isTheme(value: unknown): value is Theme {
  return typeof value === 'string' && (THEMES as readonly string[]).includes(value)
}

function getSystemTheme(): Theme {
  if (typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches) {
    return 'dark'
  }
  return 'light'
}

function getStoredTheme(): Theme | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored && THEMES.includes(stored as Theme)) {
      return stored as Theme
    }
  } catch {
    // localStorage unavailable (SSR / privacy mode)
  }
  return null
}

function applyTheme(theme: Theme) {
  document.documentElement.setAttribute('data-theme', theme)

  // Update <meta name="theme-color"> for PWA / browser chrome
  let meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
  if (!meta) {
    meta = document.createElement('meta')
    meta.name = 'theme-color'
    document.head.appendChild(meta)
  }
  meta.content = META_THEME_COLORS[theme]
}

/**
 * 系统跟随闸门（T3-3）：优先读偏好 `general.themeAutoSwitch`（设置页的真实
 * 开关，不再像旧闸门那样「用户选过一次主题就永久关闭跟随」）；prefs 未加载
 * 时回退旧行为（从未显式选过主题才跟随）。
 */
function shouldFollowSystem(): boolean {
  const auto = usePreferencesStore().prefs?.general?.themeAutoSwitch
  if (auto === undefined) return !getStoredTheme()
  return auto
}

export const useThemeStore = defineStore('theme', () => {
  const currentTheme = ref<Theme>(getStoredTheme() ?? getSystemTheme())

  // Apply immediately on store creation
  applyTheme(currentTheme.value)

  // React to changes (sync flush so DOM updates are immediate)
  watch(currentTheme, (theme) => {
    applyTheme(theme)
    try {
      localStorage.setItem(STORAGE_KEY, theme)
    } catch {
      // ignore write failures
    }
  }, { flush: 'sync' })

  // Listen for system preference changes (gate: themeAutoSwitch preference,
  // falling back to "no explicit user choice" before preferences load)
  if (typeof window !== 'undefined' && window.matchMedia) {
    const mql = window.matchMedia('(prefers-color-scheme: dark)')
    mql.addEventListener('change', (e) => {
      if (!shouldFollowSystem()) return
      // 跟随系统触发的切换同样走 setTheme——会写 localStorage 与服务器偏好
      // （themeAutoSwitch 开启时服务器主题随系统漂移，属预期语义）。
      setTheme(e.matches ? 'dark' : 'light')
    })
  }

  function setTheme(theme: Theme) {
    currentTheme.value = theme
    // 写穿服务器偏好（T3-2）：单一权威在服务器，切换入口（抽屉按钮、设置页、
    // 系统跟随、偏好回灌）统一经此落盘。updateGeneral 在 prefs 未加载时自身
    // no-op；与服务器值相同时跳过——preferences.load() 的回灌路径
    // （load → setTheme）因此不会在这里形成回写回环/多余 PUT。
    const preferencesStore = usePreferencesStore()
    if (preferencesStore.prefs && preferencesStore.prefs.general.theme !== theme) {
      preferencesStore.updateGeneral({ theme })
    }
  }

  /** Cycle light → dark → black → light */
  function toggleTheme() {
    const idx = THEMES.indexOf(currentTheme.value)
    // 必须经 setTheme（而非直接赋值 currentTheme）：否则抽屉/搜索页的
    // toggleTheme 不触发服务器偏好写穿（T3-2）。
    setTheme(THEMES[(idx + 1) % THEMES.length])
  }

  return {
    currentTheme,
    setTheme,
    toggleTheme,
  }
})
