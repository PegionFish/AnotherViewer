import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { KeepAlive, defineComponent, h, reactive, shallowRef, type Component } from 'vue'
import HomeView from '../HomeView.vue'
import SearchBar from '@/components/search/SearchBar.vue'
import FilterPanel from '@/components/search/FilterPanel.vue'
import { galleryApi } from '@/api/gallery'
import { commentApi } from '@/api/comment'
import { authApi } from '@/api/auth'
import { siteApi } from '@/api/site'
import { preferencesApi } from '@/api/preferences'
import { availability, markUnknown } from '@/stores/availability'
import { setPrivacyMaskEnabled } from '@/utils/privacyMask'
import type { Preferences } from '@/api/preferences'
import type { GalleryDetail, GalleryInfo, GalleryListResponse, TopListItem } from '@/types'

const { pushMock, replaceMock, routeMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  routeMock: { query: {} },
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  // Return a reactive wrapper so query mutations (via the cached proxy in
  // tests) re-trigger HomeView's feed watcher.
  useRoute: () => reactive(routeMock),
}))

vi.mock('@/api/gallery', () => ({
  galleryApi: {
    search: vi.fn(),
    feed: vi.fn(),
    getQuickSearches: vi.fn(),
    createQuickSearch: vi.fn(),
    getDetail: vi.fn(),
  },
}))

vi.mock('@/api/comment', () => ({
  commentApi: { listComments: vi.fn(), postComment: vi.fn(), voteComment: vi.fn() },
}))

vi.mock('@/api/auth', () => ({
  authApi: { ehSession: vi.fn() },
}))

vi.mock('@/api/preferences', () => ({
  preferencesApi: { get: vi.fn(), update: vi.fn() },
}))

vi.mock('@/api/site', () => ({
  siteApi: { getAvailability: vi.fn(), probeAvailability: vi.fn() },
}))

/**
 * `vi.mocked(galleryApi.feed)` resolves to the last (toplist) overload, so the
 * subscription/popular mocks are typed through this list-overload alias.
 */
const feedListMock = vi.mocked(
  galleryApi.feed as (
    mode: 'subscription' | 'popular',
    page?: number,
    pageSize?: number,
  ) => Promise<GalleryListResponse>,
)

/** Minimal Preferences fixture — schema keys optional (parallel-work keys). */
function makePrefs(general: Record<string, unknown>): Preferences {
  return { general } as unknown as Preferences
}

function gallery(overrides: Partial<GalleryInfo> = {}): GalleryInfo {
  return {
    gid: 1,
    token: 'abc123',
    title: 'Sample',
    titleJpn: '',
    thumb: '',
    category: 2,
    posted: '2026-01-01',
    uploader: '',
    rating: 4,
    rated: false,
    simpleLanguage: '',
    simpleTags: [],
    thumbWidth: 0,
    thumbHeight: 0,
    pages: 10,
    favoriteSlot: -1,
    favoriteName: '',
    ...overrides,
  }
}

function toplistItem(overrides: Partial<TopListItem> = {}): TopListItem {
  return {
    gid: 1,
    token: 'abc123',
    tag: 'parody:one piece',
    value: '1000',
    href: 'https://e-hentai.org/g/1/abc123/',
    ...overrides,
  }
}

/* ---------------------- 双栏断点桩（T3，平板对齐） ---------------------- */

type MqlListener = (event: MediaQueryListEvent) => void

/**
 * happy-dom 的 matchMedia 对 min-width 查询恒 true（默认视口 1024px），会把
 * 视图直接推进宽屏双栏。断点桩把判定钉死：默认窄屏（现状行为回归保护），
 * 双栏用例显式 stubMatchMedia(true)，setWide() 模拟跨 960px（change 派发）。
 */
let setWide: (matches: boolean) => void = () => {}

function stubMatchMedia(matches: boolean): void {
  const listeners = new Set<MqlListener>()
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches,
      media: query,
      addEventListener: (_type: string, cb: MqlListener) => {
        listeners.add(cb)
      },
      removeEventListener: (_type: string, cb: MqlListener) => {
        listeners.delete(cb)
      },
    })),
  )
  setWide = (next: boolean) => {
    for (const cb of [...listeners]) cb({ matches: next } as MediaQueryListEvent)
  }
}

