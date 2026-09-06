import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import AppListRow from '../AppListRow.vue'

const BASE = { id: 7, gid: 9001, title: 'Row Alpha' }

function mountRow(
  props: Partial<InstanceType<typeof AppListRow>['$props']> = {},
  slots: Record<string, string> = {},
): VueWrapper {
  return mount(AppListRow, { props: { ...BASE, ...props }, slots })
}

describe('AppListRow — 行骨架（W3-C1 共享单列行）', () => {
  it('renders title, optional subtitle and the consumer slots', () => {
    const wrapper = mountRow(
      { subtitle: 'Row Nihongo' },
      {
        meta: '<span class="chip-x">Manga</span>',
        default: '<div class="body-x">extra</div>',
        badge: '<span class="badge-x">2h ago</span>',
      },
    )
    expect(wrapper.find('.app-list-row__title').text()).toBe('Row Alpha')
    expect(wrapper.find('.app-list-row__subtitle').text()).toBe('Row Nihongo')
    // 副题缺省不渲染。
    expect(wrapper.find('.app-list-row__subtitle').exists()).toBe(true)
    // meta 包一层 flex 容器；默认 slot 直接进 body。
    expect(wrapper.find('.app-list-row__meta .chip-x').text()).toBe('Manga')
    expect(wrapper.find('.app-list-row__body .body-x').text()).toBe('extra')
    // 角标挂行根（消费方自管绝对定位）。
    expect(wrapper.find('.badge-x').text()).toBe('2h ago')
  })

  it('omits the meta container and subtitle when not provided', () => {
    const wrapper = mountRow()
    expect(wrapper.find('.app-list-row__meta').exists()).toBe(false)
    expect(wrapper.find('.app-list-row__subtitle').exists()).toBe(false)
  })
})

describe('AppListRow — 缩略图处理（与 DownloadItem/GalleryCard 同语义）', () => {
  it('rewrites external http(s) thumbs through the WebUI image proxy', () => {
    const thumb = 'https://ehgt.org/t/9001/cover.jpg'
    const wrapper = mountRow({ thumb })
    expect(wrapper.find('.app-list-row__thumb img').attributes('src')).toBe(
      `/api/v1/image/proxy?url=${encodeURIComponent(thumb)}`,
    )
  })

  it('keeps non-external thumb sources unchanged', () => {
    const wrapper = mountRow({ thumb: '/thumbs/9001/cover.jpg' })
    expect(wrapper.find('.app-list-row__thumb img').attributes('src')).toBe('/thumbs/9001/cover.jpg')
  })

  it('renders the icon placeholder when thumb is null (no alt leak)', () => {
    const wrapper = mountRow({ thumb: null })
    expect(wrapper.find('.app-list-row__thumb img').exists()).toBe(false)
    const placeholder = wrapper.find('.app-list-row__thumb-placeholder')
    expect(placeholder.exists()).toBe(true)
    expect(placeholder.text()).toBe('')
    expect(placeholder.find('svg').exists()).toBe(true)
  })

  it('swaps a failed thumbnail to the placeholder (no alt leak)', async () => {
    const wrapper = mountRow({ thumb: 'https://ehgt.org/t/9001/cover.jpg' })
    expect(wrapper.find('.app-list-row__thumb img').exists()).toBe(true)
    await wrapper.find('.app-list-row__thumb img').trigger('error')
    expect(wrapper.find('.app-list-row__thumb img').exists()).toBe(false)
    expect(wrapper.find('.app-list-row__thumb-placeholder').exists()).toBe(true)
  })
})

describe('AppListRow — 缩略图代理 URL 经 server 模块派生（plan-2026-09-06-pwa C2 后继）', () => {
  afterEach(() => {
    localStorage.removeItem('server-base')
  })

  it('keeps the legacy literal when no server base is persisted (byte-identical anchor)', () => {
    const thumb = 'https://ehgt.org/t/9001/cover.jpg'
    const wrapper = mountRow({ thumb })
    expect(wrapper.find('.app-list-row__thumb img').attributes('src')).toBe(
      `/api/v1/image/proxy?url=${encodeURIComponent(thumb)}`,
    )
  })

  it('prefixes the proxy URL with the configured server base (remote mode)', () => {
    localStorage.setItem('server-base', 'http://x:1')
    const thumb = 'https://ehgt.org/t/9001/cover.jpg'
    const wrapper = mountRow({ thumb })
    expect(wrapper.find('.app-list-row__thumb img').attributes('src')).toBe(
      `http://x:1/api/v1/image/proxy?url=${encodeURIComponent(thumb)}`,
    )
  })
})

