import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import AdminAdvanced from '../admin/AdminAdvanced.vue'
import { AppSelect, AppSwitch, PrefRow, SectionHeader } from '@/components/form'

describe('AdminAdvanced (高级)', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    wrapper?.unmount()
    vi.restoreAllMocks()
  })

  it('renders shared primitives and no native select', () => {
    wrapper = mount(AdminAdvanced)
    // 2026-09-07 去重：通用组（F4）与隐私组（F5）删除，数据组只剩清除本地数据（F1）。
    expect(wrapper.findAllComponents(SectionHeader).map((h) => h.props('title'))).toEqual([
      '同步策略',
      '数据',
    ])
    expect(wrapper.findAllComponents(PrefRow).map((r) => r.props('title'))).toEqual([
      '冲突仲裁策略',
      '自动同步间隔（秒）',
      '清除本地数据',
    ])
    expect(wrapper.text()).not.toContain('导出数据')
    expect(wrapper.text()).not.toContain('导入数据')
    expect(wrapper.text()).not.toContain('界面语言')
    expect(wrapper.text()).not.toContain('内容打码模式')
    expect(wrapper.find('select').exists()).toBe(false)
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
    expect(wrapper.find('.advanced__restart-banner').exists()).toBe(false)

    const selects = wrapper.findAllComponents(AppSelect)
    expect(selects.length).toBe(2)
    expect(selects[0].attributes('aria-label')).toBe('冲突仲裁策略')
    expect(selects[0].props('options')).toEqual([
      { value: 'device_priority', label: 'Android 优先（默认）' },
      { value: 'lww', label: '最后写入胜出' },
      { value: 'web_priority', label: 'WebUI 优先' },
    ])
    expect(selects[1].attributes('aria-label')).toBe('自动同步间隔')

    // 本页不再有任何开关（打码开关已迁往偏好「隐私」页）。
    expect(wrapper.findComponent(AppSwitch).exists()).toBe(false)
  })

  it('clears local data after confirmation, keeping auth keys', async () => {
    localStorage.setItem('token', 'keep-me')
    localStorage.setItem('username', 'bob')
    localStorage.setItem('anotherviewer-search-history', 'x')
    localStorage.setItem('anotherviewer-admin-download-ui', '{}')
    vi.spyOn(window, 'confirm')

    wrapper = mount(AdminAdvanced)
    await wrapper.find('[aria-label="清除本地数据"]').trigger('click')
    expect(wrapper.text()).toContain('删除此浏览器中所有本地的设置、缓存与搜索历史？')

    const clear = wrapper.findAll('button').find((b) => b.text() === '清除')!
    await clear.trigger('click')
    expect(localStorage.getItem('token')).toBe('keep-me')
    expect(localStorage.getItem('username')).toBe('bob')
    expect(localStorage.getItem('anotherviewer-search-history')).toBeNull()
    expect(localStorage.getItem('anotherviewer-admin-download-ui')).toBeNull()
    expect(wrapper.text()).toContain('已清除 2 项本地数据')
  })

  it('uses a trash icon for 清除本地数据 (F-UX4 icon semantics)', () => {
    wrapper = mount(AdminAdvanced)
    const button = wrapper.find('[aria-label="清除本地数据"]')
    expect(button.find('[aria-label="delete-dark"]').exists()).toBe(true)
  })
})
