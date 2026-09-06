/**
 * Preferences store — save-failure recovery (audit P2)。
 *
 * 修复点：保存失败曾让 `dirty` 永真，load() 的 `|| dirty` 分支会从此静默
 * 丢弃服务器状态（本地与服务器永久失联）。失败必须复位 dirty，让下一次
 * load() 能覆盖本地乐观改动（失败本身经 saveError → 设置页 snackbar 提示）。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePreferencesStore } from '../preferences'
import { preferencesApi, type Preferences } from '@/api/preferences'

vi.mock('@/api/preferences', () => ({
  preferencesApi: { get: vi.fn(), update: vi.fn() },
}))

/** Minimal Preferences fixture — only the fields under test are typed. */
function serverPrefs(theme: string): Preferences {
  return { general: { theme }, reader: {}, privacy: {} } as unknown as Preferences
}

describe('preferences store — save failure releases the load gate (audit P2)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('a failed save lets the next load overwrite the local optimistic state', async () => {
    vi.useFakeTimers()
    const store = usePreferencesStore()

    vi.mocked(preferencesApi.get).mockResolvedValueOnce(serverPrefs('server-v1'))
    await store.load()
    expect(store.prefs?.general?.theme).toBe('server-v1')

    // 本地改动：乐观更新 + 600ms 防抖保存 → 失败。
    vi.mocked(preferencesApi.update).mockRejectedValueOnce(new Error('network down'))
    store.updateGeneral({ theme: 'local-edit' })
    expect(store.prefs?.general?.theme).toBe('local-edit')
    await vi.advanceTimersByTimeAsync(700)
    expect(store.saveError).toBe('network down')

    // 修复点：再次 load() 服务器状态能进来（修复前被 dirty 永久压制）。
    vi.mocked(preferencesApi.get).mockResolvedValueOnce(serverPrefs('server-v2'))
    await store.load()
    expect(store.prefs?.general?.theme).toBe('server-v2')
    expect(store.loadError).toBe(false)
  })

  it('a later edit after the failure re-arms the save and can succeed', async () => {
    vi.useFakeTimers()
    const store = usePreferencesStore()

    vi.mocked(preferencesApi.get).mockResolvedValueOnce(serverPrefs('dark'))
    await store.load()

    vi.mocked(preferencesApi.update).mockRejectedValueOnce(new Error('boom'))
    store.updateGeneral({ theme: 'light' })
    await vi.advanceTimersByTimeAsync(700)
    expect(store.saveError).toBe('boom')

    // 失败后的新编辑照常重新保存并清掉错误。
    vi.mocked(preferencesApi.update).mockResolvedValueOnce(serverPrefs('light'))
    store.updateGeneral({ theme: 'light' })
    await vi.advanceTimersByTimeAsync(700)
    expect(store.saveError).toBeNull()
    expect(preferencesApi.update).toHaveBeenCalledTimes(2)
  })
})
