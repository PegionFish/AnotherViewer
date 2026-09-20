import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ReaderSettings from '../ReaderSettings.vue'

/**
 * 阅读器快捷面板（components/reader/ReaderSettings.vue）spec：
 * - T1b：屏幕常亮开关是设备本地偏好的 UI 入口——只向上抛 v-model 事件，
 *   持久化由父级（ReaderView → reader-settings localStorage）负责。
 * - R1-A6：亮度滑杆绑定设备本地扩展值 0–200（1–100 压暗 / 101–200 提亮），
 *   文案区分两段。
 * - R1-A7：屏幕方向三态（跟随系统/竖屏/横屏），同为设备本地偏好入口。
 * - R1-A5：跳页快速入口——只上抛 `jump`，对话框逻辑在 PageJumpDialog。
 */

function mountSheet(overrides: Record<string, unknown> = {}) {
  return mount(ReaderSettings, {
    props: {
      visible: true,
      direction: 'ltr',
      pageMode: 'auto',
      zoom: 1,
      autoPlay: { enabled: false, intervalMs: 3000 },
      brightnessLevel: 0,
      wakeLock: true,
      orientationLock: 'none',
      currentPage: 4,
      totalPages: 10,
      ...overrides,
    },
  })
}

describe('ReaderSettings (阅读器快捷面板) T1b — 屏幕常亮开关', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    document.body.innerHTML = ''
  })

  it('renders the device-local switch and reflects the current value', () => {
    wrapper = mountSheet()
    const row = wrapper.find('.reader-settings__wakelock')
    expect(row.exists()).toBe(true)
    const sw = row.find('[role="switch"]')
    expect(sw.attributes('aria-checked')).toBe('true')
    expect(row.text()).toContain('屏幕常亮')

    wrapper.unmount()
    wrapper = mountSheet({ wakeLock: false })
    expect(
      wrapper.find('.reader-settings__wakelock [role="switch"]').attributes('aria-checked'),
    ).toBe('false')
  })

  it('emits update:wakeLock with the flipped value on click (persistence stays upstream)', async () => {
    wrapper = mountSheet({ wakeLock: false })
    await wrapper.find('.reader-settings__wakelock [role="switch"]').trigger('click')
    expect(wrapper.emitted('update:wakeLock')).toEqual([[true]])
    // 面板本身不落盘——不发任何自定义持久化事件。
    expect(wrapper.emitted('close')).toBeUndefined()

    wrapper.unmount()
    wrapper = mountSheet({ wakeLock: true })
    await wrapper.find('.reader-settings__wakelock [role="switch"]').trigger('click')
    expect(wrapper.emitted('update:wakeLock')).toEqual([[false]])
  })
})

describe('ReaderSettings R1-A6 — 亮度滑杆 0–200（本地扩展值）', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    document.body.innerHTML = ''
  })

  it('binds the local extended level with a 0–200 range', () => {
    wrapper = mountSheet({ brightnessLevel: 150 })
    const slider = wrapper.find<HTMLInputElement>('.reader-settings__slider')
    expect(slider.attributes('max')).toBe('200')
    expect(slider.element.value).toBe('150')
  })

  it('emits update:brightnessLevel with the raw slider value', async () => {
    wrapper = mountSheet()
    const slider = wrapper.find<HTMLInputElement>('.reader-settings__slider')
    slider.element.value = '180'
    await slider.trigger('input')
    expect(wrapper.emitted('update:brightnessLevel')).toEqual([[180]])
  })

  it('labels the two segments: 系统 / 压暗 xx% / 提亮 +xx%', () => {
    wrapper = mountSheet({ brightnessLevel: 0 })
    expect(wrapper.find('.reader-settings__brightness-value').text()).toBe('系统')

    wrapper.unmount()
    wrapper = mountSheet({ brightnessLevel: 40 })
    expect(wrapper.find('.reader-settings__brightness-value').text()).toBe('40%')

    wrapper.unmount()
    wrapper = mountSheet({ brightnessLevel: 160 })
    expect(wrapper.find('.reader-settings__brightness-value').text()).toBe('+60%')
  })

  it('shows the two-segment hint (压暗 0–100 / 提亮 101–200，仅本机)', () => {
    wrapper = mountSheet()
    const hint = wrapper.find('.reader-settings__section .reader-settings__hint')
    expect(hint.text()).toContain('压暗 0–100')
    expect(hint.text()).toContain('提亮 101–200')
    expect(hint.text()).toContain('仅本机')
  })
})

describe('ReaderSettings R1-A7 — 屏幕方向三态（设备本地）', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    document.body.innerHTML = ''
  })

  it('renders three options and reflects the active one', () => {
    wrapper = mountSheet({ orientationLock: 'portrait' })
    const group = wrapper.find('[aria-labelledby="reader-settings-orientation"]')
    expect(group.exists()).toBe(true)
    const radios = group.findAll('[role="radio"]')
    expect(radios.map((r) => r.text())).toEqual(['跟随系统', '竖屏', '横屏'])
    expect(radios.map((r) => r.attributes('aria-checked'))).toEqual([
      'false',
      'true',
      'false',
    ])
  })

  it('emits update:orientationLock with the picked value', async () => {
    wrapper = mountSheet({ orientationLock: 'none' })
    const radios = wrapper
      .find('[aria-labelledby="reader-settings-orientation"]')
      .findAll('[role="radio"]')

    await radios[2]!.trigger('click')
    expect(wrapper.emitted('update:orientationLock')).toEqual([['landscape']])

    await radios[0]!.trigger('click')
    expect(wrapper.emitted('update:orientationLock')).toEqual([['landscape'], ['none']])
  })
})

describe('ReaderSettings R1-A5 — 跳页入口', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    document.body.innerHTML = ''
  })

  it('shows the jump entry with the current position and the G shortcut hint', () => {
    wrapper = mountSheet({ currentPage: 4, totalPages: 10 })
    const jump = wrapper.find('.reader-settings__jump')
    expect(jump.exists()).toBe(true)
    expect(jump.text()).toContain('跳页')
    expect(jump.text()).toContain('第 4 / 10 页')
    expect(jump.text()).toContain('G')
  })

  it('clamps the position display and hides the entry for unknown page counts', () => {
    wrapper = mountSheet({ currentPage: 99, totalPages: 10 })
    expect(wrapper.find('.reader-settings__jump').text()).toContain('第 10 / 10 页')

    wrapper.unmount()
    wrapper = mountSheet({ totalPages: 0 })
    expect(wrapper.find('.reader-settings__jump').exists()).toBe(false)
  })

  it('emits jump on click (对话框逻辑在父级 ImageReader / PageJumpDialog)', async () => {
    wrapper = mountSheet()
    await wrapper.find('.reader-settings__jump').trigger('click')
    expect(wrapper.emitted('jump')).toHaveLength(1)
    expect(wrapper.emitted('close')).toBeUndefined()
  })
})