describe('HomeView (首页)', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    setActivePinia(createPinia())
    stubMatchMedia(false)
    localStorage.clear()
    pushMock.mockClear()
    replaceMock.mockClear()
    routeMock.query = {}
    // 熔断单例（模块级）在 spec 间持久——重置为初始未知态。
    availability.state = null
    availability.downAt = null
    availability.lastReason = null
    availability.lastLoadedAt = null
    markUnknown()
    setPrivacyMaskEnabled(false)
    vi.mocked(galleryApi.getQuickSearches).mockResolvedValue({ success: true, data: [] })
    vi.mocked(authApi.ehSession).mockResolvedValue({
      signedIn: false,
      expired: false,
      gallerySite: 0,
      cookies: [],
    })
    // Default: preferences resolve with an empty general section (the
    // tests below can override per case).
    vi.mocked(preferencesApi.get).mockResolvedValue(makePrefs({}))
    vi.mocked(preferencesApi.update).mockResolvedValue(makePrefs({}))
    // 默认站点状态 UP：横幅不出现（每个用例可按需覆盖）。
    vi.mocked(siteApi.getAvailability).mockResolvedValue({ state: 'UP' })
    vi.mocked(siteApi.probeAvailability).mockResolvedValue({ state: 'UP' })
  })

  afterEach(() => {
    setPrivacyMaskEnabled(false)
    wrapper?.unmount()
    vi.unstubAllGlobals()
    vi.clearAllMocks()
  })

  async function mountHome(list: GalleryInfo[]) {
    vi.mocked(galleryApi.search).mockResolvedValue({ success: true, data: list, total: list.length })
    wrapper = mount(HomeView)
    await flushPromises()
    await flushPromises()
    return wrapper
  }

  function ctaButton(label: string) {
    return wrapper.findAll('button').find((b) => b.text() === label)
  }

  it('renders the guided CTA buttons when there is no gallery data (UX-07)', async () => {
    await mountHome([])

    expect(wrapper.find('[data-testid="content-state-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="content-state-empty"]').text()).toContain('还没有画廊数据')
    expect(ctaButton('去搜索')).toBeDefined()
    // Not authenticated → login CTA present.
    expect(ctaButton('登录')).toBeDefined()
  })

  it('navigates to /search when 去搜索 is tapped', async () => {
    await mountHome([])

    await ctaButton('去搜索')!.trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/search')
  })

  it('guides the EH session config to the merged settings page (A5-1)', async () => {
    await mountHome([])

    const link = wrapper.find('.home__empty-eh-link')
    expect(link.exists()).toBe(true)
    await link.trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/settings/server/eh')
  })

  it('hides the login CTA when already authenticated', async () => {
    localStorage.setItem('token', 'test-token')
    await mountHome([])

    expect(ctaButton('去搜索')).toBeDefined()
    expect(ctaButton('登录')).toBeUndefined()
  })

  it('hides the CTA buttons when galleries are present', async () => {
    await mountHome([gallery()])

    expect(wrapper.find('[data-testid="content-state-empty"]').exists()).toBe(false)
    expect(ctaButton('去搜索')).toBeUndefined()
  })

  /* ---------------- A4 单列密信息行（AppListRow，瀑布流出局） ---------------- */

  it('renders gallery rows as single-column AppListRow rows (A4 — no grid/waterfall)', async () => {
    // T2：showJpnTitle 是副题的偏好开关（协议默认 false）——要看到副题需显式开。
    vi.mocked(preferencesApi.get).mockResolvedValue(makePrefs({ showJpnTitle: true }))
    await mountHome([
      gallery({ gid: 1, title: 'Gallery One', titleJpn: '日本語一', pages: 10, category: 2 }),
      gallery({ gid: 2, title: 'Gallery Two', titleJpn: '', pages: 0, category: 2 }),
    ])

    const rows = wrapper.findAll('.app-list-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].find('.app-list-row__title').text()).toBe('Gallery One')
    // 日文标题作为副题（打码关闭 + showJpnTitle=true）。
    expect(rows[0].find('.app-list-row__subtitle').text()).toBe('日本語一')
    // 元信息行：分类 chip + 页数（pages ≤ 0 不渲染页数角标）。
    expect(rows[0].find('.app-list-row__meta .category-chip').exists()).toBe(true)
    expect(rows[0].find('.home__row-pages').text()).toBe('10P')
    expect(rows[1].find('.home__row-pages').exists()).toBe(false)
    // 旧瀑布流/网格形态（GalleryList/GridCard）退场。
    expect(wrapper.find('.gallery-grid__cell').exists()).toBe(false)
    expect(wrapper.find('.gallery-list__row').exists()).toBe(false)
    expect(wrapper.find('.app-card').exists()).toBe(false)
  })

  it('routes row titles through maskedTitle — masking on shows only #<gid> (privacy red line)', async () => {
    setPrivacyMaskEnabled(true)
    await mountHome([gallery({ gid: 7, title: 'Secret Title', titleJpn: '秘密のタイトル' })])

    const row = wrapper.find('.app-list-row')
    expect(row.find('.app-list-row__title').text()).toBe('#7')
    // 打码开启时日文副题一并隐藏（同 GalleryCard 的 !privacyMaskEnabled 守卫）。
    expect(row.find('.app-list-row__subtitle').exists()).toBe(false)
  })

  it('hides the jpn subtitle once prefs load without showJpnTitle=true (T2 — protocol default false)', async () => {
    // beforeEach 默认 makePrefs({})：加载完成、无该键 → 与协议默认 false 同判（隐藏）。
    await mountHome([gallery({ gid: 1, title: 'Gallery One', titleJpn: '日本語一' })])
    expect(wrapper.find('.app-list-row__subtitle').exists()).toBe(false)
  })

  it('shows the jpn subtitle while prefs are still loading (anti-flash fallback, T2)', async () => {
    vi.mocked(preferencesApi.get).mockReturnValue(new Promise(() => {})) // never settles
    await mountHome([gallery({ gid: 1, title: 'Gallery One', titleJpn: '日本語一' })])
    expect(wrapper.find('.app-list-row__subtitle').text()).toBe('日本語一')
  })

  it('opens the gallery detail from the thumbnail click zone (A4)', async () => {
    await mountHome([gallery({ gid: 42, token: 'abc123' })])

    await wrapper.find('.app-list-row__thumb').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ path: '/gallery/42', query: { token: 'abc123' } })
  })

  it('opens the gallery detail from the row body click zone（用户定案：主体直达阅读仅下载页）', async () => {
    await mountHome([gallery({ gid: 7, token: 'tok7' })])

    await wrapper.find('.app-list-row').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ path: '/gallery/7', query: { token: 'tok7' } })
  })

  /* -------------------------------- feed mode ------------------------------ */

  it('loads the popular feed through galleryApi.feed when ?feed=popular', async () => {
    routeMock.query = { feed: 'popular' }
    feedListMock.mockResolvedValue({ success: true, data: [gallery()], total: 1 })
    wrapper = mount(HomeView)
    await flushPromises()
    await flushPromises()

    expect(galleryApi.feed).toHaveBeenCalledWith('popular', 0, 25)
    expect(galleryApi.search).not.toHaveBeenCalled()
    expect(wrapper.find('.app-list-row').exists()).toBe(true)
  })

  it('loads the subscription feed when ?feed=subscription', async () => {
    routeMock.query = { feed: 'subscription' }
    feedListMock.mockResolvedValue({ success: true, data: [gallery()], total: 1 })
    wrapper = mount(HomeView)
    await flushPromises()
    await flushPromises()

    expect(galleryApi.feed).toHaveBeenCalledWith('subscription', 0, 25)
    expect(galleryApi.search).not.toHaveBeenCalled()
  })

  it('renders ranked toplist rows when ?feed=toplist', async () => {
    routeMock.query = { feed: 'toplist' }
    vi.mocked(galleryApi.feed).mockResolvedValue({
      success: true,
      data: [
        toplistItem({ tag: 'parody:one piece', value: '999' }),
        toplistItem({ gid: 2, token: 'def456', tag: 'language:chinese', value: '42' }),
      ],
      total: 2,
    })
    wrapper = mount(HomeView)
    await flushPromises()
    await flushPromises()

    expect(galleryApi.feed).toHaveBeenCalledWith('toplist', 0, 25)
    expect(galleryApi.search).not.toHaveBeenCalled()
    const rows = wrapper.findAll('[data-testid^="toplist-row-"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('parody:one piece')
    expect(rows[0].text()).toContain('999')
    expect(rows[1].text()).toContain('language:chinese')
    // The gallery list is not rendered in toplist mode.
    expect(wrapper.find('.app-list-row').exists()).toBe(false)
    // toplist 无上游分页（total=返回行数）——分页条不渲染。
    expect(wrapper.find('[data-testid="home-pagination"]').exists()).toBe(false)
  })

  it('keeps the search path when no feed query is present', async () => {
    vi.mocked(galleryApi.search).mockResolvedValue({ success: true, data: [gallery()], total: 1 })
    wrapper = mount(HomeView)
    await flushPromises()

    expect(galleryApi.search).toHaveBeenCalled()
    expect(galleryApi.feed).not.toHaveBeenCalled()
  })

  it('reloads page 1 with the new mode when the feed query changes', async () => {
    routeMock.query = { feed: 'subscription' }
    feedListMock.mockResolvedValue({ success: true, data: [gallery()], total: 1 })
    wrapper = mount(HomeView)
    await flushPromises()
    await flushPromises()
    expect(galleryApi.feed).toHaveBeenCalledWith('subscription', 0, 25)

    // Mutate through Vue's cached proxy so the query watcher re-fires
    // (raw mutation of routeMock would bypass reactivity).
    reactive(routeMock).query = { feed: 'toplist' }
    await flushPromises()
    await flushPromises()
    expect(galleryApi.feed).toHaveBeenLastCalledWith('toplist', 0, 25)
  })

  it('leaves the feed through ?keyword= when searching on a feed page (C2)', async () => {
    routeMock.query = { feed: 'popular' }
    feedListMock.mockResolvedValue({ success: true, data: [gallery()], total: 1 })
    wrapper = mount(HomeView)
    await flushPromises()

    // Frozen feed 分支不拿关键词发请求——搜索词改为 replace 到 `/?keyword=`
    // 深链形态（离开 feed、意图可见，由 keyword watcher 执行搜索）。
    wrapper.findComponent(SearchBar).vm.$emit('search', 'mahua')
    await flushPromises()
    expect(replaceMock).toHaveBeenCalledWith({ path: '/', query: { keyword: 'mahua' } })
    expect(galleryApi.search).not.toHaveBeenCalled()
  })

  it('runs the search when mounted with a ?keyword= deep link (C2)', async () => {
    routeMock.query = { keyword: 'artist:someone' }
    vi.mocked(galleryApi.search).mockResolvedValue({ success: true, data: [gallery()], total: 1 })
    wrapper = mount(HomeView)
    await flushPromises()

    expect(galleryApi.search).toHaveBeenCalledWith('artist:someone', undefined, 0, 25, undefined)
    expect(galleryApi.feed).not.toHaveBeenCalled()
  })

  it('re-runs the search when route.query.keyword changes (C2 — detail tag links)', async () => {
    vi.mocked(galleryApi.search).mockResolvedValue({ success: true, data: [], total: 0 })
    wrapper = mount(HomeView)
    await flushPromises()
    vi.mocked(galleryApi.search).mockClear()

    reactive(routeMock).query = { keyword: 'female:big' }
    await flushPromises()
    expect(galleryApi.search).toHaveBeenCalledWith('female:big', undefined, 0, 25, undefined)
  })

  it('debounces rapid filter edits into a single filtered search (C6)', async () => {
    vi.useFakeTimers()
    await mountHome([gallery()])
    vi.mocked(galleryApi.search).mockClear()

    const panel = wrapper.findComponent(FilterPanel)
    panel.vm.$emit('update:filters', { sort: 2 })
    panel.vm.$emit('update:filters', { sort: 3 })
    await vi.advanceTimersByTimeAsync(400)
    expect(galleryApi.search).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(200)
    await flushPromises()
    expect(galleryApi.search).toHaveBeenCalledTimes(1)
    // 筛选参数随请求透传（A4：仅形态切换，参数语义不变），回第 1 页。
    expect(galleryApi.search).toHaveBeenLastCalledWith(undefined, undefined, 0, 25, { sort: 3 })
    vi.useRealTimers()
  })

  it('saves a quick search from the FilterPanel action (C3 wiring)', async () => {
    vi.mocked(galleryApi.createQuickSearch).mockResolvedValue({
      id: 7,
      name: 'My preset',
      mode: 0,
      category: 0,
      keyword: '',
      advanceSearch: 0,
      minRating: 0,
      pageFrom: 0,
      pageTo: 0,
    })
    await mountHome([gallery()])

    wrapper.findComponent(FilterPanel).vm.$emit('save-quick-search')
    await flushPromises()
    // Save dialog teleports to body.
    const scrim = document.querySelector('.dialog-scrim')
    expect(scrim).not.toBeNull()
    await new DOMWrapper(scrim!.querySelector('.dialog__input')!).setValue('My preset')
    await new DOMWrapper(scrim!.querySelector('.dialog__btn--primary')!).trigger('click')
    await flushPromises()

    expect(galleryApi.createQuickSearch).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'My preset', keyword: '', mode: 0 }),
    )
    // 成功后对话框关闭。
    expect(document.querySelector('.dialog-scrim')).toBeNull()
  })

  /* ---------------- KeepAlive 停用守卫（audit P2） ---------------- */

  describe('KeepAlive 停用守卫 (audit P2)', () => {
    /** A stand-in for another route view (uncached side of the KeepAlive). */
    const OtherView = defineComponent({ name: 'OtherView', render: () => h('div') })

    /** Mount HomeView inside a real <KeepAlive> so activated/deactivated fire. */
    async function mountCachedHome() {
      vi.mocked(galleryApi.search).mockResolvedValue({ success: true, data: [gallery()], total: 1 })
      const current = shallowRef<Component>(HomeView)
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

    it('deactivated instance: Escape cannot close the save dialog and the filter debounce is dropped', async () => {
      vi.useFakeTimers()
      const current = await mountCachedHome()
      vi.mocked(galleryApi.search).mockClear()

      // 打开「保存快速搜索」对话框 + 勾选筛选（500ms 防抖时钟已排）。
      wrapper.findComponent(FilterPanel).vm.$emit('save-quick-search')
      wrapper.findComponent(FilterPanel).vm.$emit('update:filters', { sort: 2 })
      await flushPromises()

      // 离开本视图 → KeepAlive 停用（实例仍被缓存）。
      current.value = OtherView
      await flushPromises()

      // 停用态 Escape 不应误关后台实例的对话框…
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
      // …排队的筛选防抖时钟被作废，不再触发搜索。
      await vi.advanceTimersByTimeAsync(700)
      await flushPromises()
      expect(galleryApi.search).not.toHaveBeenCalled()

      // 回到本视图：对话框仍是打开的（后台 Escape 没有关掉它）。
      current.value = HomeView
      await flushPromises()
      await flushPromises()
      expect(document.querySelector('.dialog-scrim')).not.toBeNull()
    })
  })
})

