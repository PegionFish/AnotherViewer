import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { usePagedList, type PagedListPage, type UsePagedListOptions } from '../usePagedList'

type Row = { id: number }

/** Resolve/reject 控制在测试手里的假分页接口（竞态用）。 */
function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

/**
 * 在组件 setup 中运行 composable（keyboardPaging 的 window 监听需要
 * mounted/unmounted 上下文）。
 */
function mountPagedList<T extends Row>(
  overrides: Partial<UsePagedListOptions<T>> = {},
  fetchPage: UsePagedListOptions<T>['fetchPage'] = async (page, size) =>
    ({
      items: Array.from({ length: Math.min(size, Math.max(0, 250 - (page - 1) * size)) }, (_, i) => ({
        id: (page - 1) * size + i + 1,
      })),
      total: 250,
    }) as PagedListPage<T>,
) {
  let api!: ReturnType<typeof usePagedList<T>>
  const onLoadStart = vi.fn()
  const onSuccess = vi.fn()
  const onError = vi.fn()
  const onPageSizeChange = vi.fn()
  const wrapper: VueWrapper = mount(
    defineComponent({
      setup() {
        api = usePagedList<T>({
          fetchPage,
          pageSizes: [50, 100, 200],
          initialPageSize: 50,
          fallbackPageSize: 50,
          onLoadStart,
          onSuccess,
          onError,
          onPageSizeChange,
          keyboardPaging: true,
          ...overrides,
        })
        return () => h('div')
      },
    }),
  )
  return { wrapper, api: () => api, onLoadStart, onSuccess, onError, onPageSizeChange }
}

