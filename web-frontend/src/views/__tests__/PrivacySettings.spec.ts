import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrivacySettings from '../settings/PrivacySettings.vue'
import { preferencesApi, type Preferences } from '@/api/preferences'
import { privacyApi, type PrivacyMaskState } from '@/api/privacy'
import { setPrivacyMaskEnabled } from '@/utils/privacyMask'
import { usePreferencesStore } from '@/stores/preferences'
import { AppSwitch, PrefCard, PrefRow, SectionHeader } from '@/components/form'

vi.mock('@/api/preferences', () => ({
  preferencesApi: { get: vi.fn(), update: vi.fn() },
}))

vi.mock('@/api/privacy', () => ({
  privacyApi: {
    getMask: vi.fn().mockResolvedValue({ enabled: false }),
    setMask: vi.fn().mockResolvedValue({ enabled: false }),
  },
}))

function defaultPrefs(): Preferences {
  return {
    general: {
      theme: 'dark',
      themeAutoSwitch: false,
      launchPage: 'homepage',
      showReadProgress: true,
      detailSize: 'long',
      thumbSize: 'middle',
      historyInfoSize: 100,
      showJpnTitle: false,
      showGalleryPages: false,
      showTagTranslations: true,
      showGalleryComment: true,
      showGalleryRating: true,
      showEhEvents: true,
      showEhLimits: true,
      showUploader: false,
      showPostedTime: false,
      defaultFavoriteSlot: 0,
      favoriteSlotNames: '',
      recentSearchMax: 10,
    },
    reader: {
      readingDirection: 'rtl',
      pageMode: 'dual',
      firstPageCover: true,
      pageScaling: 'fit',
      startPosition: 'top_right',
      autoPlayIntervalSec: 2,
      showProgress: true,
      showPageInterval: true,
      fullscreen: true,
      brightness: 0,
      backgroundColor: 'black',
      tapZoneScheme: 'threeZone',
      keyboardPaging: true,
      zoomStep: 1.5,
      maxZoom: 5,
      dualPageGap: 8,
      splitWidePages: false,
      preloadCount: 2,
      pageTransition: 'slide',
    },
    privacy: { enableAnalytics: true },
  }
}