describe('HomeView — A4 服务端分页（分页条，usePagedList）', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    setActivePinia(createPinia())
    stubMatchMedia(false)
    localStorage.clear()
    pushMock.mockClear()
    routeMock.query = {}
    availability.state = null
    availability.downAt = null
    availability.lastReason = null
    availability.lastLoadedAt = null
    markUnknown()
    setPrivacyMaskEnabled(false)
    vi.mocked(galleryApi.getQuickSearches).mockResolvedValue({ success: true, data: [] })
    vi.mocked(authApi.ehSession).mockResolvedValue({
      signedIn: false,
      expired: false,
      gallerySite: 0,
      cookies: [],
    })
    vi.mocked(preferencesApi.get).mockResolvedValue(makePrefs({}))
    vi.mocked(preferencesApi.update).mockResolvedValue(makePrefs({}))
    vi.mocked(siteApi.getAvailability).mockResolvedValue({ state: 'UP' })
    vi.mocked(siteApi.probeAvailability).mockResolvedValue({ state: 'UP' })
  })

  afterEach(() => {
    setPrivacyMaskEnabled(false)
    wrapper?.unmount()
    vi.unstubAllGlobals()
    vi.clearAllMocks()
  })

  /**
   * 按上游分页语义切片的 search mock：`page` 即 0 起 EH 页索引（视图把
   * usePagedList 的 1 起页码减 1 直传），每页固定 25 条，total = 全集数。
   */
  function pagedSearch(totalRows: number) {
    return async (
      _kw?: string,
      _cat?: number,
      page = 0,
      pageSize = 25,
    ): Promise<GalleryListResponse> => {
      const start = page * pageSize
      const count = Math.max(0, Math.min(pageSize, totalRows - start))
      return {
        success: true,
        data: Array.from({ length: count }, (_, i) =>
          gallery({ gid: start + i + 1, title: `G ${start + i + 1}` }),
        ),
        total: totalRows,
      }
    }
  }

  async function mountPaged(totalRows: number): Promise<void> {
    vi.mocked(galleryApi.search).mockImplementation(pagedSearch(totalRows))
    wrapper = mount(HomeView)
    await flushPromises()
    await flushPromises()
  }

  it('first screen loads only page 1 and shows the pagination bar (no full fetch)', async () => {
    await mountPaged(120)

    // 首屏：上游 0 起 EH 页索引 0、25 条/页，整页替换渲染。
    expect(galleryApi.search).toHaveBeenCalledTimes(1)
    expect(galleryApi.search).toHaveBeenLastCalledWith(undefined, undefined, 0, 25, undefined)
    expect(wrapper.findAll('.app-list-row')).toHaveLength(25)
    expect(wrapper.text()).toContain('G 1')
    // 第 2 页内容从未请求（服务端分页替代无限滚动/虚拟窗口）。
    expect(wrapper.text()).not.toContain('G 26')
    // 分页条：第 1 / 5 页 · 120 条。
    const bar = wrapper.find('[data-testid="home-pagination"]')
    expect(bar.exists()).toBe(true)
    expect(bar.find('.pagination-bar__info').text()).toBe('第 1 / 5 页 · 120 条')
  })

  it('hides the pagination bar when total <= pageSize (Android 语义)', async () => {
    vi.mocked(galleryApi.search).mockResolvedValue({
      success: true,
      data: [gallery()],
      total: 1,
    })
    wrapper = mount(HomeView)
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('[data-testid="home-pagination"]').exists()).toBe(false)
  })

  it('jumps to a page via the page buttons (whole-page replace)', async () => {
    await mountPaged(120)

    const bar = () => wrapper.find('[data-testid="home-pagination"]')
    await bar()
      .findAll('.pagination-bar__page')
      .find((b) => b.text() === '2')!
      .trigger('click')
    await flushPromises()
    await flushPromises()

    // usePagedList 页码 2 → 上游 0 起 EH 页索引 1。
    expect(galleryApi.search).toHaveBeenLastCalledWith(undefined, undefined, 1, 25, undefined)
    expect(wrapper.findAll('.app-list-row')).toHaveLength(25)
    expect(wrapper.text()).toContain('G 26')
    expect(bar().find('.pagination-bar__info').text()).toBe('第 2 / 5 页 · 120 条')
    expect(bar().find('.pagination-bar__page--active').text()).toBe('2')
  })

  it('has no jump-to-page input（用户定案：跳页仅下载页，站点列表无目标语义）', async () => {
    await mountPaged(120)

    expect(wrapper.find('.pagination-bar__input').exists()).toBe(false)
    expect(wrapper.find('.pagination-bar__btn').exists()).toBe(false)
    // 页码窗口/前后页保留：页 2 可直达。
    const bar = () => wrapper.find('[data-testid="home-pagination"]')
    await bar()
      .findAll('.pagination-bar__page')
      .find((b) => b.text() === '2')!
      .trigger('click')
    await flushPromises()
    await flushPromises()
    expect(galleryApi.search).toHaveBeenLastCalledWith(undefined, undefined, 1, 25, undefined)
    expect(bar().find('.pagination-bar__page--active').text()).toBe('2')
  })

  it('pages with keyboard PageDown / PageUp (PC)', async () => {
    await mountPaged(120)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageDown', cancelable: true }))
    await flushPromises()
    await flushPromises()
    expect(galleryApi.search).toHaveBeenLastCalledWith(undefined, undefined, 1, 25, undefined)
    expect(wrapper.find('.pagination-bar__info').text()).toBe('第 2 / 5 页 · 120 条')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageUp', cancelable: true }))
    await flushPromises()
    await flushPromises()
    expect(galleryApi.search).toHaveBeenLastCalledWith(undefined, undefined, 0, 25, undefined)
    expect(wrapper.find('.pagination-bar__info').text()).toBe('第 1 / 5 页 · 120 条')
  })
})