describe('usePagedList（服务端分页状态机，W3-C1）', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
  })

  it('loads a page through fetchPage with the (page, pageSize) contract', async () => {
    const fetchPage = vi.fn(async (_page: number, _size: number): Promise<PagedListPage<Row>> => ({
      items: [{ id: 1 }, { id: 2 }],
      total: 250,
    }))
    const { wrapper: w, api, onSuccess } = mountPagedList({}, fetchPage)
    wrapper = w

    // 不自动加载：宿主（onMounted 等）自行调 load()。
    expect(fetchPage).not.toHaveBeenCalled()
    await api().load()

    // offset/limit 契约消费方据 (page, size) 自行换算：offset=(1-1)*50=0。
    expect(fetchPage).toHaveBeenCalledWith(1, 50)
    expect(api().items.value.map((r) => r.id)).toEqual([1, 2])
    expect(api().total.value).toBe(250)
    expect(api().currentPage.value).toBe(1)
    expect(api().totalPages.value).toBe(5)
    expect(api().loading.value).toBe(false)
    expect(onSuccess).toHaveBeenCalledTimes(1)
  })

  it('adapts to the page/pageSize contract without offset math (历史/收藏形态)', async () => {
    // 历史/收藏信封直接吃 page/pageSize——fetchPage 原样透传即可。
    const fetchPage = vi.fn(async (page: number, size: number): Promise<PagedListPage<Row>> => ({
      items: [{ id: page * 1000 + size }],
      total: size * 3,
    }))
    const { wrapper: w, api } = mountPagedList(
      { pageSizes: [25, 50, 100], initialPageSize: 25 },
      fetchPage,
    )
    wrapper = w
    await api().load(2)
    expect(fetchPage).toHaveBeenCalledWith(2, 25)
    expect(api().currentPage.value).toBe(2)
  })

  it('falls back to fallbackPageSize when the initial size is off-tier', async () => {
    const fetchPage = vi.fn(async (_page: number, _size: number): Promise<PagedListPage<Row>> => ({
      items: [],
      total: 0,
    }))
    const { wrapper: w, api } = mountPagedList(
      { initialPageSize: 300, fallbackPageSize: 50 },
      fetchPage,
    )
    wrapper = w
    await api().load()
    expect(api().pageSize.value).toBe(50)
    expect(fetchPage).toHaveBeenCalledWith(1, 50)
  })

  it('silent loads skip onLoadStart; regular loads fire it (宿主自管忙态)', async () => {
    const { wrapper: w, api, onLoadStart } = mountPagedList()
    wrapper = w

    await api().load(1, { silent: true })
    expect(onLoadStart).not.toHaveBeenCalled()

    await api().load(1)
    expect(onLoadStart).toHaveBeenCalledWith(1)
  })

  it('drops stale responses (fast filter switches) — 竞态守卫', async () => {
    const slow = deferred<PagedListPage<Row>>()
    const fast = deferred<PagedListPage<Row>>()
    const gates = [slow, fast]
    let call = 0
    const fetchPage = vi.fn(async (): Promise<PagedListPage<Row>> => gates[call++]!.promise)
    const { wrapper: w, api, onSuccess, onError } = mountPagedList({}, fetchPage)
    wrapper = w

    // 连续两次加载（模拟快速切标签）：第一次慢、第二次快。
    const first = api().load()
    const second = api().load()
    fast.resolve({ items: [{ id: 99 }], total: 250 })
    await second
    slow.resolve({ items: [{ id: 1 }], total: 9999 })
    await first

    // stale 首包被整包丢弃：items/total 保持第二包，onSuccess 只记非 stale。
    expect(api().items.value.map((r) => r.id)).toEqual([99])
    expect(api().total.value).toBe(250)
    expect(api().currentPage.value).toBe(1)
    expect(onSuccess).toHaveBeenCalledTimes(1)
    expect(onError).not.toHaveBeenCalled()
  })

  it('drops stale errors as well', async () => {
    const slow = deferred<PagedListPage<Row>>()
    const fast = deferred<PagedListPage<Row>>()
    const gates = [slow, fast]
    let call = 0
    const fetchPage = vi.fn(async (): Promise<PagedListPage<Row>> => gates[call++]!.promise)
    const { wrapper: w, api, onError } = mountPagedList({}, fetchPage)
    wrapper = w

    const first = api().load()
    const second = api().load()
    fast.resolve({ items: [{ id: 99 }], total: 250 })
    await second
    slow.reject(new Error('stale boom'))
    await first

    expect(onError).not.toHaveBeenCalled()
    expect(api().items.value.map((r) => r.id)).toEqual([99])
    expect(api().loading.value).toBe(false)
  })

  it('reports errors through onError and keeps the previous items', async () => {
    const fetchPage = vi.fn(async (): Promise<PagedListPage<Row>> => {
      throw new Error('boom')
    })
    const { wrapper: w, api, onError } = mountPagedList({}, fetchPage)
    wrapper = w
    await api().load()

    expect(onError).toHaveBeenCalledTimes(1)
    expect((onError.mock.calls[0]![0] as Error).message).toBe('boom')
    expect(api().items.value).toEqual([])
    expect(api().loading.value).toBe(false)
  })

  it('jumpToPage clamps to [1, totalPages] and no-ops on the same page', async () => {
    const fetchPage = vi.fn(async (_page: number, _size: number): Promise<PagedListPage<Row>> => ({
      items: [],
      total: 250,
    }))
    const { wrapper: w, api } = mountPagedList({}, fetchPage)
    wrapper = w
    await api().load()
    fetchPage.mockClear()

    // 越界钳制：999 → 第 5 页（offset 契约即 (5-1)*50=200）。
    api().jumpToPage(999)
    await flushPromises()
    expect(fetchPage).toHaveBeenCalledWith(5, 50)
    expect(api().currentPage.value).toBe(5)
    expect(api().jumpInput.value).toBe(5)
    fetchPage.mockClear()

    // 低于下界钳制：0 → 第 1 页。
    api().jumpToPage(0)
    await flushPromises()
    expect(fetchPage).toHaveBeenCalledWith(1, 50)
    fetchPage.mockClear()

    // 同页空操作。
    api().jumpToPage(1)
    await flushPromises()
    expect(fetchPage).not.toHaveBeenCalled()

    // 非法输入（NaN）空操作。
    api().jumpInput.value = null
    api().jumpToPage(Number.NaN)
    await flushPromises()
    expect(fetchPage).not.toHaveBeenCalled()
  })

  it('renders the PC page window with ellipses and keeps the current page visible', async () => {
    const fetchPage = vi.fn(async (): Promise<PagedListPage<Row>> => ({
      items: [],
      total: 1500,
    }))
    const { wrapper: w, api } = mountPagedList({}, fetchPage)
    wrapper = w
    await api().load()
    expect(api().totalPages.value).toBe(30)

    // 当前 ±2（含本身）5 页窗口，两端夹首末页 + 省略号（page 6/30）。
    api().jumpToPage(6)
    await flushPromises()
    expect(api().pageWindow.value).toEqual([1, '…', 4, 5, 6, 7, 8, '…', 30])

    // 首页窗口：首页邻域直出，仅末页折叠。
    api().jumpToPage(1)
    await flushPromises()
    expect(api().pageWindow.value).toEqual([1, 2, 3, 4, 5, 6, '…', 30])
  })

  it('lists every page in the window when totalPages <= 7', async () => {
    const fetchPage = vi.fn(async (): Promise<PagedListPage<Row>> => ({ items: [], total: 250 }))
    const { wrapper: w, api } = mountPagedList({}, fetchPage)
    wrapper = w
    await api().load()
    expect(api().totalPages.value).toBe(5)
    expect(api().pageWindow.value).toEqual([1, 2, 3, 4, 5])
  })

  it('hides the pagination bar when total <= pageSize', async () => {
    const fetchPage = vi.fn(async (page: number): Promise<PagedListPage<Row>> => ({
      items: [],
      total: page === 1 ? 50 : 250,
    }))
    const { wrapper: w, api } = mountPagedList({}, fetchPage)
    wrapper = w
    await api().load()
    expect(api().paginationVisible.value).toBe(false)

    await api().load(2)
    expect(api().paginationVisible.value).toBe(true)
  })

  it('changing the page size persists via the hook and reloads page 1', async () => {
    const fetchPage = vi.fn(async (_page: number, _size: number): Promise<PagedListPage<Row>> => ({
      items: [],
      total: 250,
    }))
    const { wrapper: w, api, onPageSizeChange } = mountPagedList({}, fetchPage)
    wrapper = w
    await api().load()
    api().jumpToPage(3)
    await flushPromises()
    fetchPage.mockClear()

    api().pageSize.value = 100
    await flushPromises()
    expect(onPageSizeChange).toHaveBeenCalledWith(100)
    expect(fetchPage).toHaveBeenCalledWith(1, 100)
    expect(api().currentPage.value).toBe(1)
    expect(api().jumpInput.value).toBe(1)

    // 档外写入（AdminDownload 旧档 300）不触发持久化/重载。
    onPageSizeChange.mockClear()
    fetchPage.mockClear()
    api().pageSize.value = 300
    await flushPromises()
    expect(onPageSizeChange).not.toHaveBeenCalled()
    expect(fetchPage).not.toHaveBeenCalled()
  })

  it('pages with PageDown / PageUp keys and exempts focused inputs', async () => {
    const fetchPage = vi.fn(async (_page: number, _size: number): Promise<PagedListPage<Row>> => ({
      items: [],
      total: 250,
    }))
    const { wrapper: w, api } = mountPagedList({}, fetchPage)
    wrapper = w
    await api().load()
    fetchPage.mockClear()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageDown' }))
    await flushPromises()
    expect(fetchPage).toHaveBeenCalledWith(2, 50)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageUp' }))
    await flushPromises()
    expect(fetchPage).toHaveBeenLastCalledWith(1, 50)
    fetchPage.mockClear()

    // 第 1 页 PageUp 越界空操作。
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageUp' }))
    await flushPromises()
    expect(fetchPage).not.toHaveBeenCalled()

    // INPUT 聚焦豁免（跳页输入框内按 PageDown 不翻页）。
    const input = document.createElement('input')
    document.body.appendChild(input)
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageDown', bubbles: true }))
    await flushPromises()
    expect(fetchPage).not.toHaveBeenCalled()
    input.remove()
  })
})
