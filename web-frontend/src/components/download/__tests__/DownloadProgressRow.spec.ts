/**
 * DownloadProgressRow — P-F2 ③ 行组件专项：
 * - 结构钉住：与抽组件前 DownloadView 内联行逐像素等价（class/结构/aria/
 *   文案断言钉住，不依赖视觉回归）；
 * - 渲染隔离：字段/速率变化只重渲染本行（渲染计数 spy）；
 * - 事件透传：AppListRow 的 open/read/menu/select 与操作钮 start/pause/
 *   cancel/delete 原载荷上抛；
 * - 状态分支：下载/不确定/完成/失败（错误原因）。
 */
import { describe, it, expect, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import DownloadProgressRow from '../DownloadProgressRow.vue'
import AppListRow from '@/components/gallery/AppListRow.vue'
import type { DownloadItem } from '@/api/download'

/** 渲染计数 spy：包一层 instance.render（renderComponentRoot 每次重渲都
    从 instance 重新读取 render，挂载后替换即可拦截每一次重渲染）。
    render 不在 ComponentInternalInstance 的公开类型上——局部形状断言。 */
interface RenderCarrier {
  render: ((...args: unknown[]) => unknown) | null
}

function spyRenders(where: VueWrapper): () => number {
  const instance = (where.vm as unknown as { $: RenderCarrier }).$
  const original = instance.render
  if (!original) throw new Error('component has no render function')
  let count = 0
  instance.render = (...args: unknown[]) => {
    count += 1
    return original(...args)
  }
  return () => count
}

/**
 * 行渲染单元 spy：meta/stats/track/footer 都是传给 AppListRow 的 **slot
 * 内容**——slot 函数在 AppListRow 的渲染 effect 里执行，done/速率等依赖
 * 记在 AppListRow 名下。所以「一行字段变化只重渲染该行」的最小渲染单元
 * 是行内 AppListRow 实例，spy 打在它身上。
 */
function spyRowUnitRenders(row: VueWrapper): () => number {
  return spyRenders(row.findComponent(AppListRow))
}

function makeItem(overrides: Partial<DownloadItem> = {}): DownloadItem {
  return {
    id: 1,
    gid: 9001,
    token: 'tok1',
    title: 'Download Row',
    titleJpn: null,
    thumb: null,
    category: 0,
    state: 0,
    total: 10,
    done: 0,
    label: 0,
    downloadDir: null,
    error: null,
    ...overrides,
  }
}

/** 生产中 item 来自深响应的列表 ref、speeds 是 ref 包裹的 record——测试里用
 *  reactive 复刻同一响应性（裸对象不触发依赖跟踪）。 */
function mountRow(item: DownloadItem, speeds: Record<number, number>, props = {}): VueWrapper {
  return mount(DownloadProgressRow, { props: { item, speeds, ...props } })
}

describe('DownloadProgressRow (P-F2 ③)', () => {
  let wrapper: VueWrapper

  afterEach(() => {
    wrapper?.unmount()
  })

  it('pins the downloading-row structure: classes, aria, stats, progress fill, actions', () => {
    setActivePinia(createPinia())
    wrapper = mountRow(makeItem({ state: 2, done: 5, total: 10 }), { 9001: 3.5 }, {
      selectMode: true,
      selected: true,
    })

    // 行根（AppListRow article）带 download-item 修饰类 + 多选态。
    const root = wrapper.find('article.app-list-row')
    expect(root.classes()).toEqual(
      expect.arrayContaining(['app-list-row', 'download-item', 'download-item--download', 'app-list-row--selectable', 'app-list-row--selected']),
    )
    expect(root.attributes('aria-label')).toBe('Download Row — Downloading')
    // 多选勾选圈点亮。
    expect(wrapper.find('.app-list-row__check--on').exists()).toBe(true)

    // stats：percent + 速率/ETA（3.5 pages/s，余 5 页 → ETA 1s）。
    expect(wrapper.find('.download-item__percent').text()).toBe('50%')
    expect(wrapper.find('.download-item__speed').text()).toBe('3.5 pages/s · ETA 1s')
    expect(wrapper.find('.download-item__pages').text()).toBe('5/10 pages')

    // 进度条：determinate，aria + fill 宽度。
    const track = wrapper.find('.download-item__track')
    expect(track.attributes('role')).toBe('progressbar')
    expect(track.attributes('aria-valuemax')).toBe('100')
    expect(track.attributes('aria-valuenow')).toBe('50')
    expect(track.attributes('aria-label')).toBe('Download progress for Download Row')
    const fill = wrapper.find('.download-item__fill')
    expect(fill.classes()).toContain('download-item__fill--sheen')
    expect(fill.attributes('style')).toBe('width: 50%;')

    // 状态文案 + 呼吸点；下载中操作钮 = 暂停 + 删除。
    expect(wrapper.find('.download-item__state').text()).toBe('Downloading')
    expect(wrapper.find('.download-item__state-dot').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Start download"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Pause download"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Stop download"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Delete download"]').exists()).toBe(true)
  })

  it('renders the indeterminate slide when downloading with an unknown total', () => {
    setActivePinia(createPinia())
    wrapper = mountRow(makeItem({ state: 2, total: 0 }), {})

    expect(wrapper.find('.download-item__percent').text()).toBe('—')
    expect(wrapper.find('.download-item__pages').exists()).toBe(false)
    const track = wrapper.find('.download-item__track')
    expect(track.attributes('aria-valuenow')).toBeUndefined()
    const fill = wrapper.find('.download-item__fill')
    expect(fill.classes()).toContain('download-item__fill--indeterminate')
    expect(fill.attributes('style')).toBeUndefined()
  })

  it('renders the finished branch: 100%, no speed, delete-only actions', () => {
    setActivePinia(createPinia())
    wrapper = mountRow(makeItem({ state: 3, done: 10, total: 10 }), { 9001: 0 })

    expect(wrapper.find('article.app-list-row').classes()).toContain('download-item--finish')
    expect(wrapper.find('.download-item__percent').text()).toBe('100%')
    expect(wrapper.find('.download-item__speed').exists()).toBe(false)
    expect(wrapper.find('.download-item__state').text()).toBe('Done')
    expect(wrapper.find('[aria-label="Pause download"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Start download"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Delete download"]').exists()).toBe(true)
  })

  it('renders the failed branch: start (retry), danger delete and the trimmed error reason', () => {
    setActivePinia(createPinia())
    wrapper = mountRow(makeItem({ state: 4, error: ' disk full  ' }), {})

    expect(wrapper.find('article.app-list-row').classes()).toContain('download-item--failed')
    expect(wrapper.find('.download-item__state').text()).toBe('Failed')
    const error = wrapper.find('.download-item__error')
    expect(error.text()).toBe('disk full')
    expect(error.attributes('title')).toBe('disk full')
    expect(wrapper.find('[aria-label="Start download"]').exists()).toBe(true)
  })

  it('keeps the defensive defaults with unloaded prefs (uploader/pages/badge hidden)', () => {
    setActivePinia(createPinia())
    wrapper = mountRow(makeItem({ uploader: 'artist_x', pages: 24, readProgress: 3 }), {})

    expect(wrapper.find('.download-item__uploader').exists()).toBe(false)
    expect(wrapper.find('.download-item__gallery-pages').exists()).toBe(false)
    expect(wrapper.find('[data-testid="read-progress-badge"]').exists()).toBe(false)
  })

  it('forwards AppListRow and action-button events with their original payloads', async () => {
    setActivePinia(createPinia())
    wrapper = mountRow(makeItem({ state: 2 }), {}) // 下载中 → pause/cancel/delete 可见

    await wrapper.find('.app-list-row__thumb').trigger('click')
    await wrapper.find('article.app-list-row').trigger('click') // 非多选主体 → read
    await wrapper.find('article.app-list-row').trigger('contextmenu') // 右键 → menu
    await wrapper.find('[aria-label="Pause download"]').trigger('click')
    await wrapper.find('[aria-label="Stop download"]').trigger('click')
    await wrapper.find('[aria-label="Delete download"]').trigger('click')

    expect(wrapper.emitted('open')).toEqual([[9001]])
    expect(wrapper.emitted('read')).toEqual([[9001]])
    expect(wrapper.emitted('menu')).toEqual([[1]])
    expect(wrapper.emitted('pause')).toEqual([[1]])
    expect(wrapper.emitted('cancel')).toEqual([[1]])
    expect(wrapper.emitted('delete')).toEqual([[1]])
    expect(wrapper.emitted('select')).toBeUndefined()
  })

  it('forwards start/select for idle rows and select-mode body clicks', async () => {
    setActivePinia(createPinia())
    wrapper = mountRow(makeItem({ state: 0 }), {}, { selectMode: true })

    await wrapper.find('[aria-label="Start download"]').trigger('click')
    await wrapper.find('article.app-list-row').trigger('click') // 多选主体 → select

    expect(wrapper.emitted('start')).toEqual([[1]])
    expect(wrapper.emitted('select')).toEqual([[1]])
    expect(wrapper.emitted('read')).toBeUndefined()
  })

  it('re-renders only the row whose fields changed (渲染隔离 spy)', async () => {
    setActivePinia(createPinia())
    // 两行共享同一 speeds record（视图里 liveSpeeds 的 ref 内层对象）。
    const speeds = reactive<Record<number, number>>({ 9001: 2, 9002: 2 })
    const itemA = reactive(makeItem({ id: 1, gid: 9001, state: 2, done: 1 }))
    const itemB = reactive(makeItem({ id: 2, gid: 9002, state: 2, done: 1 }))
    const rowA = mountRow(itemA, speeds)
    const rowB = mountRow(itemB, speeds)
    // 双层 spy：行组件自身（模板只读 state/title 等，不该因 done/速率重渲）
    // + 行渲染单元 AppListRow（slot 内容依赖的真正归属）。
    const selfRendersA = spyRenders(rowA)
    const unitRendersA = spyRowUnitRenders(rowA)
    const selfRendersB = spyRenders(rowB)
    const unitRendersB = spyRowUnitRenders(rowB)

    // 进度帧只动 A：done 字段 + speeds[9001]。
    itemA.done = 5
    speeds[9001] = 4
    await nextTick()

    expect(unitRendersA()).toBe(1)
    expect(unitRendersB()).toBe(0)
    expect(selfRendersA()).toBe(0)
    expect(selfRendersB()).toBe(0)
    expect(rowA.find('.download-item__pages').text()).toBe('5/10 pages')
    // 速率 <10 → 保留一位小数（statsTextOf 原格式）。
    expect(rowA.find('.download-item__speed').text()).toBe('4.0 pages/s · ETA 1s')
    expect(rowB.find('.download-item__pages').text()).toBe('1/10 pages')

    rowA.unmount()
    rowB.unmount()
  })
})
