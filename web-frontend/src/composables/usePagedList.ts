import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch, type ComputedRef, type Ref } from 'vue'

/**
 * 单页结果 —— 服务端分页契约的统一消费形态。`fetchPage` 负责把两种服务端
 * 形态适配成它：
 * - offset/limit（下载 / 首页 / 搜索上游）：`offset = (page - 1) * pageSize`；
 * - page/pageSize（历史 / 收藏信封）：直接透传。
 */
export interface PagedListPage<T> {
  items: T[]
  total: number
}

export interface UsePagedListOptions<T> {
  /**
   * 取一页（`page` 1 起）。消费方在这里适配服务端契约并携带当前过滤条件
   * （label/q/regex 等——闭包读取，重载时总是取最新值）。
   */
  fetchPage: (page: number, pageSize: number) => Promise<PagedListPage<T>>
  /** 每页条数档位（条数切换下拉的可选值；档外写入不触发重载）。 */
  pageSizes: readonly number[]
  /** 初始每页条数；档外值（如共享偏好里的旧档位）回落 `fallbackPageSize`。 */
  initialPageSize: number
  fallbackPageSize: number
  /** 非静默加载开始（宿主切 loading 态等）。`load(page, { silent: true })` 不触发。 */
  onLoadStart?: (page: number) => void
  /** 加载成功且非 stale——此时 items/total/currentPage 已更新。 */
  onSuccess?: (result: PagedListPage<T>, page: number) => void
  /** 加载失败且非 stale（stale 竞态失败静默丢弃）。 */
  onError?: (error: unknown, page: number) => void
  /** 每页条数变更已提交（宿主持久化偏好），随后状态机自动回第 1 页重载。 */
  onPageSizeChange?: (pageSize: number) => void
  /** PC 键盘翻页：window 级 PageUp/PageDown（INPUT/SELECT/TEXTAREA 聚焦豁免）。 */
  keyboardPaging?: boolean
}

export interface UsePagedList<T> {
  /** 当前页条目（替换语义：每次加载整页覆盖；宿主可直接做乐观局部变更）。 */
  items: Ref<T[]>
  /** 过滤条件下的总条数（服务端口径）。 */
  total: Ref<number>
  /** 每页条数（档位内；可 v-model 绑定分页条下拉）。 */
  pageSize: Ref<number>
  /** 当前页码（1 起，跟随加载位置）。 */
  currentPage: Ref<number>
  totalPages: ComputedRef<number>
  /** total > pageSize 才显示分页条（对齐 Android PaginationIndicator 语义）。 */
  paginationVisible: ComputedRef<boolean>
  /** PC 页码窗口：总页数 ≤7 全量，否则首页/末页夹在窗口两端 + 省略号折叠
      （当前 ±2 邻页；示例 page 6 / total 30 → [1,'…',4,5,6,7,8,'…',30]）。 */
  pageWindow: ComputedRef<Array<number | '…'>>
  /** 跳页输入框绑定值（v-model.number；跳页成功后同步为目标页）。 */
  jumpInput: Ref<number | null>
  /** 任一在途加载（含静默）；stale 竞态不提前松开。 */
  loading: Ref<boolean>
  /**
   * 替换式加载指定页（1 起）。`silent: true` 时不触发 onLoadStart（宿主
   * 自管忙态的入口：下拉刷新/批量操作后的静默重载等）。
   */
  load: (page?: number, opts?: { silent?: boolean }) => Promise<void>
  /** 跳页：钳制到 [1, totalPages]，同页空操作；jumpInput 同步目标页。 */
  jumpToPage: (force?: number) => void
}

/**
 * usePagedList —— 服务端分页列表状态机（A4 先行卡 W3-C1 抽取）。
 *
 * 所有单列密信息列表视图（下载/首页/搜索/历史/收藏）共用的分页核心：
 * 页码 / 每页条数 / 跳页 / PC 页码窗口 / 键盘翻页 / stale 竞态守卫。
 * 视图层（loading/content/empty/error 四态机、错误文案）留在宿主，通过
 * onLoadStart / onSuccess / onError 钩子接线。
 *
 * 竞态防护：单调 requestSeq——快速切标签/切筛选/连续跳页时，先发出的在途
 * 响应作废（items/total/currentPage 与 onSuccess/onError 均不落账）。
 *
 * KeepAlive 页码还原：App.vue 已按 fullPath 分实例缓存列表视图（`/` 与
 * `/?feed=popular` 是两个实例），页码等状态随组件实例存续、返回即还原，
 * 本 composable 无需额外逻辑。
 *
 * 不自动加载：宿主在合适的时机（通常 onMounted）自行调用 `load()`，以便
 * 等待路由参数/偏好等前置。
 */