describe('PrivacySettings (隐私设置)', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    setActivePinia(createPinia())
    // 打码是模块级状态，会跨用例泄漏——统一复位。
    setPrivacyMaskEnabled(false)
    vi.mocked(preferencesApi.get).mockResolvedValue(defaultPrefs())
    vi.mocked(preferencesApi.update).mockResolvedValue(defaultPrefs())
    vi.mocked(privacyApi.setMask).mockResolvedValue({ enabled: false })
  })

  afterEach(() => {
    wrapper?.unmount()
    setPrivacyMaskEnabled(false)
    vi.clearAllMocks()
  })

  async function mountView() {
    wrapper = mount(PrivacySettings)
    await flushPromises()
    return wrapper
  }

  function analyticsSwitch(w: VueWrapper) {
    return w.findAllComponents(AppSwitch).find((s) => s.attributes('aria-label') === '启用统计')!
  }

  it('renders the shared card structure with the mask row above analytics', async () => {
    const w = await mountView()
    expect(w.findComponent(SectionHeader).text()).toContain('隐私')
    expect(w.findComponent(PrefCard).exists()).toBe(true)
    // 内容打码模式自管理面板「高级 > 隐私」迁入（F5），位于启用统计上方。
    const titles = w.findAllComponents(PrefRow).map((r) => r.props('title'))
    expect(titles).toEqual(['内容打码模式', '启用统计'])

    const maskRow = w.findAllComponents(PrefRow)[0]
    expect(maskRow.props('icon')).toBe('sec-primary')
    expect(maskRow.props('summary')).toBe('标题以内容序列号 #gid 显示，图片替换为占位符——便于截图协作')

    const analyticsRow = w.findAllComponents(PrefRow)[1]
    expect(analyticsRow.props('summary')).toBe('帮助改进应用体验')
    expect(analyticsRow.props('icon')).toBe('sec-primary')
  })

  it('renders the analytics AppSwitch with the original aria-label', async () => {
    const w = await mountView()
    const toggle = analyticsSwitch(w)
    expect(toggle.attributes('aria-label')).toBe('启用统计')
    expect(toggle.attributes('aria-checked')).toBe('true')
  })

  it('flips the analytics preference when the switch is clicked', async () => {
    const w = await mountView()
    const store = usePreferencesStore()
    expect(store.prefs!.privacy.enableAnalytics).toBe(true)
    await analyticsSwitch(w).find('button').trigger('click')
    expect(store.prefs!.privacy.enableAnalytics).toBe(false)
    expect(store.prefs!.general.theme).toBe('dark')
  })

  it('renders the privacy-mask AppSwitch with the migrated aria-label', async () => {
    const w = await mountView()
    const mask = w.findAllComponents(AppSwitch).find((s) => s.attributes('aria-label') === '内容打码模式')!
    expect(mask.attributes('aria-checked')).toBe('false')
  })

  it('toggles the privacy mask optimistically and persists to /privacy/mask', async () => {
    const w = await mountView()
    const mask = w.findAllComponents(AppSwitch).find((s) => s.attributes('aria-label') === '内容打码模式')!
    expect(mask.attributes('aria-checked')).toBe('false')

    await mask.trigger('click')
    // 乐观切换：本地展示层立即生效（<html> 类）；权威状态在服务端。
    expect(privacyApi.setMask).toHaveBeenCalledWith(true)
    expect(document.documentElement.classList.contains('privacy-mask')).toBe(true)
    await flushPromises()
    expect(mask.attributes('aria-checked')).toBe('true')

    await mask.trigger('click')
    expect(privacyApi.setMask).toHaveBeenLastCalledWith(false)
    expect(document.documentElement.classList.contains('privacy-mask')).toBe(false)
  })

  it('keeps the mask switch out of the preferencesStore debounce save', async () => {
    const w = await mountView()
    const mask = w.findAllComponents(AppSwitch).find((s) => s.attributes('aria-label') === '内容打码模式')!
    await mask.trigger('click')
    await flushPromises()
    // 打码权威持久化在 /privacy/mask，不得走 PUT /preferences。
    expect(preferencesApi.update).not.toHaveBeenCalled()
  })

  it('rolls the mask switch back with a snack when the server save fails', async () => {
    // 用未决 Promise 延迟拒绝：trigger 自带 nextTick 会冲掉微任务，
    // 直接 mockRejectedValue 会在断言乐观态前就触发回滚。
    let rejectMask!: (error: unknown) => void
    vi.mocked(privacyApi.setMask).mockImplementation(
      () =>
        new Promise<PrivacyMaskState>((_resolve, reject) => {
          rejectMask = reject
        }),
    )
    const w = await mountView()
    const mask = w.findAllComponents(AppSwitch).find((s) => s.attributes('aria-label') === '内容打码模式')!

    await mask.trigger('click')
    // 请求未决：乐观态成立（<html> 类 + 开关显示为开）。
    expect(privacyApi.setMask).toHaveBeenCalledWith(true)
    expect(document.documentElement.classList.contains('privacy-mask')).toBe(true)
    expect(mask.attributes('aria-checked')).toBe('true')

    rejectMask(new Error('boom'))
    await flushPromises()

    expect(mask.attributes('aria-checked')).toBe('false')
    expect(document.documentElement.classList.contains('privacy-mask')).toBe(false)
    expect(w.text()).toContain('打码状态保存失败')
  })

  it('shows the privacy note below the card', async () => {
    const w = await mountView()
    expect(w.find('.privacy-settings__note').text()).toContain('统计数据仅用于改进应用体验')
  })
})
