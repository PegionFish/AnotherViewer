import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import HistoryView from '../HistoryView.vue'
import { historyApi } from '@/api/history'
import type { HistoryItem } from '@/api/history'
import { filterSlotsApi } from '@/api/filterSlots'
import { preferencesApi } from '@/api/preferences'
import { usePreferencesStore } from '@/stores/preferences'
import type { Preferences } from '@/api/preferences'
import { setPrivacyMaskEnabled } from '@/utils/privacyMask'

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
}))

vi.mock('@/api/history', () => ({
  historyApi: { listHistory: vi.fn(), clearHistory: vi.fn() },
}))

vi.mock('@/api/filterSlots', () => ({
  filterSlotsApi: { get: vi.fn(), put: vi.fn() },
}))

vi.mock('@/api/preferences', () => ({
  preferencesApi: { get: vi.fn(), update: vi.fn() },
}))

/**
 * FilterSlotBar stub — F1's component is thin (chips + select emit); the stub
 * keeps this spec decoupled from its implementation. Clicking a chip emits
 * `select(id)` exactly like the real one (「全部」= null).
 */
vi.mock('@/components/FilterSlotBar.vue', () => ({
  default: {
    name: 'FilterSlotBar',
    props: ['slots', 'activeId'],
    emits: ['select'],
    template: `
      <nav class="filter-slot-bar">
        <button type="button" class="filter-slot-bar__chip" :class="{ 'filter-slot-bar__chip--active': activeId === null }" @click="$emit('select', null)">全部</button>
        <button v-for="s in slots" :key="s.id" type="button" class="filter-slot-bar__chip" :class="{ 'filter-slot-bar__chip--active': activeId === s.id }" @click="$emit('select', s.id)">{{ s.name }}</button>
      </nav>`,
  },
}))

/** Named-regex filter slot fixture (A5d contract: {id, name, pattern}). */
const FILTER_SLOTS = [
  { id: 's1', name: 'Artist', pattern: 'artist' },
  { id: 's2', name: 'Doujin', pattern: 'doujin|doujinshi' },
]

/** Fixed "now" would need fake timers; the tests only assert structure. */
const NOW = Date.now()

function makeHistoryItem(overrides: Partial<HistoryItem> = {}): HistoryItem {
  return {
    gid: 1,
    token: 'abc123',
    title: 'Sample Gallery',
    titleJpn: '',
    thumb: '',
    category: 2,
    rating: 4,
    mode: 0,
    time: NOW - 60_000, // one minute ago → "Today …"
    ...overrides,
  }
}

/** Seed the preferences store directly (avoids the async preferences load). */
function seedPrefs(general: Record<string, unknown>): void {
  const store = usePreferencesStore()
  store.prefs = { general } as unknown as Preferences
}