export function usePagedList<T>(options: UsePagedListOptions<T>): UsePagedList<T> {
  const { pageSizes } = options

  const items = ref<T[]>([]) as Ref<T[]>
  const total = ref(0)
  const pageSize = ref(
    (pageSizes as readonly number[]).includes(options.initialPageSize)
      ? options.initialPageSize
      : options.fallbackPageSize,
  )
  /** 当前页码（1 起，跟随加载位置；跳页直取替换）。 */
  const currentPage = ref(1)
  const jumpInput = ref<number | null>(1)
  const loading = ref(false)

  const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
  /** 还有更多页才需要定位（total ≤ pageSize 时分页条隐藏）。 */
  const paginationVisible = computed(() => total.value > pageSize.value)

  /**
   * PC 页码窗口：总页数 ≤7 全量；否则首页/末页夹在窗口两端，窗口内当前
   * ±2 邻页 + 折叠省略号，当前页永远可见。
   * 返回示例（page=6/total=30）：[1,'…',4,5,6,7,8,'…',30]。
   */
  const pageWindow = computed<Array<number | '…'>>(() => {
    const pages = totalPages.value
    const current = currentPage.value
    if (pages <= 7) return Array.from({ length: pages }, (_, i) => i + 1)
    const windowSize = 5 // 当前 ±2（含本身）
    const start = Math.max(2, Math.min(current - 2, pages - windowSize))
    const end = start + windowSize - 1
    const window: Array<number | '…'> = []
    window.push(1)
    if (start > 2) window.push('…')
    for (let p = start; p <= end; p++) window.push(p)
    if (end < pages - 1) window.push('…')
    window.push(pages)
    return window
  })

  /** Monotonic request guard — stale responses (fast filter switches) drop. */
  let requestSeq = 0

  async function load(page = 1, opts?: { silent?: boolean }): Promise<void> {
    const seq = ++requestSeq
    if (!opts?.silent) options.onLoadStart?.(page)
    loading.value = true
    try {
      const result = await options.fetchPage(page, pageSize.value)
      if (seq !== requestSeq) return
      items.value = result.items
      total.value = result.total
      currentPage.value = Math.min(page, totalPages.value)
      loading.value = false
      options.onSuccess?.(result, currentPage.value)
    } catch (error) {
      if (seq !== requestSeq) return
      loading.value = false
      options.onError?.(error, page)
    }
  }

  /**
   * 跳页：目标页钳制到 [1, totalPages]（非法输入空操作），同页不重载；
   * jumpInput 同步目标页后整页替换加载（宿主负责滚回顶部）。
   */
  function jumpToPage(force?: number): void {
    const target = Math.min(
      Math.max(Math.floor(force ?? jumpInput.value ?? currentPage.value), 1),
      totalPages.value,
    )
    if (!Number.isFinite(target) || target < 1) return
    if (target === currentPage.value) return
    jumpInput.value = target
    void load(target)
  }

  /** 每页条数切换（分页条下拉）→ 持久化钩子 + 重置回第 1 页加载。 */
  watch(pageSize, (next) => {
    if (!(pageSizes as readonly number[]).includes(next)) return
    options.onPageSizeChange?.(next)
    jumpInput.value = 1
    void load(1)
  })

  /** PC 键盘：PageUp/PageDown 上一页/下一页（INPUT/SELECT 焦点时豁免）。 */
  function onPageKey(e: KeyboardEvent): void {
    const target = e.target as HTMLElement | null
    if (target && (target.tagName === 'INPUT' || target.tagName === 'SELECT' || target.tagName === 'TEXTAREA')) return
    if (e.key === 'PageDown') {
      e.preventDefault()
      if (currentPage.value < totalPages.value) jumpToPage(currentPage.value + 1)
    } else if (e.key === 'PageUp') {
      e.preventDefault()
      if (currentPage.value > 1) jumpToPage(currentPage.value - 1)
    }
  }

  if (options.keyboardPaging) {
    // KeepAlive 缓存视图（DownloadView/HistoryView 等）停用期间不得后台劫持
    // PageUp/Down（audit P1-5 同类缺陷）：onMounted 注册覆盖非缓存挂载
    // （onActivated 仅在 KeepAlive 内触发），激活期 remove-before-add 保证
    // 全程恰好一个监听器，停用即摘除；unmount 兜底缓存淘汰路径。
    onMounted(() => window.addEventListener('keydown', onPageKey))
    onActivated(() => {
      window.removeEventListener('keydown', onPageKey)
      window.addEventListener('keydown', onPageKey)
    })
    onDeactivated(() => window.removeEventListener('keydown', onPageKey))
    onUnmounted(() => window.removeEventListener('keydown', onPageKey))
  }

  return {
    items,
    total,
    pageSize,
    currentPage,
    totalPages,
    paginationVisible,
    pageWindow,
    jumpInput,
    loading,
    load,
    jumpToPage,
  }
}
