import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { reactive } from 'vue'
import HomeView from '../HomeView.vue'
import SearchBar from '@/components/search/SearchBar.vue'
import FilterPanel from '@/components/search/FilterPanel.vue'
import { galleryApi } from '@/api/gallery'
import { authApi } from '@/api/auth'
import { siteApi } from '@/api/site'
import { preferencesApi } from '@/api/preferences'
import { usePreferencesStore } from '@/stores/preferences'
import { availability, markUnknown } from '@/stores/availability'
import { setPrivacyMaskEnabled } from '@/utils/privacyMask'
import type { Preferences } from '@/api/preferences'
import type { GalleryInfo, GalleryListResponse, TopListItem } from '@/types'

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
  },
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

/** Legacy localStorage key of the pre-preferences list mode (B-1 migration). */
const LIST_MODE_KEY = 'anotherviewer-webui:gallery-list-mode'

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

describe('HomeView (首页)', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    setActivePinia(createPinia())
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
    // Default: preferences resolve with the grid layout (the pre-migration
    // tests below can override per case).
    vi.mocked(preferencesApi.get).mockResolvedValue(makePrefs({ listMode: 'grid' }))
    vi.mocked(preferencesApi.update).mockResolvedValue(makePrefs({}))
    // 默认站点状态 UP：横幅不出现（每个用例可按需覆盖）。
    vi.mocked(siteApi.getAvailability).mockResolvedValue({ state: 'UP' })
    vi.mocked(siteApi.probeAvailability).mockResolvedValue({ state: 'UP' })
  })

  afterEach(() => {
    setPrivacyMaskEnabled(false)
    wrapper?.unmount()
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
    await mountHome([
      gallery({ gid: 1, title: 'Gallery One', titleJpn: '日本語一', pages: 10, category: 2 }),
      gallery({ gid: 2, title: 'Gallery Two', titleJpn: '', pages: 0, category: 2 }),
    ])

    const rows = wrapper.findAll('.app-list-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].find('.app-list-row__title').text()).toBe('Gallery One')
    // 日文标题作为副题（打码关闭时）。
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

  it('opens the gallery detail from the thumbnail click zone (A4)', async () => {
    await mountHome([gallery({ gid: 42, token: 'abc123' })])

    await wrapper.find('.app-list-row__thumb').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ path: '/gallery/42', query: { token: 'abc123' } })
  })

  it('opens the reader directly from the row body click zone (A4)', async () => {
    await mountHome([gallery({ gid: 7, token: 'tok7' })])

    await wrapper.find('.app-list-row').trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ path: '/reader/7', query: { token: 'tok7' } })
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
})