describe('HistoryView (W3-F3 A4 单列密信息行 + 服务端分页)', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    setActivePinia(createPinia())
    pushMock.mockClear()
    vi.mocked(historyApi.clearHistory).mockResolvedValue({ success: true })
    vi.mocked(filterSlotsApi.get).mockResolvedValue([])
    vi.mocked(preferencesApi.get).mockReturnValue(new Promise(() => {})) // never settles
  })

  afterEach(() => {
    setPrivacyMaskEnabled(false)
    wrapper?.unmount()
    vi.clearAllMocks()
  })

  async function mountHistory(items: HistoryItem[]) {
    vi.mocked(historyApi.listHistory).mockResolvedValue({ history: items, total: items.length })
    wrapper = mount(HistoryView)
    await flushPromises()
    await flushPromises()
    return wrapper
  }

  /** Click the filter-slot chip with the given name (「全部」= null). */
  async function clickFilterChip(name: string): Promise<void> {
    const chip = wrapper
      .findAll('.filter-slot-bar__chip')
      .find((c) => c.text() === name)!
    await chip.trigger('click')
    await flushPromises()
    await flushPromises()
  }

  /* ---------------- A4 单列密信息行（AppListRow + 角标/元信息） ---------------- */

  it('renders every history row as a single-column AppListRow with the last-viewed corner badge', async () => {
    await mountHistory([
      makeHistoryItem({ gid: 1, title: 'Gallery One' }),
      makeHistoryItem({ gid: 2, title: 'Gallery Two' }),
    ])

    const rows = wrapper.findAll('.app-list-row')
    expect(rows).toHaveLength(2)
    // The last-viewed stamp rides the row's absolute corner badge mount.
    const badges = wrapper.findAll('.history-list .time-badge')
    expect(badges).toHaveLength(2)
    expect(badges[0].text()).toMatch(/^Today \d/)
    // GalleryList（网格/列表双形态）已被删除——只剩单列行。
    expect(wrapper.find('.gallery-grid__cell').exists()).toBe(false)
    expect(wrapper.find('.gallery-list__row').exists()).toBe(false)
  })

  it('routes the row title through maskedTitle — masking on shows only #<gid> (privacy red line)', async () => {
    setPrivacyMaskEnabled(true)
    await mountHistory([
      makeHistoryItem({ gid: 7, title: 'Secret Title', titleJpn: '秘密のタイトル' }),
    ])

    const row = wrapper.find('.app-list-row')
    expect(row.find('.app-list-row__title').text()).toBe('#7')
    // 打码开启时日文副题一并隐藏（同 GalleryCard 的 !privacyMaskEnabled 守卫）。
    expect(row.find('.app-list-row__subtitle').exists()).toBe(false)
  })

  it('shows titleJpn as the subtitle when masking is off', async () => {
    await mountHistory([makeHistoryItem({ gid: 3, title: 'T', titleJpn: '日本語' })])
    const sub = wrapper.find('.app-list-row__subtitle')
    expect(sub.exists()).toBe(true)
    expect(sub.text()).toBe('日本語')
  })

  it('falls back to #<gid> for title-less rows (R4-6)', async () => {
    await mountHistory([makeHistoryItem({ gid: 7, title: '', titleJpn: '' })])
    expect(wrapper.find('.app-list-row__title').text()).toBe('#7')
  })

  it('renders the category chip in the meta row', async () => {
    await mountHistory([makeHistoryItem({ gid: 1, category: 2 })])
    expect(wrapper.find('.app-list-row__meta .category-chip').exists()).toBe(true)
  })

  it('rewrites external thumbnails through the image proxy', async () => {
    const thumb = 'https://ehgt.org/t/1/cover.jpg'
    await mountHistory([makeHistoryItem({ gid: 2, thumb })])
    const img = wrapper.find('.app-list-row__thumb img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe(`/api/v1/image/proxy?url=${encodeURIComponent(thumb)}`)
  })

  /* ---------------- W6 阅读进度透传（plan-2026-09-02） ---------------- */

  it('passes the history row page through as readProgress (badge shows N+1P)', async () => {
    // 历史行无页数 → 角标退化为 NP 格式。
    seedPrefs({ showReadProgress: true })
    await mountHistory([makeHistoryItem({ gid: 11, page: 5 })])
    const badge = wrapper.find('[data-testid="read-progress-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('6P')
  })

  it('hides the read-progress badge when the row carries no page (legacy server)', async () => {
    seedPrefs({ showReadProgress: true })
    await mountHistory([makeHistoryItem({ gid: 12 })])
    expect(wrapper.find('[data-testid="read-progress-badge"]').exists()).toBe(false)
  })

  it('hides the read-progress badge at page 0 (no progress yet, GalleryCard 语义)', async () => {
    seedPrefs({ showReadProgress: true })
    await mountHistory([makeHistoryItem({ gid: 15, page: 0 })])
    expect(wrapper.find('[data-testid="read-progress-badge"]').exists()).toBe(false)
  })

  it('hides the read-progress badge when showReadProgress is off', async () => {
    seedPrefs({ showReadProgress: false })
    await mountHistory([makeHistoryItem({ gid: 13, page: 5 })])
    expect(wrapper.find('[data-testid="read-progress-badge"]').exists()).toBe(false)
  })

  /* ---------------- 点击分区（A4：缩略图→详情 / 主体→阅读） ---------------- */

  it('opens the gallery detail from the thumbnail click zone', async () => {
    await mountHistory([makeHistoryItem({ gid: 42, token: 'abc123' })])
    await wrapper.find('.app-list-row__thumb').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ path: '/gallery/42', query: { token: 'abc123' } })
  })

  it('opens the reader directly from the row body click zone (A4)', async () => {
    await mountHistory([makeHistoryItem({ gid: 7, token: 'tok7' })])
    await wrapper.find('.app-list-row').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ path: '/reader/7', query: { token: 'tok7' } })
  })

  it('omits the token query when the history row carries none (P-A)', async () => {
    await mountHistory([makeHistoryItem({ gid: 9, token: '' })])
    await wrapper.find('.app-list-row__thumb').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ path: '/gallery/9', query: {} })
  })

  /* ---------------- search + filter slots (A5d, 互斥) ---------------- */

  it('loads the unfiltered history without q/regex params', async () => {
    await mountHistory([makeHistoryItem({ gid: 1 })])
    expect(historyApi.listHistory).toHaveBeenCalledWith(null, undefined, 0, 50)
  })

  it('activates a filter slot and requests q=pattern&regex=true', async () => {
    vi.mocked(filterSlotsApi.get).mockResolvedValue(FILTER_SLOTS)
    await mountHistory([makeHistoryItem({ gid: 1 })])
    expect(wrapper.findAll('.filter-slot-bar__chip')).toHaveLength(3) // 全部 + 2

    await clickFilterChip('Artist')
    expect(historyApi.listHistory).toHaveBeenLastCalledWith('artist', true, 0, 50)
    // 互斥：选槽位清空搜索词。
    const input = wrapper.find('.search-bar__input').element as HTMLInputElement
    expect(input.value).toBe('')

    // 回到「全部」→ 恢复无过滤加载（分页重置回第 1 页）。
    await clickFilterChip('全部')
    expect(historyApi.listHistory).toHaveBeenLastCalledWith(null, undefined, 0, 50)
  })

  it('debounces a typed search to q=word and cancels the active slot', async () => {
    vi.useFakeTimers()
    vi.mocked(filterSlotsApi.get).mockResolvedValue(FILTER_SLOTS)
    await mountHistory([makeHistoryItem({ gid: 1 })])

    await clickFilterChip('Artist')
    await wrapper.find('.search-bar__input').setValue('futa')
    // 防抖 400ms 内不触发请求。
    await vi.advanceTimersByTimeAsync(200)
    expect(historyApi.listHistory).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(300)
    await flushPromises()

    // 输入搜索取消槽位 → LIKE 搜索（无 regex 参数）。
    expect(historyApi.listHistory).toHaveBeenLastCalledWith('futa', undefined, 0, 50)
    const artistChip = wrapper
      .findAll('.filter-slot-bar__chip')
      .find((c) => c.text() === 'Artist')!
    expect(artistChip.classes()).not.toContain('filter-slot-bar__chip--active')
    vi.useRealTimers()
  })

  it('holds the debounced search while the IME composition is active (C1)', async () => {
    vi.useFakeTimers()
    await mountHistory([makeHistoryItem({ gid: 1 })])
    const input = wrapper.find('.search-bar__input')

    // 拼音组合期间的中间态输入不触发请求（首屏 1 次）。
    await input.trigger('compositionstart')
    await input.setValue('futa')
    await vi.advanceTimersByTimeAsync(1000)
    expect(historyApi.listHistory).toHaveBeenCalledTimes(1)

    // compositionend 后的最终选词照常防抖提交。
    await input.trigger('compositionend')
    await input.setValue('漫画')
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()
    expect(historyApi.listHistory).toHaveBeenLastCalledWith('漫画', undefined, 0, 50)
    vi.useRealTimers()
  })

  it('clear button restores the unfiltered history', async () => {
    vi.useFakeTimers()
    await mountHistory([makeHistoryItem({ gid: 1 })])
    await wrapper.find('.search-bar__input').setValue('futa')
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()
    expect(historyApi.listHistory).toHaveBeenLastCalledWith('futa', undefined, 0, 50)

    await wrapper.find('.search-bar__clear').trigger('click')
    await flushPromises()
    expect(historyApi.listHistory).toHaveBeenLastCalledWith(null, undefined, 0, 50)
    vi.useRealTimers()
  })

  /* ---------------- 服务端分页（A4，usePagedList + 分页条） ---------------- */

  /** 按服务端分页语义切片的 mock：page 0 起始，pageSize 每页条数，返回 total 全集数。 */
  function pagedServer(totalRows: number) {
    return async (
      _q: unknown,
      _r: unknown,
      page = 0,
      pageSize = 50,
    ): Promise<{ history: HistoryItem[]; total: number }> => {
      const start = page * pageSize
      const count = Math.max(0, Math.min(pageSize, totalRows - start))
      return {
        history: Array.from({ length: count }, (_, i) =>
          makeHistoryItem({ gid: start + i + 1, title: `H ${start + i + 1}` }),
        ),
        total: totalRows,
      }
    }
  }

  async function mountPaged(totalRows: number): Promise<void> {
    vi.mocked(historyApi.listHistory).mockImplementation(pagedServer(totalRows))
    wrapper = mount(HistoryView)
    await flushPromises()
    await flushPromises()
  }

  it('first screen loads page=0 only and shows the pagination bar (no full fetch)', async () => {
    await mountPaged(120)

    // 首屏：page=0&pageSize=50，整页替换渲染 50 行。
    expect(historyApi.listHistory).toHaveBeenCalledTimes(1)
    expect(historyApi.listHistory).toHaveBeenLastCalledWith(null, undefined, 0, 50)
    expect(wrapper.findAll('.app-list-row')).toHaveLength(50)
    expect(wrapper.text()).toContain('H 1')
    // 标题计数 = 服务端过滤后全集条数。
    expect(wrapper.find('.history-view__count').text()).toBe('120 galleries')
    // 分页条：第 1 / 3 页 · 120 条。
    const bar = wrapper.find('[data-testid="history-pagination"]')
    expect(bar.exists()).toBe(true)
    expect(bar.find('.pagination-bar__info').text()).toBe('第 1 / 3 页 · 120 条')
  })

  it('hides the pagination bar when total <= pageSize (Android 语义)', async () => {
    await mountHistory([makeHistoryItem({ gid: 1 }), makeHistoryItem({ gid: 2 })])
    expect(wrapper.find('[data-testid="history-pagination"]').exists()).toBe(false)
  })

  it('jumps to a page via the page buttons (whole-page replace)', async () => {
    await mountPaged(120)

    const bar = () => wrapper.find('[data-testid="history-pagination"]')
    await bar()
      .findAll('.pagination-bar__page')
      .find((b) => b.text() === '3')!
      .trigger('click')
    await flushPromises()
    await flushPromises()

    // usePagedList 页码 3 → /history/list 的 page=2（0 起）。
    expect(historyApi.listHistory).toHaveBeenLastCalledWith(null, undefined, 2, 50)
    // 最后一页只剩 20 条——整页替换（不再是追加语义）。
    expect(wrapper.findAll('.app-list-row')).toHaveLength(20)
    expect(wrapper.text()).toContain('H 120')
    expect(bar().find('.pagination-bar__info').text()).toBe('第 3 / 3 页 · 120 条')
    expect(bar().find('.pagination-bar__page--active').text()).toBe('3')
  })

  it('reloads page 1 with the selected page size', async () => {
    await mountPaged(120)

    await wrapper.find('.pagination-bar__select').setValue('100')
    await flushPromises()
    await flushPromises()

    expect(historyApi.listHistory).toHaveBeenLastCalledWith(null, undefined, 0, 100)
    expect(wrapper.findAll('.app-list-row')).toHaveLength(100)
    expect(wrapper.find('.pagination-bar__info').text()).toBe('第 1 / 2 页 · 120 条')
  })

  it('pages with keyboard PageDown / PageUp (PC)', async () => {
    await mountPaged(120)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageDown', cancelable: true }))
    await flushPromises()
    await flushPromises()
    expect(historyApi.listHistory).toHaveBeenLastCalledWith(null, undefined, 1, 50)
    expect(wrapper.find('.pagination-bar__info').text()).toBe('第 2 / 3 页 · 120 条')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageUp', cancelable: true }))
    await flushPromises()
    await flushPromises()
    expect(historyApi.listHistory).toHaveBeenLastCalledWith(null, undefined, 0, 50)
    expect(wrapper.find('.pagination-bar__info').text()).toBe('第 1 / 3 页 · 120 条')
  })

  /* ---------------- F4 REGEX_INVALID 错误识别 --------------- */

  /** API 错误信封（{error:{code,message,traceId,status}}）的 axios 形状。 */
  function apiError(code: string): {
    response: { status: number; data: { error: { code: string; message: string; traceId: string; status: number } } }
  } {
    return {
      response: {
        status: 400,
        data: { error: { code, message: '正则表达式无效', traceId: '0123456789abcdef', status: 400 } },
      },
    }
  }

  it('names REGEX_INVALID when the server rejects the filter pattern (F4)', async () => {
    vi.mocked(historyApi.listHistory).mockRejectedValue(apiError('REGEX_INVALID'))
    wrapper = mount(HistoryView)
    await flushPromises()
    await flushPromises()

    // 首屏失败（无内容）→ 错误态 + 专属文案（不再是泛化的 Failed to load）。
    expect(wrapper.find('[data-testid="content-state-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('正则无效')
    // Toast teleports to body — assert on document.
    expect(document.querySelector('.toast')?.textContent).toContain('正则无效')
  })

  it('keeps the generic error tip for non-REGEX failures on the first screen (F4)', async () => {
    vi.mocked(historyApi.listHistory).mockRejectedValue(new Error('boom'))
    wrapper = mount(HistoryView)
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('[data-testid="content-state-error"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('正则无效')
    expect(document.querySelector('.toast')).toBeNull()
  })

  /* ---------------- F6 清历史失败不再静默 --------------- */

  it('closes the dialog and shows an error toast when clearing fails (F6)', async () => {
    vi.mocked(historyApi.clearHistory).mockRejectedValue(new Error('boom'))
    await mountHistory([makeHistoryItem({ gid: 1 })])

    // FAB「Clear history」→ 确认对话框打开。
    await wrapper
      .findAll('.fab--mini')
      .find((b) => b.attributes('aria-label') === 'Clear history')!
      .trigger('click')
    await flushPromises()
    const scrim = document.querySelector('.dialog-scrim')
    expect(scrim).not.toBeNull()

    // 点击 Clear → 失败：对话框关闭（对齐成功路径）+ 错误 toast。
    const clearBtn = new DOMWrapper(scrim!.querySelector<HTMLButtonElement>('.dialog__btn--danger')!)
    await clearBtn.trigger('click')
    await flushPromises()

    expect(document.querySelector('.dialog-scrim')).toBeNull()
    expect(document.querySelector('.toast')?.textContent).toContain('清除历史失败')
    // 列表数据保持原样（未误清）。
    expect(wrapper.text()).toContain('Sample Gallery')
  })

  it('clears the list and closes the dialog when clearing succeeds (F6 对照)', async () => {
    await mountHistory([makeHistoryItem({ gid: 1 })])
    await wrapper
      .findAll('.fab--mini')
      .find((b) => b.attributes('aria-label') === 'Clear history')!
      .trigger('click')
    await flushPromises()

    const clearBtn = new DOMWrapper(
      document.querySelector('.dialog-scrim')!.querySelector<HTMLButtonElement>('.dialog__btn--danger')!,
    )
    await clearBtn.trigger('click')
    await flushPromises()

    expect(document.querySelector('.dialog-scrim')).toBeNull()
    expect(wrapper.find('[data-testid="content-state-empty"]').exists()).toBe(true)
    expect(document.querySelector('.toast')).toBeNull()
  })
})
