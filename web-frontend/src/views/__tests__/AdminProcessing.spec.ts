import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import AdminProcessing from '../admin/AdminProcessing.vue'
import { settingsApi, type Settings } from '@/api/settings'
import {
  processingApi,
  type ProcessingHistoryPage,
  type ProcessingTaskRecord,
} from '@/api/processing'
import { AppSelect, AppSwitch, AppTextField, PrefRow, SectionHeader } from '@/components/form'

vi.mock('@/api/settings', () => ({
  settingsApi: { get: vi.fn(), update: vi.fn() },
}))

vi.mock('@/api/processing', () => ({
  processingApi: {
    getActiveTasks: vi.fn(),
    getHistory: vi.fn(),
    retry: vi.fn(),
    cancel: vi.fn(),
  },
}))

function settingsWithProcessing(overrides: Partial<Settings['processing']> = {}): Settings {
  return {
    download: {
      path: '/data',
      downloadDelay: 1000,
      downloadTimeout: 30000,
      maxConcurrentGalleries: 2,
      maxConcurrentImages: 8,
    },
    cache: { path: '/cache', sizeMb: 5120 },
    smb: { enabled: false },
    security: { requireAuth: false, sessionTimeout: 86400 },
    processing: {
      enabled: false,
      defaultType: 'UPSCALE_2X',
      outputFormat: 'png',
      outputQuality: 90,
      entrypointUrl: 'http://192.168.6.141:9800',
      entrypointTokenSet: false,
      automationEnabled: false,
      periodicEnabled: false,
      periodicIntervalMinutes: 60,
      ...overrides,
    },
    proxy: { enabled: false, type: 'http', host: '', port: 0, username: '', password: '' },
  }
}

function taskFixture(overrides: Partial<ProcessingTaskRecord> = {}): ProcessingTaskRecord {
  const now = Date.now()
  return {
    taskId: 'proc-active001',
    galleryId: 7,
    title: 'Example Gallery',
    trigger: 'DOWNLOAD_AUTO',
    processingType: 'UPSCALE_2X',
    state: 'PROCESSING',
    pagesTotal: 10,
    pagesDone: 3,
    pagesFailed: 0,
    sourceDir: '/data/downloads/7',
    outputDir: '/cache/enhanced/7',
    epTaskIds: ['ep-task-1'],
    errorCode: '',
    errorMessage: '',
    createdAt: now - 70000,
    startedAt: now - 65000,
    finishedAt: 0,
    updatedAt: now - 1000,
    ...overrides,
  }
}

function historyPageFixture(
  items: ProcessingTaskRecord[],
  total = items.length,
): ProcessingHistoryPage {
  return { items, total, page: 0, size: 20 }
}

