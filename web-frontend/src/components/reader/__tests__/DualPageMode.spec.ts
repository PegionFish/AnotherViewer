import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DualPageMode from '../DualPageMode.vue'
import { usePreferencesStore } from '@/stores/preferences'
import {
  DEFAULT_PREFERENCES,
  DEFAULT_READER_PREFERENCES,
} from '@/api/preferences'
import { pageImageUrl } from '../PageMode.vue'

/* ------------------------------------------------------------------------ */
/* 夹具                                                                      */
/* ------------------------------------------------------------------------ */

function prefsWithTapZone(scheme: string): void {
  const store = usePreferencesStore()
  store.prefs = {
    general: { ...DEFAULT_PREFERENCES.general },
    reader: { ...DEFAULT_READER_PREFERENCES, tapZoneScheme: scheme },
    privacy: { ...DEFAULT_PREFERENCES.privacy },
  }
}

interface DualProps {
  gid: number
  page: number
  totalPages: number
  direction: 'ltr' | 'rtl'
  enhancedUrls?: ReadonlyMap<number, string>
}

function mountDual(overrides: Partial<DualProps> = {}): VueWrapper {
  return mount(DualPageMode, {
    props: {
      gid: 77,
      page: 0,
      totalPages: 6,
      direction: 'ltr' as const,
      ...overrides,
    },
    attachTo: document.body,
  })
}

/**
 * happy-dom 无布局：把点击分区依赖的几何量（clientWidth /
 * getBoundingClientRect().left）钉到确定值，分区边界才可断言。
 */
function pinStageGeometry(wrapper: VueWrapper, width: number): HTMLElement {
  const el = wrapper.get('.dual-page').element as HTMLElement
  Object.defineProperty(el, 'clientWidth', { value: width, configurable: true })
  vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({
    left: 0,
    top: 0,
    right: width,
    bottom: 600,
    width,
    height: 600,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  } as DOMRect)
  return el
}

/** 桌面鼠标语义的 click（pointerType='mouse' → 单击立即生效，无双击窗口）。 */
function clickAt(el: HTMLElement, clientX: number): void {
  const event = new MouseEvent('click', { bubbles: true, clientX, clientY: 300 })
  Object.defineProperty(event, 'pointerType', { value: 'mouse' })
  el.dispatchEvent(event)
}

/* ------------------------------------------------------------------------ */
/* T1a：tapZoneScheme 偏好接入分区判定                                        */
/* ------------------------------------------------------------------------ */

describe('DualPageMode — tapZoneScheme 偏好（T1a）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('默认 threeZone：左 ⅓ prev / 右 ⅓ next / 中间 toggle-chrome', () => {
    prefsWithTapZone('threeZone')
    const wrapper = mountDual()
    const el = pinStageGeometry(wrapper, 1000)

    clickAt(el, 100)
    clickAt(el, 900)
    clickAt(el, 500)

    expect(wrapper.emitted('prev')).toHaveLength(1)
    expect(wrapper.emitted('next')).toHaveLength(1)
    expect(wrapper.emitted('toggle-chrome')).toHaveLength(1)
    wrapper.unmount()
  })

  it('edgeOnly：仅 15% 边缘翻页，20% 已是中央（threeZone 下仍是 prev）', () => {
    prefsWithTapZone('edgeOnly')
    const wrapper = mountDual()
    const el = pinStageGeometry(wrapper, 1000)

    clickAt(el, 100) // 10% < 15% 边缘 → prev
    clickAt(el, 200) // 20% ≥ 15% → 中央 → toggle-chrome
    clickAt(el, 950) // 95% 边缘 → next
    clickAt(el, 800) // 80% → 中央 → toggle-chrome

    expect(wrapper.emitted('prev')).toHaveLength(1)
    expect(wrapper.emitted('next')).toHaveLength(1)
    expect(wrapper.emitted('toggle-chrome')).toHaveLength(2)
    wrapper.unmount()
  })

  it('disabled：点击不翻页，一律 toggle-chrome', () => {
    prefsWithTapZone('disabled')
    const wrapper = mountDual()
    const el = pinStageGeometry(wrapper, 1000)

    clickAt(el, 100)
    clickAt(el, 900)

    expect(wrapper.emitted('prev')).toBeUndefined()
    expect(wrapper.emitted('next')).toBeUndefined()
    expect(wrapper.emitted('toggle-chrome')).toHaveLength(2)
    wrapper.unmount()
  })

  it('偏好变化即时生效：threeZone → edgeOnly 后同一落点从 prev 变 toggle', async () => {
    prefsWithTapZone('threeZone')
    const wrapper = mountDual()
    const el = pinStageGeometry(wrapper, 1000)

    clickAt(el, 200) // threeZone：20% < ⅓ → prev
    expect(wrapper.emitted('prev')).toHaveLength(1)

    const store = usePreferencesStore()
    store.prefs!.reader.tapZoneScheme = 'edgeOnly'
    await wrapper.vm.$nextTick()

    clickAt(el, 200) // edgeOnly：20% 已出边缘 → toggle-chrome
    expect(wrapper.emitted('prev')).toHaveLength(1)
    expect(wrapper.emitted('toggle-chrome')).toHaveLength(1)
    wrapper.unmount()
  })

  it('RTL 镜像不受方案影响：左边缘 next、右边缘 prev（threeZone）', () => {
    prefsWithTapZone('threeZone')
    const wrapper = mountDual({ direction: 'rtl' as const })
    const el = pinStageGeometry(wrapper, 1000)

    clickAt(el, 100)
    clickAt(el, 900)

    expect(wrapper.emitted('next')).toHaveLength(1)
    expect(wrapper.emitted('prev')).toHaveLength(1)
    wrapper.unmount()
  })
})