describe('HomeView — A4 服务端分页（分页条，usePagedList）', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    setActivePinia(createPinia())
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
    vi.mocked(preferencesApi.get).mockResolvedValue(makePrefs({ listMode: 'list' }))
    vi.mocked(preferencesApi.update).mockResolvedValue(makePrefs({}))
    vi.mocked(siteApi.getAvailability).mockResolvedValue({ state: 'UP' })
    vi.mocked(siteApi.probeAvailability).mockResolvedValue({ state: 'UP' })
  })

  afterEach(() => {
    setPrivacyMaskEnabled(false)
    wrapper?.unmount()
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

  it('jumps through the page input (钳制到页码窗口)', async () => {
    await mountPaged(120)

    const input = wrapper.find('.pagination-bar__input')
    await input.setValue(5)
    await wrapper.find('.pagination-bar__btn').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(galleryApi.search).toHaveBeenLastCalledWith(undefined, undefined, 4, 25, undefined)
    expect(wrapper.find('.pagination-bar__info').text()).toBe('第 5 / 5 页 · 120 条')
    // 末页只剩 20 条——整页替换（不再是追加语义）。
    expect(wrapper.findAll('.app-list-row')).toHaveLength(20)
    expect(wrapper.text()).toContain('G 120')
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

describe('HomeView (B-1 localStorage → preferences listMode migration)', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    setActivePinia(createPinia())
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
    vi.mocked(galleryApi.search).mockResolvedValue({ success: true, data: [gallery()], total: 1 })
    vi.mocked(authApi.ehSession).mockResolvedValue({
      signedIn: false,
      expired: false,
      gallerySite: 0,
      cookies: [],
    })
    vi.mocked(preferencesApi.update).mockResolvedValue(makePrefs({}))
    vi.mocked(siteApi.getAvailability).mockResolvedValue({ state: 'UP' })
    vi.mocked(siteApi.probeAvailability).mockResolvedValue({ state: 'UP' })
  })

  afterEach(() => {
    wrapper?.unmount()
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  async function mountHomeWithPrefs(general: Record<string, unknown>) {
    vi.mocked(preferencesApi.get).mockResolvedValue(makePrefs(general))
    wrapper = mount(HomeView)
    await flushPromises()
    return wrapper
  }

  it('writes the legacy grid value into preferences and clears localStorage', async () => {
    vi.useFakeTimers()
    localStorage.setItem(LIST_MODE_KEY, 'grid')
    await mountHomeWithPrefs({ listMode: 'list' }) // server value differs

    // Migration applied the legacy value to the store…
    expect(usePreferencesStore().prefs?.general.listMode).toBe('grid')
    // …cleared the legacy key…
    expect(localStorage.getItem(LIST_MODE_KEY)).toBeNull()
    // …and persists it through the preferences API (debounced PUT).
    await vi.advanceTimersByTimeAsync(600)
    expect(preferencesApi.update).toHaveBeenCalledTimes(1)
    expect(preferencesApi.update).toHaveBeenCalledWith({
      general: expect.objectContaining({ listMode: 'grid' }),
    })
  })

  it('migrates the legacy list value too', async () => {
    vi.useFakeTimers()
    localStorage.setItem(LIST_MODE_KEY, 'list')
    await mountHomeWithPrefs({ listMode: 'grid' })

    expect(usePreferencesStore().prefs?.general.listMode).toBe('list')
    expect(localStorage.getItem(LIST_MODE_KEY)).toBeNull()
  })

  it('normalizes an unrecognized legacy value to list', async () => {
    vi.useFakeTimers()
    localStorage.setItem(LIST_MODE_KEY, 'table')
    await mountHomeWithPrefs({ listMode: 'grid' })

    // Legacy semantics: anything that is not exactly "grid" was list mode.
    expect(usePreferencesStore().prefs?.general.listMode).toBe('list')
    expect(localStorage.getItem(LIST_MODE_KEY)).toBeNull()
  })

  it('leaves preferences untouched when no legacy value is stored', async () => {
    vi.useFakeTimers()
    await mountHomeWithPrefs({ listMode: 'list' })

    expect(usePreferencesStore().prefs?.general.listMode).toBe('list')
    await vi.advanceTimersByTimeAsync(600)
    expect(preferencesApi.update).not.toHaveBeenCalled()
  })

  it('migrates exactly once', async () => {
    vi.useFakeTimers()
    localStorage.setItem(LIST_MODE_KEY, 'grid')
    await mountHomeWithPrefs({ listMode: 'list' })
    await vi.advanceTimersByTimeAsync(600)

    // The key is gone; a later preferences change is not re-migrated.
    const store = usePreferencesStore()
    store.updateGeneral({ listMode: 'list' })
    await vi.advanceTimersByTimeAsync(600)
    expect(store.prefs?.general.listMode).toBe('list')
    expect(localStorage.getItem(LIST_MODE_KEY)).toBeNull()
  })
})

describe('HomeView — EH 熔断（plan-2026-08-30 §0）', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    // 迁移 describe 共用同一外层 beforeEach？不——此处建立独立前置。
    setActivePinia(createPinia())
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
    vi.mocked(preferencesApi.get).mockResolvedValue(makePrefs({ listMode: 'grid' }))
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
