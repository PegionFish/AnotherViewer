import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ImageReader from '../ImageReader.vue'
import { usePreferencesStore } from '@/stores/preferences'
import { DEFAULT_PREFERENCES, DEFAULT_READER_PREFERENCES } from '@/api/preferences'

/**
 * T1b/T1c 的组件级 spec：Wake Lock 屏幕常亮生命周期 + Fullscreen API 接管
 * reader.fullscreen。chrome 子组件全部 stub——这里只关心 ImageReader 自身的
 * 文档级监听（visibilitychange / fullscreenchange）与挂载/卸载行为。
 */

/* ------------------------------------------------------------------ */
/* 全局属性 override 工具（保存原描述符，afterEach 还原）                */
/* ------------------------------------------------------------------ */

const propRestores: Array<() => void> = []

function overrideProp(target: object, key: string, descriptor: PropertyDescriptor): void {
  const original = Object.getOwnPropertyDescriptor(target, key)
  Object.defineProperty(target, key, { ...descriptor, configurable: true })
  propRestores.push(() => {
    if (original) Object.defineProperty(target, key, original)
    else delete (target as unknown as Record<string, unknown>)[key]
  })
}

function setHidden(hidden: boolean): void {
  overrideProp(document, 'hidden', { configurable: true, value: hidden })
}

function dispatchVisibility(hidden: boolean): void {
  setHidden(hidden)
  document.dispatchEvent(new Event('visibilitychange'))
}

/* ------------------------------------------------------------------ */
/* Wake Lock mock                                                      */
/* ------------------------------------------------------------------ */

interface MockSentinel {
  released: boolean
  release: ReturnType<typeof vi.fn>
}

function makeSentinel(): MockSentinel {
  const sentinel: MockSentinel = {
    released: false,
    release: vi.fn(async () => {
      sentinel.released = true
    }),
  }
  return sentinel
}

function installWakeLock() {
  const sentinels: MockSentinel[] = []
  const request = vi.fn(async () => {
    const sentinel = makeSentinel()
    sentinels.push(sentinel)
    return sentinel as unknown as WakeLockSentinel
  })
  overrideProp(navigator, 'wakeLock', { configurable: true, value: { request } })
  return { request, sentinels }
}

/* ------------------------------------------------------------------ */
/* Fullscreen API mock（fullscreenchange 由 mock 派发，模拟浏览器行为）  */
/* ------------------------------------------------------------------ */

function installFullscreen() {
  let fullscreenEl: Element | null = null
  const fire = () => document.dispatchEvent(new Event('fullscreenchange'))
  const requestFullscreen = vi.fn(async function (this: Element) {
    fullscreenEl = this
    fire()
  })
  const exitFullscreen = vi.fn(async () => {
    if (fullscreenEl !== null) {
      fullscreenEl = null
      fire()
    }
  })
  const clear = () => {
    fullscreenEl = null
    fire()
  }
  overrideProp(Element.prototype, 'requestFullscreen', { value: requestFullscreen })
  // happy-dom 的 document 实例不走全局 Document.prototype（补丁不生效）——
  // 直接打实例自有属性，属性查找必中。
  overrideProp(document, 'exitFullscreen', { value: exitFullscreen })
  overrideProp(document, 'fullscreenElement', {
    configurable: true,
    get: () => fullscreenEl,
  })
  return { requestFullscreen, exitFullscreen, current: () => fullscreenEl, clear }
}

/* ------------------------------------------------------------------ */
/* Mount helpers                                                       */
/* ------------------------------------------------------------------ */

function prefsWithReader(patch: Partial<typeof DEFAULT_READER_PREFERENCES> = {}): void {
  const store = usePreferencesStore()
  store.prefs = {
    general: { ...DEFAULT_PREFERENCES.general },
    reader: { ...DEFAULT_READER_PREFERENCES, ...patch },
    privacy: { ...DEFAULT_PREFERENCES.privacy },
  }
}

