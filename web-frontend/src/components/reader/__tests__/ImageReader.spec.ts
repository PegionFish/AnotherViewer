import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ImageReader from '../ImageReader.vue'
import { usePreferencesStore } from '@/stores/preferences'
import { DEFAULT_PREFERENCES, DEFAULT_READER_PREFERENCES } from '@/api/preferences'

/**
 * ImageReader 组件级 spec：
 * - T1b：Wake Lock 屏幕常亮生命周期（visibilitychange / 挂载卸载）。
 * - T1c：真全屏（Fullscreen API 接管 reader.fullscreen，fullscreenchange
 *   是唯一事实源）。
 * - R1-A6：亮度 0–200——0–100 遮罩压暗一期语义不变，101–200 页面容器
 *   CSS filter 提亮且无遮罩。
 * - R1-A5：跳页——G 键/设置面板菜单入口、SeekBarPanel 同一 seek 通路、
 *   Esc（handleBack 兜底）先关对话框。
 * - R1-A7：屏幕方向锁定——全屏联动 lock/unlock、被拒静默降级、卸载必解锁
 *   （含在途请求迟到兑现的幽灵锁兜底）。
 *
 * chrome 子组件全部 stub——这里只关心 ImageReader 自身的文档级监听与
 * 挂载/卸载行为。happy-dom 坑（沿用既有手法）：document 实例不走全局
 * prototype，fullscreen/hidden 需打实例自有属性；focus 只对已接入文档的
 * 元素生效。
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
      brightnessLevel: 0,
      autoPlay: { enabled: false, intervalMs: 3000 },
      autoPlayProgress: 0,
      wakeLock: true,
      orientationLock: 'none',
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
        PageJumpDialog: true,
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

describe('ImageReader R1-A6 — 亮度 0–200（0–100 遮罩压暗不变 + 101–200 filter 提亮）', () => {
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

  function pagesFilter(): string {
    return (wrapper!.find('.image-reader__pages').element as HTMLElement).style.filter
  }

  it('shows no mask at 0 (follow system)', async () => {
    wrapper = mountReader({ brightnessLevel: 0 })
    await flushPromises()
    expect(maskOpacity()).toBe('0')
    expect(pagesFilter()).toBe('')
  })

  it('applies (1 - v/100) * 0.87 inside the dimming range — 一期语义不变', async () => {
    wrapper = mountReader({ brightnessLevel: 50 })
    await flushPromises()
    expect(maskOpacity()).toBe(String((1 - 50 / 100) * 0.87))
    expect(pagesFilter()).toBe('')

    await wrapper.setProps({ brightnessLevel: 30 })
    await flushPromises()
    expect(maskOpacity()).toBe(String((1 - 30 / 100) * 0.87))
  })

  it('is mask-free again at the dimming ceiling (100), still no filter', async () => {
    wrapper = mountReader({ brightnessLevel: 100 })
    await flushPromises()
    expect(maskOpacity()).toBe('0')
    expect(pagesFilter()).toBe('')
  })

  it('boosts page content via CSS filter and drops the mask in 101–200', async () => {
    wrapper = mountReader({ brightnessLevel: 150 })
    await flushPromises()
    // 提亮段：filter 生效（150 → 1.5×）且遮罩恒为 0。
    expect(pagesFilter()).toBe('brightness(1.5)')
    expect(maskOpacity()).toBe('0')

    await wrapper.setProps({ brightnessLevel: 200 })
    await flushPromises()
    expect(pagesFilter()).toBe('brightness(2)')
    expect(maskOpacity()).toBe('0')

    // 回到 0–100 段：filter 撤除、遮罩回归一期语义。
    await wrapper.setProps({ brightnessLevel: 40 })
    await flushPromises()
    expect(pagesFilter()).toBe('')
    expect(maskOpacity()).toBe(String((1 - 40 / 100) * 0.87))
  })
})

describe('ImageReader R1-A5 — 跳页（G 键 + 菜单入口 + SeekBarPanel 同一 seek 通路）', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    setActivePinia(createPinia())
    prefsWithReader()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    propRestores.splice(0).reverse().forEach((restore) => restore())
    document.body.innerHTML = ''
  })

  /** window 级合成按键（target 为 window，非表单控件）。 */
  function pressKey(key: string): void {
    window.dispatchEvent(new KeyboardEvent('keydown', { key }))
  }

  /**
   * 对话框是否处于打开态。不能用 DOM 存活判断关闭：happy-dom 不派发
   * transitionend，Transition 的 leave 元素会滞留——改看组件 visible prop。
   */
  function jumpDialogOpen(wrapper: VueWrapper): boolean {
    const dialog = wrapper.findComponent({ name: 'PageJumpDialog' })
    return dialog.exists() && (dialog.props('visible') as boolean)
  }

  it('opens the jump dialog on the G key and closes it via handleBack (Esc 兜底路径)', async () => {
    wrapper = mountReader()
    await flushPromises()
    expect(jumpDialogOpen(wrapper)).toBe(false)

    pressKey('g')
    await flushPromises()
    expect(jumpDialogOpen(wrapper)).toBe(true)

    // Esc（焦点在对话框外时经 useKeyboardNav → handleBack）先关对话框，
    // 不退出阅读器（不向上抛 back）。
    const exposed = wrapper.vm as unknown as { handleBack: () => void }
    exposed.handleBack()
    await flushPromises()
    expect(jumpDialogOpen(wrapper)).toBe(false)
    expect(wrapper.emitted('back')).toBeUndefined()
  })

  it('accepts the uppercase G alias', async () => {
    wrapper = mountReader()
    await flushPromises()
    pressKey('G')
    await flushPromises()
    expect(jumpDialogOpen(wrapper)).toBe(true)
  })

  it('does not hijack G typed into form controls (dialog inputs stay usable)', async () => {
    wrapper = mountReader()
    await flushPromises()
    const input = document.createElement('input')
    document.body.appendChild(input)
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'g', bubbles: true }))
    await flushPromises()
    expect(jumpDialogOpen(wrapper)).toBe(false)
  })

  it('does not offer jumping with unknown page counts (totalPages=0)', async () => {
    wrapper = mountReader({ totalPages: 0 })
    await flushPromises()
    pressKey('g')
    await flushPromises()
    expect(jumpDialogOpen(wrapper)).toBe(false)
  })

  it('opens from the settings-sheet menu entry (jump event from the sheet)', async () => {
    wrapper = mountReader()
    await flushPromises()
    const sheet = wrapper.findComponent({ name: 'ReaderSettings' })
    expect(sheet.exists()).toBe(true)
    ;(sheet.vm as unknown as { $emit: (e: string) => void }).$emit('jump')
    await flushPromises()
    expect(jumpDialogOpen(wrapper)).toBe(true)
    // 菜单进入跳页时设置面板先收起。
    expect(
      (wrapper.findComponent({ name: 'ReaderSettings' }).props() as { visible: boolean }).visible,
    ).toBe(false)
  })

  it('commits the jump through the same seek pathway as the seek bar (1-based)', async () => {
    wrapper = mountReader({ currentPage: 4 })
    await flushPromises()
    pressKey('g')
    await flushPromises()

    const dialog = wrapper.findComponent({ name: 'PageJumpDialog' })
    expect(dialog.exists()).toBe(true)
    // 对话框收到 0-based currentPage（对话框内部负责 +1 展示）。
    expect(dialog.props('currentPage')).toBe(4)
    expect(dialog.props('totalPages')).toBe(10)

    ;(dialog.vm as unknown as { $emit: (e: string, v: number) => void }).$emit('jump', 7)
    await flushPromises()
    expect(wrapper.emitted('update:currentPage')).toEqual([[6]]) // page - 1
    expect(jumpDialogOpen(wrapper)).toBe(false)
  })

  it('closing the dialog without a jump does not touch the current page', async () => {
    wrapper = mountReader({ currentPage: 4 })
    await flushPromises()
    pressKey('g')
    await flushPromises()
    const dialog = wrapper.findComponent({ name: 'PageJumpDialog' })
    ;(dialog.vm as unknown as { $emit: (e: string) => void }).$emit('close')
    await flushPromises()
    expect(wrapper.emitted('update:currentPage')).toBeUndefined()
    expect(jumpDialogOpen(wrapper)).toBe(false)
  })
})

