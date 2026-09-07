import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useThemeStore } from '../theme'
import { usePreferencesStore } from '../preferences'
import { preferencesApi } from '@/api/preferences'
import type { Preferences } from '@/api/preferences'
import type { Theme } from '../theme'

// T3：theme store 现在写穿服务器偏好（setTheme → updateGeneral → 防抖 PUT），
// mock 掉 api 层避免测试里发起真实请求。
vi.mock('@/api/preferences', () => ({
  preferencesApi: { get: vi.fn(), update: vi.fn() },
}))

/** Seed the preferences store directly (avoids the async preferences load). */
function seedGeneral(general: Record<string, unknown>): void {
  usePreferencesStore().prefs = { general } as unknown as Preferences
}

/**
 * 模拟系统主题翻转。happy-dom 每次 matchMedia() 都返回新实例，往新实例上
 * dispatchEvent 够不着 store 创建时注册的监听器——替换成可受控的假
 * matchMedia（必须在 useThemeStore() 之前安装，监听器在 store 创建时注册）。
 */
function installMatchMediaStub(): { fire(matches: boolean): void } {
  const listeners: Array<(e: { matches: boolean }) => void> = []
  const stub = {
    matches: false,
    addEventListener: (_type: string, cb: (e: { matches: boolean }) => void) => {
      listeners.push(cb)
    },
    removeEventListener: () => {},
  }
  vi.spyOn(window, 'matchMedia').mockImplementation(() => stub as unknown as MediaQueryList)
  return {
    fire(matches: boolean) {
      stub.matches = matches
      for (const cb of listeners) cb({ matches })
    },
  }
}