function mountReader(props: Record<string, unknown> = {}) {
  return mount(ImageReader, {
    props: {
      gid: 123456,
      title: 'Reader Spec',
      totalPages: 10,
      currentPage: 0,
      direction: 'ltr',
      pageMode: 'single',
      mode: 'page',
      zoom: 1,
      brightness: 0,
      autoPlay: { enabled: false, intervalMs: 3000 },
      autoPlayProgress: 0,
      wakeLock: true,
      ...props,
    },
    global: {
      stubs: {
        PageMode: true,
        DualPageMode: true,
        ScrollMode: true,
        ReaderStatusBar: true,
        SeekBarPanel: true,
        ReaderToolbar: true,
        ReaderSettings: true,
        ProgressSpinner: true,
      },
    },
  })
}

/** 阅读器根元素（wrapper.element 在此环境是 VTU 的 app 容器，不能用来比对）。 */
function rootEl(wrapper: VueWrapper): Element {
  return wrapper.find('.image-reader').element
}

/** defineExpose 的运行时读取（模板层无需感知的测试观察口）。 */
function exposed(wrapper: VueWrapper): { isFullscreen: boolean; toggleChrome: () => void } {
  return wrapper.vm as unknown as { isFullscreen: boolean; toggleChrome: () => void }
}

/** chrome 预隐藏语义的可观察面：seek bar 的滑出类。 */
function chromeHidden(wrapper: VueWrapper): boolean {
  return wrapper.find('.image-reader__seekbar').classes().includes('image-reader__seekbar--hidden')
}

describe('ImageReader T1b — Wake Lock 屏幕常亮', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    setActivePinia(createPinia())
    prefsWithReader()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    // 逆序还原：同键覆盖（document.hidden true→false…）按栈语义逐层退出。
    propRestores.splice(0).reverse().forEach((restore) => restore())
  })

  it('acquires the screen wake lock on mount and releases it on unmount', async () => {
    const wl = installWakeLock()
    wrapper = mountReader()
    await flushPromises()
    expect(wl.request).toHaveBeenCalledTimes(1)
    expect(wl.request).toHaveBeenCalledWith('screen')
    expect(wl.sentinels).toHaveLength(1)

    wrapper.unmount()
    wrapper = undefined
    await flushPromises()
    expect(wl.sentinels[0].release).toHaveBeenCalledTimes(1)
  })

  it('degrades silently on platforms without navigator.wakeLock (no mock installed)', async () => {
    wrapper = mountReader()
    await flushPromises()
    // 组件照常工作，卸载不抛错。
    expect(wrapper.find('.image-reader').exists()).toBe(true)
    wrapper.unmount()
    wrapper = undefined
    await flushPromises()
  })

  it('stays silent while the device-local switch is off and follows toggles', async () => {
    const wl = installWakeLock()
    wrapper = mountReader({ wakeLock: false })
    await flushPromises()
    expect(wl.request).not.toHaveBeenCalled()

    await wrapper.setProps({ wakeLock: true })
    await flushPromises()
    expect(wl.request).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ wakeLock: false })
    await flushPromises()
    expect(wl.sentinels[0].release).toHaveBeenCalledTimes(1)

    // 关掉后再开：重新获取一把新锁。
    await wrapper.setProps({ wakeLock: true })
    await flushPromises()
    expect(wl.request).toHaveBeenCalledTimes(2)
    expect(wl.sentinels[1].released).toBe(false)
  })

  it('releases on visibilitychange→hidden and re-acquires on →visible', async () => {
    const wl = installWakeLock()
    wrapper = mountReader()
    await flushPromises()
    expect(wl.sentinels).toHaveLength(1)

    // 浏览器在隐藏时会自动释放 lock；组件主动同步本地引用（幂等）。
    dispatchVisibility(true)
    await flushPromises()
    expect(wl.sentinels[0].release).toHaveBeenCalledTimes(1)
    expect(wl.sentinels[0].released).toBe(true)

    // 回到可见 → 重新获取。
    dispatchVisibility(false)
    await flushPromises()
    expect(wl.request).toHaveBeenCalledTimes(2)
    expect(wl.sentinels[1].released).toBe(false)

    wrapper.unmount()
    wrapper = undefined
    await flushPromises()
    expect(wl.sentinels[1].release).toHaveBeenCalledTimes(1)
  })

  it('does not request while the document is hidden (acquire waits for visible)', async () => {
    const wl = installWakeLock()
    dispatchVisibility(true) // 先置 hidden 再挂载（后台标签直开阅读器）
    wrapper = mountReader()
    await flushPromises()
    expect(wl.request).not.toHaveBeenCalled()

    dispatchVisibility(false)
    await flushPromises()
    expect(wl.request).toHaveBeenCalledTimes(1)
  })

  it('swallows request rejection (denied / low battery) and unmounts safely', async () => {
    const wl = installWakeLock()
    wl.request.mockRejectedValueOnce(new Error('NotAllowedError'))
    wrapper = mountReader()
    await flushPromises()
    // 被拒不记账、组件存活。
    expect(wl.request).toHaveBeenCalledTimes(1)
    expect(wl.sentinels).toHaveLength(0)
    expect(wrapper.find('.image-reader').exists()).toBe(true)

    wrapper.unmount()
    wrapper = undefined
    await flushPromises()
  })
})

