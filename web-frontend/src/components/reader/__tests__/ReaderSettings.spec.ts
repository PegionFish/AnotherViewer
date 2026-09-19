import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ReaderSettings from '../ReaderSettings.vue'

/**
 * 阅读器快捷面板（components/reader/ReaderSettings.vue）的 T1b spec：
 * 屏幕常亮开关是设备本地偏好的 UI 入口——只向上抛 v-model 事件，持久化
 * 由父级（ReaderView → reader-settings localStorage）负责。
 */

function mountSheet(overrides: Record<string, unknown> = {}) {
  return mount(ReaderSettings, {
    props: {
      visible: true,
      direction: 'ltr',
      pageMode: 'auto',
      zoom: 1,
      autoPlay: { enabled: false, intervalMs: 3000 },
      brightness: 0,
      wakeLock: true,
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
