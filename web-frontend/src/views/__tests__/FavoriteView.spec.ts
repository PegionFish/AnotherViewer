import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { KeepAlive, defineComponent, h, shallowRef, type Component } from 'vue'
import FavoriteView from '../FavoriteView.vue'
import { favoriteApi } from '@/api/favorite'
import type { FavoriteItem, FavoriteListResponse } from '@/api/favorite'
import { filterSlotsApi } from '@/api/filterSlots'
import { preferencesApi } from '@/api/preferences'
import { usePreferencesStore } from '@/stores/preferences'
import type { Preferences } from '@/api/preferences'
import { setPrivacyMaskEnabled } from '@/utils/privacyMask'

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
}))

vi.mock('@/api/favorite', () => ({
  favoriteApi: { listFavorites: vi.fn(), addFavorite: vi.fn(), removeFavorite: vi.fn() },
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

/** 默认每页条数 50（W3-F4b：控制器默认 50、钳制 1..200；前端默认档同为 50）。 */
const PAGE_SIZE = 50

function makeFavorite(gid: number, overrides: Partial<FavoriteItem> = {}): FavoriteItem {
  return {
    gid,
    token: `tok${gid}`,
    title: `Favorite ${gid}`,
    titleJpn: '',
    thumb: '',
    category: 2,
    rating: 4,
    uploader: 'uploader',
    posted: '',
    favoriteSlot: 0,
    ...overrides,
  }
}

/**
 * W2-B2 信封：favorites/totalPages/currentPage + 新增 page/pageSize/total
 * （W3-F4b 起 favorite.ts 已声明后三个可选字段）。
 */
function listResult(
  items: FavoriteItem[],
  opts: {
    total?: number
    totalPages?: number
    currentPage?: number
    pageSize?: number
  } = {},
): FavoriteListResponse {
  const page = opts.currentPage ?? 1
  const total = opts.total ?? items.length
  const size = opts.pageSize ?? PAGE_SIZE
  return {
    favorites: items,
    totalPages: opts.totalPages ?? Math.max(1, Math.ceil(total / size)),
    currentPage: page,
    total,
    page,
    pageSize: size,
  }
}

/** Seed the preferences store directly (avoids the async preferences load). */
function seedPrefs(general: Record<string, unknown>): void {
  const store = usePreferencesStore()
  store.prefs = { general } as unknown as Preferences
}

describe('FavoriteView (W3-F4 A4 单列密信息行 + 服务端分页)', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    setActivePinia(createPinia())
    pushMock.mockClear()
    vi.mocked(filterSlotsApi.get).mockResolvedValue([])
    vi.mocked(preferencesApi.get).mockReturnValue(new Promise(() => {})) // never settles
  })

  afterEach(() => {
    setPrivacyMaskEnabled(false)
    wrapper?.unmount()
    vi.clearAllMocks()
  })

  /** Mount with one page of favorites served by the listFavorites mock. */
  async function mountFavorites(items: FavoriteItem[]): Promise<VueWrapper> {
    vi.mocked(favoriteApi.listFavorites).mockResolvedValue(listResult(items))
    wrapper = mount(FavoriteView)
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

  /* ---------------- A4 单列密信息行（AppListRow + 收藏夹角标/元信息） ---------------- */

  it('loads the folder list through /favorite/list with slot/page params', async () => {
    await mountFavorites([makeFavorite(1), makeFavorite(2)])
    expect(favoriteApi.listFavorites).toHaveBeenCalledWith(0, 1, null, undefined, 50)
    expect(wrapper.text()).toContain('Favorite 1')
    // 标题计数 = 服务端过滤后全集条数（total 驱动，不再累计已加载数）。
    expect(wrapper.find('.favorite-view__count').text()).toBe('2 galleries')
  })

  it('renders every favorite row as a single-column AppListRow with the folder badge', async () => {
    await mountFavorites([
      makeFavorite(1, { favoriteSlot: 0 }),
      makeFavorite(2, { favoriteSlot: 3 }),
    ])

    const rows = wrapper.findAll('.app-list-row')
    expect(rows).toHaveLength(2)
    // F-UX5：♥ 角标渲染条目真实 favoriteSlot（tab 0 混合 -1/0 的语义）。
    const badges = wrapper.findAll('.favorite-list .slot-badge')
    expect(badges).toHaveLength(2)
    expect(badges[0].text()).toBe('0')
    expect(badges[1].text()).toBe('3')
    expect(badges[1].attributes('title')).toBe('In Favorites 3')
    // GalleryList（网格/列表双形态）已被删除——只剩单列行。
    expect(wrapper.find('.gallery-grid__cell').exists()).toBe(false)
    expect(wrapper.find('.gallery-list__row').exists()).toBe(false)
  })

  it('routes the row title through maskedTitle — masking on shows only #<gid> (privacy red line)', async () => {
    setPrivacyMaskEnabled(true)
    await mountFavorites([
      makeFavorite(7, { title: 'Secret Title', titleJpn: '秘密のタイトル' }),
    ])

    const row = wrapper.find('.app-list-row')
    expect(row.find('.app-list-row__title').text()).toBe('#7')
    // 打码开启时日文副题一并隐藏（同 GalleryCard 的 !privacyMaskEnabled 守卫）。
    expect(row.find('.app-list-row__subtitle').exists()).toBe(false)
  })

  it('shows titleJpn as the subtitle when masking is off', async () => {
    await mountFavorites([makeFavorite(3, { title: 'T', titleJpn: '日本語' })])
    const sub = wrapper.find('.app-list-row__subtitle')
    expect(sub.exists()).toBe(true)
    expect(sub.text()).toBe('日本語')
  })

  it('hides the jpn subtitle when seeded prefs keep showJpnTitle=false (T2 — protocol default)', async () => {
    seedPrefs({ showJpnTitle: false })
    await mountFavorites([makeFavorite(3, { title: 'T', titleJpn: '日本語' })])
    expect(wrapper.find('.app-list-row__subtitle').exists()).toBe(false)
  })

  it('keeps the jpn subtitle when seeded prefs set showJpnTitle=true (T2)', async () => {
    seedPrefs({ showJpnTitle: true })
    await mountFavorites([makeFavorite(3, { title: 'T', titleJpn: '日本語' })])
    expect(wrapper.find('.app-list-row__subtitle').text()).toBe('日本語')
  })

  it('falls back to #<gid> for title-less rows (R4-6)', async () => {
    await mountFavorites([makeFavorite(7, { title: '', titleJpn: '' })])
    expect(wrapper.find('.app-list-row__title').text()).toBe('#7')
  })

  it('renders the category chip in the meta row', async () => {
    await mountFavorites([makeFavorite(1, { category: 2 })])
    expect(wrapper.find('.app-list-row__meta .category-chip').exists()).toBe(true)
  })

  /* ---------------- W6 阅读进度透传（plan-2026-09-02） ---------------- */

  it('passes the favorite row readProgress through as the N+1P badge', async () => {
    seedPrefs({ showReadProgress: true })
    await mountFavorites([makeFavorite(11, { readProgress: 5 })])
    const badge = wrapper.find('[data-testid="read-progress-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('6P')
  })

  it('hides the read-progress badge when the row carries no progress (legacy server)', async () => {
    seedPrefs({ showReadProgress: true })
    await mountFavorites([makeFavorite(12)])
    expect(wrapper.find('[data-testid="read-progress-badge"]').exists()).toBe(false)
  })

  it('hides the read-progress badge at progress 0 (no progress yet, GalleryCard 语义)', async () => {
    seedPrefs({ showReadProgress: true })
    await mountFavorites([makeFavorite(15, { readProgress: 0 })])
    expect(wrapper.find('[data-testid="read-progress-badge"]').exists()).toBe(false)
  })

  it('hides the read-progress badge when showReadProgress is off', async () => {
    seedPrefs({ showReadProgress: false })
    await mountFavorites([makeFavorite(13, { readProgress: 5 })])
    expect(wrapper.find('[data-testid="read-progress-badge"]').exists()).toBe(false)
  })

  /* ---------------- 点击分区（用户定案：整行→详情，主体直达阅读仅下载页） ---------------- */

  it('opens the gallery detail from the thumbnail click zone (P-A token passthrough)', async () => {
    await mountFavorites([makeFavorite(42)])
    await wrapper.find('.app-list-row__thumb').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ path: '/gallery/42', query: { token: 'tok42' } })
  })

  it('opens the gallery detail from the row body click zone', async () => {
    await mountFavorites([makeFavorite(7)])
    await wrapper.find('.app-list-row').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ path: '/gallery/7', query: { token: 'tok7' } })
  })

  it('omits the token query when the favorite row carries none (P-A)', async () => {
    await mountFavorites([makeFavorite(43, { token: '' })])
    await wrapper.find('.app-list-row__thumb').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ path: '/gallery/43', query: {} })
  })

  /* ---------------- 收藏夹页签 + search + filter slots (A5d, 互斥) ---------------- */

  it('switching the folder chip reloads with the new slot param', async () => {
    await mountFavorites([makeFavorite(1)])
    await wrapper.findAll('.slot-bar__chip').find((c) => c.text() === 'Favorites 3')!.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(3, 1, null, undefined, 50)
  })

  it('activates a filter slot and requests q=pattern&regex=true', async () => {
    vi.mocked(filterSlotsApi.get).mockResolvedValue(FILTER_SLOTS)
    await mountFavorites([makeFavorite(1)])
    expect(wrapper.findAll('.filter-slot-bar__chip')).toHaveLength(3) // 全部 + 2

    await clickFilterChip('Artist')
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(0, 1, 'artist', true, 50)
    // 互斥：选槽位清空搜索词。
    const input = wrapper.find('.search-bar__input').element as HTMLInputElement
    expect(input.value).toBe('')

    // 回到「全部」→ 恢复无过滤加载（分页重置回第 1 页）。
    await clickFilterChip('全部')
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(0, 1, null, undefined, 50)
  })

  it('debounces a typed search to q=word and cancels the active slot', async () => {
    vi.useFakeTimers()
    vi.mocked(filterSlotsApi.get).mockResolvedValue(FILTER_SLOTS)
    await mountFavorites([makeFavorite(1), makeFavorite(2)])

    await clickFilterChip('Artist')
    await wrapper.find('.search-bar__input').setValue('futa')
    // 防抖 400ms 内不触发请求。
    await vi.advanceTimersByTimeAsync(200)
    expect(favoriteApi.listFavorites).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(300)
    await flushPromises()

    // 输入搜索取消槽位 → LIKE 搜索（无 regex 参数）。
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(0, 1, 'futa', undefined, 50)
    const artistChip = wrapper
      .findAll('.filter-slot-bar__chip')
      .find((c) => c.text() === 'Artist')!
    expect(artistChip.classes()).not.toContain('filter-slot-bar__chip--active')
    vi.useRealTimers()
  })

  it('holds the debounced search while the IME composition is active (C1)', async () => {
    vi.useFakeTimers()
    await mountFavorites([makeFavorite(1)])
    const input = wrapper.find('.search-bar__input')

    // 拼音组合期间的中间态输入不触发请求（首屏 1 次）。
    await input.trigger('compositionstart')
    await input.setValue('futa')
    await vi.advanceTimersByTimeAsync(1000)
    expect(favoriteApi.listFavorites).toHaveBeenCalledTimes(1)

    // compositionend 后的最终选词照常防抖提交。
    await input.trigger('compositionend')
    await input.setValue('漫画')
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(0, 1, '漫画', undefined, 50)
    vi.useRealTimers()
  })

  it('clear button restores the unfiltered list', async () => {
    vi.useFakeTimers()
    await mountFavorites([makeFavorite(1)])
    await wrapper.find('.search-bar__input').setValue('futa')
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(0, 1, 'futa', undefined, 50)

    await wrapper.find('.search-bar__clear').trigger('click')
    await flushPromises()
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(0, 1, null, undefined, 50)
    vi.useRealTimers()
  })

  /* ---------------- 服务端分页（A4，usePagedList + 分页条） ---------------- */

  /** 按服务端分页语义切片的 mock：page 1 起始（收藏信封），pageSize 每页条数。 */
  function pagedServer(totalRows: number) {
    return async (
      _slot: unknown,
      page = 1,
      _q?: unknown,
      _regex?: unknown,
      pageSize = PAGE_SIZE,
    ): Promise<FavoriteListResponse> => {
      const start = (page - 1) * pageSize
      const count = Math.max(0, Math.min(pageSize, totalRows - start))
      return listResult(
        Array.from({ length: count }, (_, i) =>
          makeFavorite(start + i + 1, { title: `F ${start + i + 1}` }),
        ),
        { total: totalRows, currentPage: page, pageSize },
      )
    }
  }

  async function mountPaged(totalRows: number): Promise<void> {
    vi.mocked(favoriteApi.listFavorites).mockImplementation(pagedServer(totalRows))
    wrapper = mount(FavoriteView)
    await flushPromises()
    await flushPromises()
  }

  it('first screen loads page=1 only and shows the pagination bar (no full fetch)', async () => {
    await mountPaged(120)

    // 首屏：page=1&pageSize=50（默认档），整页替换渲染 50 行。
    expect(favoriteApi.listFavorites).toHaveBeenCalledTimes(1)
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(0, 1, null, undefined, 50)
    expect(wrapper.findAll('.app-list-row')).toHaveLength(50)
    expect(wrapper.text()).toContain('F 1')
    // 标题计数 = 服务端过滤后全集条数。
    expect(wrapper.find('.favorite-view__count').text()).toBe('120 galleries')
    // 分页条：第 1 / 3 页 · 120 条。
    const bar = wrapper.find('[data-testid="favorite-pagination"]')
    expect(bar.exists()).toBe(true)
    expect(bar.find('.pagination-bar__info').text()).toBe('第 1 / 3 页 · 120 条')
  })

  it('hides the pagination bar when total <= pageSize (Android 语义)', async () => {
    await mountFavorites([makeFavorite(1), makeFavorite(2)])
    expect(wrapper.find('[data-testid="favorite-pagination"]').exists()).toBe(false)
  })

  it('jumps to a page via the page buttons (whole-page replace)', async () => {
    await mountPaged(120)

    const bar = () => wrapper.find('[data-testid="favorite-pagination"]')
    await bar()
      .findAll('.pagination-bar__page')
      .find((b) => b.text() === '3')!
      .trigger('click')
    await flushPromises()
    await flushPromises()

    // 收藏信封 page 1 起——usePagedList 页码 3 直传 page=3。
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(0, 3, null, undefined, 50)
    // 最后一页只剩 20 条——整页替换（不再是追加语义）。
    expect(wrapper.findAll('.app-list-row')).toHaveLength(20)
    expect(wrapper.text()).toContain('F 120')
    expect(bar().find('.pagination-bar__info').text()).toBe('第 3 / 3 页 · 120 条')
    expect(bar().find('.pagination-bar__page--active').text()).toBe('3')
  })

  it('has no jump-to-page input（用户定案：跳页仅下载页）', async () => {
    await mountPaged(120)

    expect(wrapper.find('.pagination-bar__input').exists()).toBe(false)
    expect(wrapper.find('.pagination-bar__btn').exists()).toBe(false)
  })

  it('reloads page 1 with the selected page size (50/100/200 tiers)', async () => {
    await mountPaged(300)

    // 三档下拉（对齐下载/历史页档位），默认 50。
    const select = wrapper.find('.pagination-bar__select')
    expect(select.findAll('option').map((o) => o.text())).toEqual(['50', '100', '200'])

    await select.setValue('100')
    await flushPromises()
    await flushPromises()
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(0, 1, null, undefined, 100)
    expect(wrapper.findAll('.app-list-row')).toHaveLength(100)
    expect(wrapper.find('.pagination-bar__info').text()).toBe('第 1 / 3 页 · 300 条')

    await wrapper.find('.pagination-bar__select').setValue('200')
    await flushPromises()
    await flushPromises()
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(0, 1, null, undefined, 200)
    expect(wrapper.findAll('.app-list-row')).toHaveLength(200)
    expect(wrapper.find('.pagination-bar__info').text()).toBe('第 1 / 2 页 · 300 条')
  })

  it('pages with keyboard PageDown / PageUp (PC)', async () => {
    await mountPaged(120)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageDown', cancelable: true }))
    await flushPromises()
    await flushPromises()
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(0, 2, null, undefined, 50)
    expect(wrapper.find('.pagination-bar__info').text()).toBe('第 2 / 3 页 · 120 条')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageUp', cancelable: true }))
    await flushPromises()
    await flushPromises()
    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(0, 1, null, undefined, 50)
    expect(wrapper.find('.pagination-bar__info').text()).toBe('第 1 / 3 页 · 120 条')
  })

  it('keeps whole-page replace semantics when switching the folder chip mid-pagination', async () => {
    await mountPaged(120)

    // 翻到第 2 页后切收藏夹 → 回第 1 页整页替换（新 slot 参数）。
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageDown', cancelable: true }))
    await flushPromises()
    await flushPromises()
    await wrapper.findAll('.slot-bar__chip').find((c) => c.text() === 'Favorites 3')!.trigger('click')
    await flushPromises()
    await flushPromises()

    expect(favoriteApi.listFavorites).toHaveBeenLastCalledWith(3, 1, null, undefined, 50)
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

  it('names REGEX_INVALID when the backend rejects the filter pattern (F4)', async () => {
    vi.mocked(favoriteApi.listFavorites).mockRejectedValue(apiError('REGEX_INVALID'))
    wrapper = mount(FavoriteView)
    await flushPromises()
    await flushPromises()

    // 首屏失败（无内容）→ 错误态 + 专属文案（不再是泛化的 Failed to load）。
    expect(wrapper.find('[data-testid="content-state-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('正则无效')
    // Toast teleports to body — assert on document.
    expect(document.querySelector('.toast')?.textContent).toContain('正则无效')
  })

  it('keeps the generic error tip for non-REGEX failures (F4)', async () => {
    vi.mocked(favoriteApi.listFavorites).mockRejectedValue(new Error('boom'))
    wrapper = mount(FavoriteView)
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('[data-testid="content-state-error"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('正则无效')
    expect(document.querySelector('.toast')).toBeNull()
  })

  /* ---------------- KeepAlive 停用守卫（audit P2） ---------------- */

  describe('KeepAlive 停用守卫 (audit P2)', () => {
    /** A stand-in for another route view (uncached side of the KeepAlive). */
    const OtherView = defineComponent({ name: 'OtherView', render: () => h('div') })

    /** Mount FavoriteView inside a real <KeepAlive> so activated/deactivated fire. */
    async function mountCachedFavorites() {
      vi.mocked(favoriteApi.listFavorites).mockResolvedValue(listResult([makeFavorite(1)]))
      const current = shallowRef<Component>(FavoriteView)
      const Host = defineComponent({
        setup() {
          return () => h(KeepAlive, () => h(current.value))
        },
      })
      wrapper = mount(Host)
      await flushPromises()
      await flushPromises()
      return current
    }

    afterEach(() => {
      vi.useRealTimers()
    })

    it('deactivated instance drops the pending search debounce (no reload, no re-fire on return)', async () => {
      vi.useFakeTimers()
      const current = await mountCachedFavorites()
      expect(favoriteApi.listFavorites).toHaveBeenCalledTimes(1)

      // 输入搜索词（400ms 防抖时钟已排）后立即离开。
      await wrapper.find('.search-bar__input').setValue('futa')
      current.value = OtherView
      await flushPromises()

      // 停用态防抖时钟被作废——到点不再触发 /favorite/list。
      await vi.advanceTimersByTimeAsync(600)
      await flushPromises()
      expect(favoriteApi.listFavorites).toHaveBeenCalledTimes(1)

      // 重新激活也不补发（时钟已作废，等待新输入）。
      current.value = FavoriteView
      await flushPromises()
      expect(favoriteApi.listFavorites).toHaveBeenCalledTimes(1)
    })
  })
})