describe('AdminProcessing (图像处理)', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    vi.mocked(settingsApi.get).mockResolvedValue(settingsWithProcessing())
    vi.mocked(settingsApi.update).mockResolvedValue(true)
    vi.mocked(processingApi.getActiveTasks).mockResolvedValue([])
    vi.mocked(processingApi.getHistory).mockResolvedValue(historyPageFixture([]))
    vi.mocked(processingApi.retry).mockResolvedValue({
      taskId: 'proc-retried1',
      galleryId: 7,
      totalPages: 10,
      state: 'PENDING',
    })
    vi.mocked(processingApi.cancel).mockResolvedValue(undefined)
  })

  afterEach(() => {
    // 先在当前计时器体制下卸载（fake interval 由 fake clearInterval 清掉），
    // 再恢复真实计时器——避免轮询 interval 跨测试泄漏。
    wrapper?.unmount()
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  /** 全部用例统一在 fake timers 下挂载：600ms 防抖与 3s 轮询都由假时钟驱动，
   *  flush 用 advanceTimersByTimeAsync(0)（flushPromises 依赖 setImmediate，假时钟下不可用）。 */
  async function mountView(attach = false) {
    wrapper = mount(AdminProcessing, attach ? { attachTo: document.body } : {})
    await vi.advanceTimersByTimeAsync(0)
    return wrapper
  }

  async function pickAppSelectOption(select: { find: (sel: string) => { trigger: (e: string) => Promise<void> } }, label: string) {
    await select.find('.app-select__trigger').trigger('click')
    const option = [...document.body.querySelectorAll<HTMLElement>('.app-select__option')].find(
      (el) => el.textContent?.trim() === label,
    )
    expect(option).toBeTruthy()
    option!.click()
  }

  it('renders shared primitives; three sections of PrefRows, selects kept MD', async () => {
    vi.useFakeTimers()
    const w = await mountView()
    expect(w.findAllComponents(SectionHeader).map((h) => h.props('title'))).toEqual([
      '处理设置',
      '自动化',
      '进行中任务',
      '历史记录',
    ])
    expect(w.findAllComponents(PrefRow).map((r) => r.props('title'))).toEqual([
      '启用图像处理',
      '默认处理类型',
      '输出格式',
      '输出质量',
      'EntryPoint 服务地址',
      'EntryPoint API Token',
      '下载完成后自动处理',
      '定期补跑未处理页',
    ])
    expect(w.find('select').exists()).toBe(false)
    expect(w.find('.switch').exists()).toBe(false)

    const selects = w.findAllComponents(AppSelect)
    expect(selects.length).toBe(4)
    expect(selects[0].props('options')).toEqual([
      { value: 'UPSCALE_2X', label: '2X 放大' },
      { value: 'UPSCALE_4X', label: '4X 放大' },
      { value: 'DENOISE', label: '降噪' },
      { value: 'DENOISE_UPSCALE', label: '降噪 + 放大' },
      { value: 'REMOVE_BG', label: '抠图（去背景）' },
    ])
    expect(selects[1].props('options')).toEqual([
      { value: 'png', label: 'PNG' },
      { value: 'jpeg', label: 'JPEG' },
      { value: 'webp', label: 'WebP' },
    ])
    expect(selects[0].text()).toContain('2X 放大')
    expect(selects[2].props('options')).toEqual([
      { value: 15, label: '15 分钟' },
      { value: 30, label: '30 分钟' },
      { value: 60, label: '1 小时' },
      { value: 360, label: '6 小时' },
      { value: 1440, label: '24 小时' },
    ])
    expect(selects[2].props('modelValue')).toBe(60)
    expect(selects[3].props('options')).toEqual([
      { value: '', label: '全部' },
      { value: 'DONE', label: '已完成' },
      { value: 'FAILED', label: '失败' },
    ])

    expect(w.findAllComponents(AppSwitch).map((s) => s.attributes('aria-label'))).toEqual([
      '启用图像处理',
      '下载完成后自动处理',
      '定期补跑未处理页',
    ])
    expect(w.find('.processing__slider').exists()).toBe(true)

    // Token 输入：password 型 + 按 entrypointTokenSet 显示占位（默认未配置）。
    expect(w.findAllComponents(AppTextField).length).toBe(2)
    const tokenInput = w.find('input[aria-label="EntryPoint API Token"]')
    expect(tokenInput.attributes('type')).toBe('password')
    expect(tokenInput.attributes('placeholder')).toBe('未配置')

    // 空态。
    expect(w.text()).toContain('暂无进行中的处理任务')
    expect(w.text()).toContain('暂无历史记录')
  })

  it('selects a processing type via the dropdown and persists it', async () => {
    vi.useFakeTimers()
    await mountView(true)

    const select = wrapper.findAllComponents(AppSelect)[0]
    await pickAppSelectOption(select, '4X 放大')
    await vi.advanceTimersByTimeAsync(700)

    expect(settingsApi.update).toHaveBeenCalledWith(
      expect.objectContaining({ processing: expect.objectContaining({ defaultType: 'UPSCALE_4X' }) }),
    )
  })

  it('toggles the processing switch and persists it', async () => {
    vi.useFakeTimers()
    const w = await mountView()
    const sw = w.findComponent(AppSwitch)
    expect(sw.attributes('aria-checked')).toBe('false')
    await sw.trigger('click')
    expect(sw.attributes('aria-checked')).toBe('true')
    await vi.advanceTimersByTimeAsync(700)
    expect(settingsApi.update).toHaveBeenCalledWith(
      expect.objectContaining({ processing: expect.objectContaining({ enabled: true }) }),
    )
  })

  it('persists an edited EntryPoint service URL after the debounce', async () => {
    vi.useFakeTimers()
    const w = await mountView()

    await w.find('input[aria-label="EntryPoint 服务地址"]').setValue('http://10.0.0.2:9800')
    await vi.advanceTimersByTimeAsync(700)

    expect(settingsApi.update).toHaveBeenCalledWith(
      expect.objectContaining({
        processing: expect.objectContaining({ entrypointUrl: 'http://10.0.0.2:9800' }),
      }),
    )
  })

  it('sends the token draft once with the save payload and never writes it back into the form state', async () => {
    vi.useFakeTimers()
    const w = await mountView()

    await w.find('input[aria-label="EntryPoint API Token"]').setValue('secret-token')
    await vi.advanceTimersByTimeAsync(700)

    const first = vi.mocked(settingsApi.update).mock.calls[0][0]
    expect(first.processing?.entrypointToken).toBe('secret-token')

    // 保存成功：草稿清空、占位切换为「已配置——留空保留」。
    expect(w.find<HTMLInputElement>('input[aria-label="EntryPoint API Token"]').element.value).toBe('')
    expect(w.find('input[aria-label="EntryPoint API Token"]').attributes('placeholder')).toBe(
      '已配置——留空保留',
    )

    // 后续与 token 无关的保存不得再携带 entrypointToken（无回写污染）。
    await w.findComponent(AppSwitch).trigger('click')
    await vi.advanceTimersByTimeAsync(700)
    const second = vi.mocked(settingsApi.update).mock.calls[1][0]
    expect(second.processing).not.toHaveProperty('entrypointToken')
    expect(second.processing).toMatchObject({ enabled: true })
  })

  it('persists the download-automation switch', async () => {
    vi.useFakeTimers()
    const w = await mountView()

    const sw = w.findAllComponents(AppSwitch)[1]
    expect(sw.attributes('aria-label')).toBe('下载完成后自动处理')
    await sw.trigger('click')
    await vi.advanceTimersByTimeAsync(700)

    expect(settingsApi.update).toHaveBeenCalledWith(
      expect.objectContaining({
        processing: expect.objectContaining({ automationEnabled: true }),
      }),
    )
  })

  it('renders the active task list: progress, badges, paths, error line and cancel button', async () => {
    vi.useFakeTimers()
    vi.mocked(processingApi.getActiveTasks).mockResolvedValue([
      taskFixture(),
      taskFixture({
        taskId: 'proc-failed009',
        galleryId: 9,
        title: '',
        trigger: 'MANUAL',
        processingType: 'REMOVE_BG',
        state: 'FAILED',
        pagesTotal: 4,
        pagesDone: 1,
        pagesFailed: 3,
        sourceDir: '/data/downloads/9',
        outputDir: '/cache/enhanced/9',
        errorCode: 'EP_UNREACHABLE',
        errorMessage: 'EntryPoint 无法连接',
        startedAt: Date.now() - 120000,
      }),
    ])
    const w = await mountView()

    const rows = w.findAll('.processing__task-row')
    expect(rows.length).toBe(2)

    const first = rows[0]
    expect(first.text()).toContain('Example Gallery')
    expect(first.text()).toContain('2X 放大')
    expect(first.text()).toContain('下载完成')
    expect(first.text()).toContain('处理中')
    expect(first.text()).toContain('3/10 页')
    expect(first.text()).toContain('当前第 4 页') // 0-based pagesDone → 1-based 展示
    expect(first.text()).toContain('已耗时 01:05')
    expect(first.text()).toContain('/data/downloads/7')
    expect(first.text()).toContain('/cache/enhanced/7')
    expect(first.findAll('button.processing__action').length).toBe(1)

    const failed = rows[1]
    expect(failed.text()).toContain('画廊 #9') // 空 title 回退
    expect(failed.text()).toContain('抠图')
    expect(failed.text()).toContain('手动')
    expect(failed.text()).toContain('EP_UNREACHABLE')
    expect(failed.text()).toContain('EntryPoint 无法连接')
    expect(failed.findAll('button.processing__action').length).toBe(0) // 非 PENDING/PROCESSING 无取消
  })

  it('polls active tasks every 3s: initial load plus one call per tick', async () => {
    vi.useFakeTimers()
    await mountView()
    expect(processingApi.getActiveTasks).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(3000)
    await vi.advanceTimersByTimeAsync(3000)

    expect(processingApi.getActiveTasks).toHaveBeenCalledTimes(3)
  })

  it('cancels an active task and refreshes the list', async () => {
    vi.useFakeTimers()
    vi.mocked(processingApi.getActiveTasks).mockResolvedValue([taskFixture()])
    const w = await mountView()
    expect(processingApi.getActiveTasks).toHaveBeenCalledTimes(1)

    await w.find('button.processing__action--danger').trigger('click')
    await vi.advanceTimersByTimeAsync(0)

    expect(processingApi.cancel).toHaveBeenCalledTimes(1)
    expect(processingApi.cancel).toHaveBeenCalledWith('proc-active001')
    // 取消后立即手动刷新一次列表。
    expect(processingApi.getActiveTasks).toHaveBeenCalledTimes(2)
  })

  it('loads history page 0 size 20 and refetches when the state filter changes', async () => {
    vi.useFakeTimers()
    await mountView(true)

    expect(processingApi.getHistory).toHaveBeenCalledWith(0, 20, undefined)

    // 第 4 个 AppSelect 是历史状态过滤。
    const filter = wrapper.findAllComponents(AppSelect)[3]
    await pickAppSelectOption(filter, '已完成')
    await vi.advanceTimersByTimeAsync(0)

    expect(processingApi.getHistory).toHaveBeenLastCalledWith(0, 20, 'DONE')
  })

  it('retries a failed history task, shows a snackbar and refreshes both lists', async () => {
    vi.useFakeTimers()
    vi.mocked(processingApi.getHistory).mockResolvedValue(
      historyPageFixture([
        taskFixture({
          taskId: 'proc-histfail1',
          galleryId: 12,
          title: 'History Gallery',
          trigger: 'SCHEDULED',
          processingType: 'DENOISE',
          state: 'FAILED',
          pagesFailed: 2,
          errorCode: 'EP_TIMEOUT',
          errorMessage: '单页处理超时',
          finishedAt: 1757280600000,
        }),
      ]),
    )
    const w = await mountView()
    expect(processingApi.getHistory).toHaveBeenCalledWith(0, 20, undefined)

    const retry = w.find('button.processing__action')
    expect(retry.text()).toBe('重试')
    await retry.trigger('click')
    await vi.advanceTimersByTimeAsync(0)

    expect(processingApi.retry).toHaveBeenCalledTimes(1)
    expect(processingApi.retry).toHaveBeenCalledWith('proc-histfail1')
    expect(w.text()).toContain('已重新提交')
    // 历史与进行中列表都刷新。
    expect(processingApi.getHistory).toHaveBeenCalledTimes(2)
    expect(processingApi.getActiveTasks).toHaveBeenCalledTimes(2)
    expect(w.find('.processing__task-error').text()).toContain('EP_TIMEOUT')
  })

  it('disables pagination buttons at page boundaries', async () => {
    vi.useFakeTimers()
    vi.mocked(processingApi.getHistory).mockImplementation(async (page?: number) => ({
      items: [taskFixture({ taskId: `proc-page${page ?? 0}` })],
      total: 45, // 3 页
      page: page ?? 0,
      size: 20,
    }))
    const w = await mountView()

    const prev = () => w.find('button[aria-label="上一页"]')
    const next = () => w.find('button[aria-label="下一页"]')
    expect(w.text()).toContain('第 1 / 3 页')
    expect(prev().attributes('disabled')).toBeDefined()
    expect(next().attributes('disabled')).toBeUndefined()

    await next().trigger('click')
    await vi.advanceTimersByTimeAsync(0)
    expect(processingApi.getHistory).toHaveBeenLastCalledWith(1, 20, undefined)
    expect(prev().attributes('disabled')).toBeUndefined()

    await next().trigger('click')
    await vi.advanceTimersByTimeAsync(0)
    expect(processingApi.getHistory).toHaveBeenLastCalledWith(2, 20, undefined)
    expect(next().attributes('disabled')).toBeDefined()
  })
})