describe('ImageReader T1c — 真全屏（Fullscreen API 接管 reader.fullscreen）', () => {
  let wrapper: VueWrapper | undefined
  let fs: ReturnType<typeof installFullscreen>

  beforeEach(() => {
    setActivePinia(createPinia())
    fs = installFullscreen()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    // 逆序还原：同键覆盖（document.hidden true→false…）按栈语义逐层退出。
    propRestores.splice(0).reverse().forEach((restore) => restore())
  })

  it('requests fullscreen on the reader root element on mount when the pref is on', async () => {
    prefsWithReader({ fullscreen: true })
    wrapper = mountReader()
    await flushPromises()
    expect(fs.requestFullscreen).toHaveBeenCalledTimes(1)
    expect(fs.current()).toBe(rootEl(wrapper))
    expect(exposed(wrapper).isFullscreen).toBe(true)
    // 预隐藏 chrome 语义不变：真全屏进入即藏 chrome。
    expect(chromeHidden(wrapper)).toBe(true)
  })

  it('does nothing when the pref is off (existing behavior preserved)', async () => {
    prefsWithReader({ fullscreen: false })
    wrapper = mountReader()
    await flushPromises()
    expect(fs.requestFullscreen).not.toHaveBeenCalled()
    expect(exposed(wrapper).isFullscreen).toBe(false)
    // 默认 chrome 可见。
    expect(chromeHidden(wrapper)).toBe(false)
  })

  it('falls back silently to the pre-hide chrome when requestFullscreen is rejected', async () => {
    prefsWithReader({ fullscreen: true })
    fs.requestFullscreen.mockRejectedValueOnce(new Error('document not active'))
    wrapper = mountReader()
    await flushPromises()
    expect(fs.requestFullscreen).toHaveBeenCalledTimes(1)
    expect(fs.current()).toBeNull()
    expect(exposed(wrapper).isFullscreen).toBe(false)
    // 回退态 = 现状行为：chrome 仍按偏好预隐藏，tap 语义不变。
    expect(chromeHidden(wrapper)).toBe(true)
    // 卸载时不误调 exitFullscreen（从未进入全屏）。
    wrapper.unmount()
    wrapper = undefined
    await flushPromises()
    expect(fs.exitFullscreen).not.toHaveBeenCalled()
  })

  it('syncs internal state on fullscreenchange when the user exits with Esc', async () => {
    prefsWithReader({ fullscreen: true })
    wrapper = mountReader()
    await flushPromises()
    expect(exposed(wrapper).isFullscreen).toBe(true)

    // 用户 Esc：浏览器清空 fullscreenElement 并派发 fullscreenchange。
    fs.clear()
    await flushPromises()
    expect(exposed(wrapper).isFullscreen).toBe(false)
    expect(fs.exitFullscreen).not.toHaveBeenCalled() // UI 不得重复/反向操作

    // chrome 切换语义不乱：tap 唤出 → 预隐藏类移除。
    exposed(wrapper).toggleChrome()
    await flushPromises()
    expect(chromeHidden(wrapper)).toBe(false)
  })

  it('exits fullscreen when the preference is switched off mid-reading', async () => {
    prefsWithReader({ fullscreen: true })
    wrapper = mountReader()
    await flushPromises()
    expect(fs.current()).not.toBeNull()

    prefsWithReader({ fullscreen: false })
    await flushPromises()
    expect(fs.exitFullscreen).toHaveBeenCalledTimes(1)
    expect(fs.current()).toBeNull()
  })

  it('enters fullscreen when prefs arrive after mount (deep link, prefs late)', async () => {
    // 深链直进阅读器：挂载时 prefs 尚未加载（null），onMounted 的请求被跳过。
    wrapper = mountReader()
    await flushPromises()
    expect(fs.requestFullscreen).not.toHaveBeenCalled()
    expect(exposed(wrapper).isFullscreen).toBe(false)

    // prefs 后到（null → 加载完成，fullscreen=true）——watch 这一跳补进真全屏。
    prefsWithReader({ fullscreen: true })
    await flushPromises()
    expect(fs.requestFullscreen).toHaveBeenCalledTimes(1)
    expect(fs.current()).toBe(rootEl(wrapper))
    expect(exposed(wrapper).isFullscreen).toBe(true)
    expect(chromeHidden(wrapper)).toBe(true)
  })

  it('does not request fullscreen when late prefs load with the pref off', async () => {
    wrapper = mountReader() // prefs 晚到且为关
    await flushPromises()
    prefsWithReader({ fullscreen: false })
    await flushPromises()
    expect(fs.requestFullscreen).not.toHaveBeenCalled()
    expect(fs.exitFullscreen).not.toHaveBeenCalled()
  })

  it('enters fullscreen when the preference is switched on mid-reading', async () => {
    prefsWithReader({ fullscreen: false })
    wrapper = mountReader()
    await flushPromises()
    expect(fs.requestFullscreen).not.toHaveBeenCalled()

    prefsWithReader({ fullscreen: true })
    await flushPromises()
    expect(fs.requestFullscreen).toHaveBeenCalledTimes(1)
    expect(exposed(wrapper).isFullscreen).toBe(true)
  })

  it('restores (exits) fullscreen on unmount — leaving the route reverts it', async () => {
    prefsWithReader({ fullscreen: true })
    wrapper = mountReader()
    await flushPromises()
    expect(fs.current()).not.toBeNull()

    wrapper.unmount()
    wrapper = undefined
    await flushPromises()
    expect(fs.exitFullscreen).toHaveBeenCalledTimes(1)
    expect(fs.current()).toBeNull()
  })
})

describe('ImageReader Wave-2 T2 — brightness 压暗遮罩（系数三端统一 0.87）', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    setActivePinia(createPinia())
    prefsWithReader()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    propRestores.splice(0).reverse().forEach((restore) => restore())
  })

  function maskOpacity(): string {
    return (wrapper!.find('.image-reader__mask').element as HTMLElement).style.opacity
  }

  it('shows no mask at 0 (follow system)', async () => {
    wrapper = mountReader({ brightness: 0 })
    await flushPromises()
    expect(maskOpacity()).toBe('0')
  })

  it('applies (1 - v/100) * 0.87 inside the dimming range', async () => {
    wrapper = mountReader({ brightness: 50 })
    await flushPromises()
    expect(maskOpacity()).toBe(String((1 - 50 / 100) * 0.87))

    await wrapper.setProps({ brightness: 30 })
    await flushPromises()
    expect(maskOpacity()).toBe(String((1 - 30 / 100) * 0.87))
  })

  it('is mask-free again at the brightest end (100)', async () => {
    wrapper = mountReader({ brightness: 100 })
    await flushPromises()
    expect(maskOpacity()).toBe('0')
  })
})