describe('AppListRow — 点击分区（缩略图→详情 / 主体→阅读）', () => {
  it('thumb click emits open (detail) and not read', async () => {
    const wrapper = mountRow()
    await wrapper.find('.app-list-row__thumb').trigger('click')
    expect(wrapper.emitted('open')).toEqual([[9001]])
    expect(wrapper.emitted('read')).toBeUndefined()
  })

  it('thumb Enter key emits open as well (role=link)', async () => {
    const wrapper = mountRow()
    await wrapper.find('.app-list-row__thumb').trigger('keydown.enter')
    expect(wrapper.emitted('open')).toEqual([[9001]])
  })

  it('body click outside select mode emits read (direct reader)', async () => {
    const wrapper = mountRow({}, { default: '<p class="x">body</p>' })
    await wrapper.find('.app-list-row__body').trigger('click')
    expect(wrapper.emitted('read')).toEqual([[9001]])
    expect(wrapper.emitted('open')).toBeUndefined()
  })

  it('body click in select mode toggles via select(id) instead', async () => {
    const wrapper = mountRow({ selectable: true, selected: false })
    await wrapper.find('.app-list-row').trigger('click')
    expect(wrapper.emitted('select')).toEqual([[7]])
    expect(wrapper.emitted('read')).toBeUndefined()
  })

  it('clicks on slotted buttons do not bubble into read/select (closest guard)', async () => {
    const wrapper = mountRow({ selectable: true }, { default: '<button type="button">Act</button>' })
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
    expect(wrapper.emitted('read')).toBeUndefined()
  })
})

describe('AppListRow — 多选入口（右键 / 长按 / 勾选态）', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('emits menu on right-click and suppresses the native context menu', () => {
    const wrapper = mountRow()
    const article = wrapper.find('.app-list-row')
    article.trigger('contextmenu')
    expect(wrapper.emitted('menu')).toHaveLength(1)
    expect(wrapper.emitted('menu')![0]).toEqual([7])
  })

  it('emits menu after a 500ms long-press (<10px movement)', async () => {
    vi.useFakeTimers()
    const wrapper = mountRow()
    const article = wrapper.find('.app-list-row')

    article.trigger('touchstart', { touches: [{ clientX: 10, clientY: 10 }] })
    vi.advanceTimersByTime(499)
    expect(wrapper.emitted('menu')).toBeUndefined()
    vi.advanceTimersByTime(2)
    expect(wrapper.emitted('menu')).toHaveLength(1)
    expect(wrapper.emitted('menu')![0]).toEqual([7])
  })

  it('cancels the long-press on early touch end or big movement', async () => {
    vi.useFakeTimers()
    const wrapper = mountRow()
    const article = wrapper.find('.app-list-row')

    // 短触：500ms 前 touchend → 无 menu。
    article.trigger('touchstart', { touches: [{ clientX: 0, clientY: 0 }] })
    article.trigger('touchend')
    vi.advanceTimersByTime(600)
    expect(wrapper.emitted('menu')).toBeUndefined()

    // 按压中位移 >10px 取消计时器。
    article.trigger('touchstart', { touches: [{ clientX: 0, clientY: 0 }] })
    article.trigger('touchmove', { touches: [{ clientX: 30, clientY: 0 }] })
    vi.advanceTimersByTime(600)
    expect(wrapper.emitted('menu')).toBeUndefined()
  })

  it('renders the checked indicator, selected style and aria-selected', () => {
    const wrapper = mountRow({ selectable: true, selected: true })
    expect(wrapper.find('.app-list-row__check').exists()).toBe(true)
    expect(wrapper.find('.app-list-row__check--on').exists()).toBe(true)
    expect(wrapper.find('.app-list-row').classes()).toContain('app-list-row--selected')
    expect(wrapper.find('.app-list-row').attributes('aria-selected')).toBe('true')
  })

  it('omits the check indicator outside select mode (aria-selected absent)', () => {
    const wrapper = mountRow({ selectable: false, selected: true })
    expect(wrapper.find('.app-list-row__check').exists()).toBe(false)
    expect(wrapper.find('.app-list-row').attributes('aria-selected')).toBeUndefined()
  })
})