describe('theme store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    // Remove any meta theme-color tags from previous tests
    document.querySelectorAll('meta[name="theme-color"]').forEach((el) => el.remove())
  })

  afterEach(() => {
    vi.useRealTimers()
    // 还原 installMatchMediaStub 的 window.matchMedia spy，避免泄漏到其他用例。
    vi.restoreAllMocks()
  })

  describe('initialization', () => {
    it('defaults to light when no stored theme and no system preference', () => {
      // happy-dom matchMedia returns matches: false by default
      const store = useThemeStore()
      expect(store.currentTheme).toBe('light')
    })

    it('restores theme from localStorage', () => {
      localStorage.setItem('anotherviewer-theme', 'black')
      const store = useThemeStore()
      expect(store.currentTheme).toBe('black')
    })

    it('ignores invalid localStorage values', () => {
      localStorage.setItem('anotherviewer-theme', 'neon')
      const store = useThemeStore()
      expect(store.currentTheme).toBe('light')
    })
  })

  describe('setTheme', () => {
    it('sets the theme', () => {
      const store = useThemeStore()
      store.setTheme('dark')
      expect(store.currentTheme).toBe('dark')
    })

    it('sets data-theme attribute on documentElement', () => {
      const store = useThemeStore()
      store.setTheme('black')
      expect(document.documentElement.getAttribute('data-theme')).toBe('black')
    })

    it('persists to localStorage', () => {
      const store = useThemeStore()
      store.setTheme('dark')
      expect(localStorage.getItem('anotherviewer-theme')).toBe('dark')
    })

    it('updates meta theme-color', () => {
      const store = useThemeStore()
      store.setTheme('dark')
      const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
      expect(meta).not.toBeNull()
      expect(meta!.content).toBe('#323232')
    })
  })

  describe('server preference write-through (T3-2)', () => {
    it('setTheme writes the new theme into preferences (debounced PUT)', async () => {
      vi.useFakeTimers()
      seedGeneral({ theme: 'light', themeAutoSwitch: false })
      const store = useThemeStore()

      store.setTheme('dark')
      // 乐观：本地 prefs 立即更新
      expect(usePreferencesStore().prefs?.general?.theme).toBe('dark')

      await vi.advanceTimersByTimeAsync(700)
      expect(preferencesApi.update).toHaveBeenCalledTimes(1)
      const payload = vi.mocked(preferencesApi.update).mock.calls[0][0]
      expect(payload.general).toMatchObject({ theme: 'dark' })
    })

    it('skips the write when the server value already matches (backfill no-echo guard)', async () => {
      vi.useFakeTimers()
      seedGeneral({ theme: 'dark', themeAutoSwitch: false })
      const store = useThemeStore()

      store.setTheme('dark')
      await vi.advanceTimersByTimeAsync(700)
      expect(preferencesApi.update).not.toHaveBeenCalled()
      expect(store.currentTheme).toBe('dark')
    })

    it('does not write through when preferences are not loaded (updateGeneral guard)', async () => {
      vi.useFakeTimers()
      const store = useThemeStore()
      store.setTheme('black')
      expect(store.currentTheme).toBe('black')
      await vi.advanceTimersByTimeAsync(700)
      expect(preferencesApi.update).not.toHaveBeenCalled()
    })

    it('toggleTheme routes through setTheme so the write-through applies', async () => {
      vi.useFakeTimers()
      seedGeneral({ theme: 'light', themeAutoSwitch: false })
      const store = useThemeStore()

      store.toggleTheme()
      expect(store.currentTheme).toBe('dark')
      expect(usePreferencesStore().prefs?.general?.theme).toBe('dark')
      await vi.advanceTimersByTimeAsync(700)
      expect(preferencesApi.update).toHaveBeenCalledTimes(1)
    })
  })

  describe('system-follow gate reads themeAutoSwitch (T3-3)', () => {
    it('blocks the system change when preferences say themeAutoSwitch=false', () => {
      const system = installMatchMediaStub()
      seedGeneral({ theme: 'black', themeAutoSwitch: false })
      const store = useThemeStore()
      store.setTheme('black') // 守卫：与偏好一致 → 不产生写穿

      system.fire(true)
      // 旧闸门（!getStoredTheme()）在无 localStorage 时会放行；偏好优先。
      expect(store.currentTheme).toBe('black')
      expect(preferencesApi.update).not.toHaveBeenCalled()
    })

    it('follows the system when themeAutoSwitch=true even if a theme was stored', async () => {
      vi.useFakeTimers()
      const system = installMatchMediaStub()
      localStorage.setItem('anotherviewer-theme', 'black')
      seedGeneral({ theme: 'black', themeAutoSwitch: true })
      const store = useThemeStore()
      expect(store.currentTheme).toBe('black')

      // matches=false → light；旧闸门会因 localStorage 已选而拦截。
      system.fire(false)
      expect(store.currentTheme).toBe('light')
      // 跟随系统同样写穿偏好（预期语义，见 theme.ts 注释）。
      await vi.advanceTimersByTimeAsync(700)
      expect(usePreferencesStore().prefs?.general?.theme).toBe('light')
      expect(preferencesApi.update).toHaveBeenCalledTimes(1)
    })

    it('keeps the legacy gate before preferences load (stored theme wins)', () => {
      const system = installMatchMediaStub()
      localStorage.setItem('anotherviewer-theme', 'black')
      const store = useThemeStore()

      system.fire(false)
      expect(store.currentTheme).toBe('black')
    })

    it('follows the system before preferences load when nothing is stored (legacy default)', () => {
      const system = installMatchMediaStub()
      const store = useThemeStore()
      store.setTheme('black')
      // setTheme 的 watch 会写 localStorage——清掉以模拟「从未显式选过主题」。
      localStorage.removeItem('anotherviewer-theme')

      system.fire(false)
      expect(store.currentTheme).toBe('light')
    })
  })

  describe('toggleTheme (cycling)', () => {
    it('cycles light → dark → black → light', () => {
      const store = useThemeStore()
      expect(store.currentTheme).toBe('light')

      store.toggleTheme()
      expect(store.currentTheme).toBe('dark')

      store.toggleTheme()
      expect(store.currentTheme).toBe('black')

      store.toggleTheme()
      expect(store.currentTheme).toBe('light')
    })

    it('updates data-theme attribute on each cycle', () => {
      const store = useThemeStore()

      store.toggleTheme()
      expect(document.documentElement.getAttribute('data-theme')).toBe('dark')

      store.toggleTheme()
      expect(document.documentElement.getAttribute('data-theme')).toBe('black')

      store.toggleTheme()
      expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    })
  })

  describe('data-theme attribute', () => {
    it('is set on store creation', () => {
      useThemeStore()
      expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    })

    it('reflects stored theme on creation', () => {
      localStorage.setItem('anotherviewer-theme', 'dark')
      useThemeStore()
      expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    })
  })

  describe('meta theme-color', () => {
    it.each<[Theme, string]>([
      ['light', '#009688'],
      ['dark', '#323232'],
      ['black', '#000000'],
    ])('sets correct color for %s theme', (theme, expectedColor) => {
      const store = useThemeStore()
      store.setTheme(theme)
      const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
      expect(meta).not.toBeNull()
      expect(meta!.content).toBe(expectedColor)
    })

    it('creates meta tag if not present', () => {
      expect(document.querySelector('meta[name="theme-color"]')).toBeNull()
      useThemeStore()
      expect(document.querySelector('meta[name="theme-color"]')).not.toBeNull()
    })
  })
})