/* ------------------------------------------------------------------------ */
/* V5：相邻铺摊预取                                                          */
/* ------------------------------------------------------------------------ */

/** 捕获 new Image() 的预取目标（stub 掉 happy-dom 的原生 Image）。 */
class FakeImage {
  static instances: FakeImage[] = []
  src = ''
  decoding = ''
  constructor() {
    FakeImage.instances.push(this)
  }
}

/** 与组件 srcFor 同式的期望 URL（happy-dom: clientWidth=0 → innerWidth 兜底）。 */
function expectedUrl(gid: number, page: number, cssWidth: number): string {
  const dpr =
    typeof window.devicePixelRatio === 'number' && window.devicePixelRatio > 0
      ? window.devicePixelRatio
      : 1
  return pageImageUrl(gid, page, Math.max(1, Math.round(cssWidth * dpr)))
}

function halfStageWidth(): number {
  const el = document.querySelector('.dual-page')
  const measured = el instanceof HTMLElement ? el.clientWidth : 0
  return (measured || window.innerWidth) / 2
}

describe('DualPageMode — 相邻铺摊预取（V5）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    prefsWithTapZone('threeZone')
    FakeImage.instances = []
    vi.stubGlobal('Image', FakeImage)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it('翻页时以前一主页面为基准预取前/后铺摊的主页面（封面独页语义）', async () => {
    const wrapper = mountDual({ gid: 77, page: 0, totalPages: 6 })

    // 挂载本身不预取（宽度尚是默认值，URL 会与真实请求错开）。
    expect(FakeImage.instances).toHaveLength(0)

    // page 2 → spread 1 = (1,2)；前铺摊主页面 0、后铺摊主页面 3。
    await wrapper.setProps({ page: 2 })

    const half = halfStageWidth()
    expect(FakeImage.instances.map((img) => img.src)).toEqual([
      expectedUrl(77, 0, half),
      expectedUrl(77, 3, half),
    ])
    // 预取的是相邻铺摊，不含已在屏上的当前铺摊页（1、2）。
    expect(FakeImage.instances.map((img) => img.src)).not.toContain(expectedUrl(77, 1, half))
    expect(FakeImage.instances.map((img) => img.src)).not.toContain(expectedUrl(77, 2, half))
    wrapper.unmount()
  })

  it('末铺摊不再预取 next（越界即不发请求）', async () => {
    // totalPages=6、封面独页：铺摊 0 | (1,2) | (3,4) | 5（独页）。
    const wrapper = mountDual({ gid: 77, page: 4, totalPages: 6 })
    expect(FakeImage.instances).toHaveLength(0)

    await wrapper.setProps({ page: 5 })

    const half = halfStageWidth()
    // 只回补前一铺摊主页面 3；next 铺摊首页 7 越界，绝不发请求。
    expect(FakeImage.instances.map((img) => img.src)).toEqual([expectedUrl(77, 3, half)])
    wrapper.unmount()
  })

  it('同 URL 只预取一次（往返翻页不重复建 Image）', async () => {
    const wrapper = mountDual({ gid: 77, page: 2, totalPages: 6 })
    const half = halfStageWidth()

    await wrapper.setProps({ page: 4 }) // spread(3,4)：预取 1、5
    await wrapper.setProps({ page: 2 }) // spread(1,2)：预取 0、3
    await wrapper.setProps({ page: 4 }) // 1、5 已预取过 → 不再发请求

    expect(FakeImage.instances.map((img) => img.src)).toEqual([
      expectedUrl(77, 1, half),
      expectedUrl(77, 5, half),
      expectedUrl(77, 0, half),
      expectedUrl(77, 3, half),
    ])
    wrapper.unmount()
  })
})