describe('HomeView — EH 熔断（plan-2026-08-30 §0）', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    // 迁移 describe 共用同一外层 beforeEach？不——此处建立独立前置。
    setActivePinia(createPinia())
    stubMatchMedia(false)
    localStorage.clear()
    pushMock.mockClear()
    routeMock.query = {}
    availability.state = null
    availability.downAt = null
    availability.lastReason = null
    availability.lastLoadedAt = null
    markUnknown()
    setPrivacyMaskEnabled(false)
    vi.mocked(galleryApi.getQuickSearches).mockResolvedValue({ success: true, data: [] })
    vi.mocked(authApi.ehSession).mockResolvedValue({
      signedIn: false,
      expired: false,
      gallerySite: 0,
      cookies: [],
    })
    vi.mocked(preferencesApi.get).mockResolvedValue(makePrefs({}))
    vi.mocked(preferencesApi.update).mockResolvedValue(makePrefs({}))
    vi.mocked(siteApi.getAvailability).mockResolvedValue({ state: 'UNKNOWN' })
    vi.mocked(siteApi.probeAvailability).mockResolvedValue({ state: 'UP' })
  })

  afterEach(() => {
    wrapper?.unmount()
    availability.state = null
    availability.downAt = null
    availability.lastReason = null
    availability.lastLoadedAt = null
    vi.unstubAllGlobals()
    vi.clearAllMocks()
  })

  /** 当 EH 不可达时搜索以 404 信封形式快速失败（服务端已短路）。 */
  function ehError() {
    return {
      response: {
        status: 404,
        data: {
          error: {
            code: 'EH_UNAVAILABLE',
            message: 'EH 平台当前不可达，仅显示本地内容',
          },
        },
      },
    }
  }

  it('defers to the local-only tip when the feed fails with EH_UNAVAILABLE', async () => {
    vi.mocked(galleryApi.search).mockRejectedValue(ehError())
    wrapper = mount(HomeView)
    await flushPromises()

    expect(wrapper.find('[data-testid="content-state-error"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('EH 平台当前不可达，仅显示本地内容')
    expect(wrapper.text()).not.toContain('加载失败，请稍后重试')
  })

  it('shows the banner at the top when the availability state is down', async () => {
    vi.mocked(galleryApi.search).mockResolvedValue({ success: true, data: [gallery()], total: 1 })
    // 服务器自身判定 DOWN。
    vi.mocked(siteApi.getAvailability).mockResolvedValue({
      state: 'DOWN',
      downAt: 9,
      lastReason: 'probe failed',
    })
    wrapper = mount(HomeView)
    await flushPromises()
    await flushPromises()

    const banner = wrapper.find('[data-testid="availability-banner"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('重新连接')
    // 内容区不受影响（本地内容照常）。
    expect(wrapper.find('[data-testid="content-state-content"]').exists()).toBe(true)
  })

  it('hides the banner and refreshes the list after a successful reconnect', async () => {
    vi.mocked(galleryApi.search).mockResolvedValue({ success: true, data: [gallery()], total: 1 })
    vi.mocked(siteApi.getAvailability).mockResolvedValue({ state: 'DOWN' })
    wrapper = mount(HomeView)
    await flushPromises()
    await flushPromises()
    expect(wrapper.find('[data-testid="availability-banner"]').exists()).toBe(true)

    await wrapper.find('[data-testid="availability-banner"] button').trigger('click')
    await flushPromises()
    await flushPromises()

    // 探测成功 → 状态 UP → 横幅消失 + 父视图刷新（静默重载第 1 页）。
    expect(wrapper.find('[data-testid="availability-banner"]').exists()).toBe(false)
    expect(galleryApi.search).toHaveBeenCalledTimes(2)
  })
})

describe('HomeView — 宽屏双栏（T3 平板对齐，2026-09-20 定案）', () => {
  let wrapper: VueWrapper

  /** 详情面板 fixture（GalleryDetailPane 契约字段）。 */
  function detailFixture(overrides: Partial<GalleryDetail> = {}): GalleryDetail {
    return {
      gid: 42,
      token: 'tok42',
      title: 'Detail Gallery',
      titleJpn: '',
      thumb: '',
      category: 2,
      posted: '',
      uploader: '',
      rating: 4,
      rated: false,
      simpleLanguage: '',
      simpleTags: [],
      thumbWidth: 0,
      thumbHeight: 0,
      pages: 10,
      favoriteSlot: -2,
      favoriteName: '',
      tags: [],
      imageUrl: '',
      ...overrides,
    }
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    stubMatchMedia(true)
    localStorage.clear()
    pushMock.mockClear()
    routeMock.query = {}
    availability.state = null
    availability.downAt = null
    availability.lastReason = null
    availability.lastLoadedAt = null
    markUnknown()
    setPrivacyMaskEnabled(false)
    vi.mocked(galleryApi.getQuickSearches).mockResolvedValue({ success: true, data: [] })
    vi.mocked(authApi.ehSession).mockResolvedValue({
      signedIn: false,
      expired: false,
      gallerySite: 0,
      cookies: [],
    })
    vi.mocked(preferencesApi.get).mockResolvedValue(makePrefs({}))
    vi.mocked(preferencesApi.update).mockResolvedValue(makePrefs({}))
    vi.mocked(siteApi.getAvailability).mockResolvedValue({ state: 'UP' })
    vi.mocked(siteApi.probeAvailability).mockResolvedValue({ state: 'UP' })
    vi.mocked(galleryApi.getDetail).mockResolvedValue(detailFixture())
    vi.mocked(commentApi.listComments).mockResolvedValue({ comments: [] })
  })

  afterEach(() => {
    setPrivacyMaskEnabled(false)
    wrapper?.unmount()
    availability.state = null
    availability.downAt = null
    availability.lastReason = null
    availability.lastLoadedAt = null
    vi.unstubAllGlobals()
    vi.clearAllMocks()
  })

  async function mountWide(list: GalleryInfo[]) {
    vi.mocked(galleryApi.search).mockResolvedValue({
      success: true,
      data: list,
      total: list.length,
    })
    wrapper = mount(HomeView)
    await flushPromises()
    await flushPromises()
    return wrapper
  }

  it('宽屏：无选中时右栏渲染占位符，不自动选中任何条目', async () => {
    await mountWide([gallery({ gid: 42 })])

    expect(wrapper.find('[data-testid="two-pane--wide"], .two-pane--wide').exists()).toBe(true)
    expect(wrapper.find('[data-testid="two-pane-placeholder"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="gallery-detail-pane"]').exists()).toBe(false)
    // 不自动选中：详情接口零调用。
    expect(galleryApi.getDetail).not.toHaveBeenCalled()
  })

  it('宽屏：点选列表项 → 右栏原位加载详情，路由不变', async () => {
    await mountWide([gallery({ gid: 42, token: 'abc123' })])

    await wrapper.find('.app-list-row__thumb').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(pushMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="two-pane-placeholder"]').exists()).toBe(false)
    expect(wrapper.find('.detail-header__title').text()).toBe('Detail Gallery')
    // 行内 token 透传给 getDetail。
    expect(galleryApi.getDetail).toHaveBeenCalledWith(42, 'abc123')
  })

  it('宽屏：主体点击分区同样选中（不跳阅读器）', async () => {
    await mountWide([gallery({ gid: 7, token: 'tok7' })])

    await wrapper.find('.app-list-row').trigger('click')
    await flushPromises()

    expect(pushMock).not.toHaveBeenCalled()
    expect(galleryApi.getDetail).toHaveBeenCalledWith(7, 'tok7')
  })

  it('宽屏：再点同项不重载详情（无闪烁）', async () => {
    await mountWide([gallery({ gid: 42, token: 'abc123' })])

    await wrapper.find('.app-list-row__thumb').trigger('click')
    await flushPromises()
    expect(galleryApi.getDetail).toHaveBeenCalledTimes(1)

    await wrapper.find('.app-list-row__thumb').trigger('click')
    await flushPromises()
    // gid 未变 → GalleryDetailPane 的 watch 不触发 → 不重新加载。
    expect(galleryApi.getDetail).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.detail-header__title').exists()).toBe(true)
  })

  it('宽屏：Esc 先清除选中回到占位符（返回语义第一段）', async () => {
    await mountWide([gallery({ gid: 42, token: 'abc123' })])
    await wrapper.find('.app-list-row__thumb').trigger('click')
    await flushPromises()
    expect(wrapper.find('.detail-header__title').exists()).toBe(true)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()

    expect(wrapper.find('[data-testid="two-pane-placeholder"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="gallery-detail-pane"]').exists()).toBe(false)
    // 清选中不是导航。
    expect(pushMock).not.toHaveBeenCalled()
  })

  it('宽屏：Esc 在输入框聚焦时不劫持（搜索词输入不受影响）', async () => {
    await mountWide([gallery({ gid: 42 })])
    await wrapper.find('.app-list-row__thumb').trigger('click')
    await flushPromises()

    const input = document.createElement('input')
    document.body.appendChild(input)
    input.focus()
    // 事件在可编辑元素上派发（冒泡到 window）——event.target 才是 input。
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    input.remove()
    await flushPromises()

    expect(wrapper.find('.detail-header__title').exists()).toBe(true)
  })

  it('宽屏：右栏详情面板返回箭头 = 清除选中回到占位符', async () => {
    await mountWide([gallery({ gid: 42, token: 'abc123' })])
    await wrapper.find('.app-list-row__thumb').trigger('click')
    await flushPromises()

    await wrapper.find('.detail-header__back').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="two-pane-placeholder"]').exists()).toBe(true)
    expect(pushMock).not.toHaveBeenCalled()
  })

  it('跨 960px 阈值来回：双栏类名切换，选中态与右栏 DOM 保留', async () => {
    await mountWide([gallery({ gid: 42, token: 'abc123' })])
    await wrapper.find('.app-list-row__thumb').trigger('click')
    await flushPromises()
    expect(galleryApi.getDetail).toHaveBeenCalledTimes(1)

    // 变窄：右栏退役（类名消失），但选中与面板 DOM 不销毁。
    setWide(false)
    await flushPromises()
    expect(wrapper.find('.two-pane--wide').exists()).toBe(false)
    expect(wrapper.find('[data-testid="gallery-detail-pane"]').exists()).toBe(true)

    // 变宽：右栏原样恢复，详情不重新加载。
    setWide(true)
    await flushPromises()
    expect(wrapper.find('.two-pane--wide').exists()).toBe(true)
    expect(wrapper.find('.detail-header__title').text()).toBe('Detail Gallery')
    expect(galleryApi.getDetail).toHaveBeenCalledTimes(1)
  })

  it('窄屏（跨阈值后）：点击列表项仍是整页跳转（现状行为）', async () => {
    await mountWide([gallery({ gid: 42, token: 'abc123' })])
    setWide(false)
    await flushPromises()

    await wrapper.find('.app-list-row__thumb').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ path: '/gallery/42', query: { token: 'abc123' } })
  })
})