describe('ImageReader R1-A7 — 屏幕方向锁定（全屏联动 / 退全屏解锁 / 卸载必解锁）', () => {
  let wrapper: VueWrapper | undefined
  let fs: ReturnType<typeof installFullscreen>

  interface FakeOrientation {
    lock: ReturnType<typeof vi.fn>
    unlock: ReturnType<typeof vi.fn>
  }

  function installScreenOrientation(): FakeOrientation {
    const orientation: FakeOrientation = {
      lock: vi.fn(async () => {}),
      unlock: vi.fn(() => {}),
    }
    overrideProp(globalThis, 'screen', {
      configurable: true,
      value: { orientation },
    })
    return orientation
  }

  /** lock 迟迟不兑现的挂起句柄（在途请求 + 卸载竞态用）。 */
  function deferredLock(orientation: FakeOrientation): Array<() => void> {
    const resolvers: Array<() => void> = []
    orientation.lock.mockImplementation(
      () => new Promise<void>((resolve) => resolvers.push(resolve)),
    )
    return resolvers
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    fs = installFullscreen()
    prefsWithReader({ fullscreen: true })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    propRestores.splice(0).reverse().forEach((restore) => restore())
  })

  it('locks portrait when entering fullscreen with the portrait pref', async () => {
    const orientation = installScreenOrientation()
    wrapper = mountReader({ orientationLock: 'portrait' })
    await flushPromises()
    expect(fs.requestFullscreen).toHaveBeenCalledTimes(1)
    expect(orientation.lock).toHaveBeenCalledTimes(1)
    expect(orientation.lock).toHaveBeenCalledWith('portrait')
    expect(orientation.unlock).not.toHaveBeenCalled()
  })

  it('locks landscape when entering fullscreen with the landscape pref', async () => {
    const orientation = installScreenOrientation()
    wrapper = mountReader({ orientationLock: 'landscape' })
    await flushPromises()
    expect(orientation.lock).toHaveBeenCalledWith('landscape')
  })

  it('never locks with the default follow-system pref (none)', async () => {
    const orientation = installScreenOrientation()
    wrapper = mountReader({ orientationLock: 'none' })
    await flushPromises()
    expect(orientation.lock).not.toHaveBeenCalled()
    expect(orientation.unlock).not.toHaveBeenCalled()
  })

  it('does not lock outside fullscreen even with a lock pref', async () => {
    const orientation = installScreenOrientation()
    prefsWithReader({ fullscreen: false })
    wrapper = mountReader({ orientationLock: 'portrait' })
    await flushPromises()
    expect(fs.requestFullscreen).not.toHaveBeenCalled()
    expect(orientation.lock).not.toHaveBeenCalled()
  })

  it('re-locks with the new value when the pref switches mid-reading', async () => {
    const orientation = installScreenOrientation()
    wrapper = mountReader({ orientationLock: 'portrait' })
    await flushPromises()
    expect(orientation.lock).toHaveBeenCalledWith('portrait')

    await wrapper.setProps({ orientationLock: 'landscape' })
    await flushPromises()
    expect(orientation.lock).toHaveBeenCalledTimes(2)
    expect(orientation.lock).toHaveBeenLastCalledWith('landscape')
  })

  it('unlocks when the pref returns to none mid-reading', async () => {
    const orientation = installScreenOrientation()
    wrapper = mountReader({ orientationLock: 'portrait' })
    await flushPromises()
    expect(orientation.unlock).not.toHaveBeenCalled()

    await wrapper.setProps({ orientationLock: 'none' })
    await flushPromises()
    expect(orientation.unlock).toHaveBeenCalledTimes(1)
  })

  it('unlocks when fullscreen exits (fullscreenchange 联动)', async () => {
    const orientation = installScreenOrientation()
    wrapper = mountReader({ orientationLock: 'portrait' })
    await flushPromises()
    expect(orientation.lock).toHaveBeenCalledTimes(1)

    // 用户 Esc 退出全屏：浏览器清空 fullscreenElement 并派发 fullscreenchange。
    fs.clear()
    await flushPromises()
    expect(orientation.unlock).toHaveBeenCalledTimes(1)

    // 重新进全屏 → 重锁。
    await rootEl(wrapper).requestFullscreen()
    await flushPromises()
    expect(orientation.lock).toHaveBeenCalledTimes(2)
    expect(orientation.unlock).toHaveBeenCalledTimes(1)
  })

  it('degrades silently when the lock is rejected (iOS Safari / 非全屏环境)', async () => {
    const orientation = installScreenOrientation()
    orientation.lock.mockRejectedValueOnce(new Error('NotSupportedError'))
    wrapper = mountReader({ orientationLock: 'portrait' })
    await flushPromises()
    expect(orientation.lock).toHaveBeenCalledTimes(1)
    // 被拒不记账：卸载时不会去解一把从未持有的锁。
    wrapper.unmount()
    wrapper = undefined
    await flushPromises()
    expect(orientation.unlock).not.toHaveBeenCalled()
  })

  it('unlocks on unmount — 方向锁绝不越过阅读器会话泄漏', async () => {
    const orientation = installScreenOrientation()
    wrapper = mountReader({ orientationLock: 'portrait' })
    await flushPromises()
    expect(orientation.lock).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    wrapper = undefined
    await flushPromises()
    expect(orientation.unlock).toHaveBeenCalledTimes(1)
  })

  it('unlocks an in-flight lock request that settles after unmount (幽灵锁兜底)', async () => {
    const orientation = installScreenOrientation()
    const resolvers = deferredLock(orientation)
    wrapper = mountReader({ orientationLock: 'portrait' })
    await flushPromises()
    expect(orientation.lock).toHaveBeenCalledTimes(1)
    expect(orientation.unlock).not.toHaveBeenCalled() // 仍在途

    // 卸载：先对在途请求兜底 unlock。
    wrapper.unmount()
    wrapper = undefined
    await flushPromises()
    expect(orientation.unlock).toHaveBeenCalledTimes(1)

    // 迟到兑现：post-await 守卫再补一次 unlock，锁不落袋。
    resolvers[0]?.()
    await flushPromises()
    expect(orientation.unlock).toHaveBeenCalledTimes(2)
  })

  it('stays silent on platforms without screen.orientation (特性检测降级)', async () => {
    wrapper = mountReader({ orientationLock: 'portrait' })
    await flushPromises()
    expect(wrapper.find('.image-reader').exists()).toBe(true)
    wrapper.unmount()
    wrapper = undefined
    await flushPromises()
  })
})
