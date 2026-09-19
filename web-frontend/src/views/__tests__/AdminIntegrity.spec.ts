import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

vi.mock('@/api/integrity', () => ({
  integrityApi: {
    getReport: vi.fn(),
    refreshPage: vi.fn(),
    reverify: vi.fn(),
    scrub: vi.fn(),
    backfill: vi.fn(),
  },
}))

vi.mock('@/api/jobs', () => ({
  jobsApi: { getJob: vi.fn() },
}))

import AdminIntegrity from '../admin/AdminIntegrity.vue'
import AdminDownload from '../admin/AdminDownload.vue'
import { integrityApi, type IntegrityReport } from '@/api/integrity'
import { jobsApi, type Job } from '@/api/jobs'
import { settingsApi, type Settings } from '@/api/settings'
import { AppSwitch, PrefRow } from '@/components/form'

vi.mock('@/api/settings', () => ({
  settingsApi: { get: vi.fn(), update: vi.fn() },
}))

vi.mock('@/api/download', () => ({
  downloadApi: { previewMaintenance: vi.fn(), cleanMaintenance: vi.fn() },
}))

const mockedGetReport = vi.mocked(integrityApi.getReport)
const mockedRefreshPage = vi.mocked(integrityApi.refreshPage)
const mockedReverify = vi.mocked(integrityApi.reverify)
const mockedScrub = vi.mocked(integrityApi.scrub)
const mockedBackfill = vi.mocked(integrityApi.backfill)
const mockedGetJob = vi.mocked(jobsApi.getJob)

/** 契约 IntegrityReport 夹具：1 条 MISSING + 1 条 MISMATCH，1 条分歧。 */
function reportFixture(): IntegrityReport {
  return {
    lastRun: {
      startedAt: 1758000000000,
      finishedAt: 1758000060000,
      totalPages: 120,
      badPages: 2,
      divergences: 1,
      oneSided: 3,
    },
    badPages: [
      { gid: 600, page: 3, verdict: 'MISSING', size: null, hashShort: null },
      { gid: 601, page: 7, verdict: 'MISMATCH', size: 2048, hashShort: 'abcd1234efgh' },
    ],
    badPageTotal: 2,
    divergences: [{ gid: 600, page: 3, localHash: 'a'.repeat(64), peerHash: 'b'.repeat(64) }],
    divergenceTotal: 1,
  }
}

function jobFixture(overrides: Partial<Job> = {}): Job {
  return {
    jobId: 'job-1',
    type: 'IMPORT',
    state: 'RUNNING',
    stage: null,
    percent: 0,
    processed: 0,
    total: 0,
    startedAt: null,
    completedAt: null,
    error: null,
    result: null,
    ...overrides,
  }
}

/** 409 CONFLICT 等服务端错误信封形状（errorCodeOf 的输入）。 */
function apiError(code: string): { response: { status: number; data: { error: { code: string } } } } {
  return { response: { status: 409, data: { error: { code } } } }
}

describe('AdminIntegrity (下载完整性)', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    mockedGetReport.mockResolvedValue(reportFixture())
  })

  afterEach(() => {
    wrapper?.unmount()
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  async function mountView() {
    wrapper = mount(AdminIntegrity)
    await flushPromises()
    return wrapper
  }

  /* ------------------------------- 三状态 -------------------------------- */

  it('starts in the loading state before the report resolves', async () => {
    wrapper = mount(AdminIntegrity)
    expect(wrapper.find('[role="status"]').text()).toContain('正在加载完整性报告')
    await flushPromises()
    expect(wrapper.text()).toContain('上次巡检')
  })

  it('shows the error state with a working retry when the report fails to load', async () => {
    mockedGetReport.mockRejectedValueOnce(new Error('boom'))
    wrapper = mount(AdminIntegrity)
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('无法加载完整性报告')

    await wrapper.find('.integrity-state .btn-primary').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('上次巡检')
    expect(mockedGetReport).toHaveBeenCalledTimes(2)
  })

  /* ------------------------------ 正常态渲染 ------------------------------ */

  it('renders the last-run summary, bad pages, divergences and the empty-pager state', async () => {
    const w = await mountView()
    expect(w.text()).toContain('巡检结果')
    expect(w.text()).toContain('共 120 页 · 坏页 2 · 分歧 1 · 单侧证据 3')

    const rows = w.findAll('.integrity-list').at(0)!.findAll('.integrity-list__main')
    expect(rows.map((r) => r.text())).toEqual([
      '#600 · 第 3 页文件缺失',
      '#601 · 第 7 页哈希不符 · abcd1234efgh',
    ])
    expect(w.text()).toContain('共 2 条')

    // 分歧清单只读：基线/对端短摘要，无任何动作按钮。
    const divRow = w.findAll('.integrity-list').at(1)!.findAll('.integrity-list__main')
    expect(divRow.map((r) => r.text())).toEqual(['#600 · 第 3 页 基线 aaaaaaaaaaaa / 对端 bbbbbbbbbbbb'])
    expect(divRow[0].find('button').exists()).toBe(false)

    expect(mockedGetReport).toHaveBeenCalledWith(0, 20)
  })

  it('renders the no-scrub-yet state when lastRun is null', async () => {
    mockedGetReport.mockResolvedValue({
      lastRun: null,
      badPages: [],
      badPageTotal: 0,
      divergences: [],
      divergenceTotal: 0,
    })
    const w = await mountView()
    expect(w.text()).toContain('尚未运行过巡检')
    expect(w.text()).toContain('没有坏页记录。')
    expect(w.text()).toContain('没有基线与对端哈希分歧。')
  })

  it('paginates the lists via prev/next', async () => {
    // 45 条坏页 → 3 页，翻页按钮可用。
    mockedGetReport.mockResolvedValue({ ...reportFixture(), badPageTotal: 45 })
    const w = await mountView()
    const buttons = w.findAll('.integrity-pager button')
    expect(buttons[0].attributes('disabled')).toBeDefined() // 第 1 页无上一页

    await buttons[1].trigger('click')
    await flushPromises()
    expect(mockedGetReport).toHaveBeenCalledWith(1, 20)
    expect(w.find('.integrity-pager__label').text()).toBe('第 2 / 3 页')
  })

  /* ------------------------------ 手动巡检 ------------------------------- */

  it('triggers a scrub and reports acceptance', async () => {
    mockedScrub.mockResolvedValue({ accepted: true })
    const w = await mountView()
    await w.findAll('button').find((b) => b.text() === '立即巡检')!.trigger('click')
    await flushPromises()
    expect(mockedScrub).toHaveBeenCalledTimes(1)
    expect(w.text()).toContain('巡检已受理，后台执行中')
  })

  it('reports the 409 conflict when a scrub is already running', async () => {
    mockedScrub.mockRejectedValue(apiError('CONFLICT'))
    const w = await mountView()
    await w.findAll('button').find((b) => b.text() === '立即巡检')!.trigger('click')
    await flushPromises()
    expect(w.text()).toContain('已有巡检在进行中')
  })

  /* ------------------------------ 坏页刷新 ------------------------------- */

  it('refreshes a single bad page and reloads the report', async () => {
    mockedRefreshPage.mockResolvedValue({ status: 'healed', attribution: null, message: null })
    const w = await mountView()
    const firstRowBtn = w.findAll('.integrity-list').at(0)!.find('button')
    await firstRowBtn.trigger('click')
    await flushPromises()
    expect(mockedRefreshPage).toHaveBeenCalledWith(600, 3)
    expect(w.text()).toContain('#600 第 3 页已修复')
    // 成功后重载当前页（仍 page=0 / pageSize=20）。
    expect(mockedGetReport).toHaveBeenLastCalledWith(0, 20)
  })

  it('surfaces a failed single-page repair message', async () => {
    mockedRefreshPage.mockResolvedValue({
      status: 'failed',
      attribution: 'source_changed',
      message: 'source fetch rejected',
    })
    const w = await mountView()
    await w.findAll('.integrity-list').at(0)!.find('button').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('#600 第 3 页修复失败：source fetch rejected')
  })

  it('refreshes all bad pages one by one and summarizes the batch', async () => {
    mockedRefreshPage.mockResolvedValue({ status: 'healed', attribution: null, message: null })
    const w = await mountView()
    await w.findAll('button').find((b) => b.text() === '全部刷新')!.trigger('click')
    await flushPromises()

    // 全量取数用 batchSize 200，逐条各刷一次。
    expect(mockedGetReport).toHaveBeenCalledWith(0, 200)
    expect(mockedRefreshPage).toHaveBeenCalledTimes(2)
    expect(mockedRefreshPage).toHaveBeenNthCalledWith(1, 600, 3)
    expect(mockedRefreshPage).toHaveBeenNthCalledWith(2, 601, 7)
    expect(w.text()).toContain('批量刷新完成：修复 2 页，失败 0 页')
  })

  it('counts per-item failures inside the batch without aborting the loop', async () => {
    mockedRefreshPage
      .mockResolvedValueOnce({ status: 'healed', attribution: null, message: null })
      .mockRejectedValueOnce(new Error('boom'))
    const w = await mountView()
    await w.findAll('button').find((b) => b.text() === '全部刷新')!.trigger('click')
    await flushPromises()
    expect(mockedRefreshPage).toHaveBeenCalledTimes(2)
    expect(w.text()).toContain('批量刷新完成：修复 1 页，失败 1 页')
  })

  /* ------------------------------ 整本复验 ------------------------------- */

  it('submits a reverify job and polls it to the terminal stats', async () => {
    vi.useFakeTimers()
    mockedReverify.mockResolvedValue({ jobId: 'job-1', state: 'PENDING' })
    mockedGetJob
      .mockResolvedValueOnce(jobFixture({ state: 'RUNNING', processed: 3, total: 10, percent: 30 }))
      .mockResolvedValueOnce(
        jobFixture({
          state: 'COMPLETED',
          result: { total: 10, ok: 9, bad: 1, refreshed: 1, interrupted: false },
        }),
      )

    const w = await mountView()
    await w.find('input[aria-label="复验画廊 gid"]').setValue('600')
    await w.findAll('button').find((b) => b.text() === '开始复验')!.trigger('click')
    await flushPromises()

    // 第一帧：RUNNING 进度 + 中断按钮可见。
    expect(mockedReverify).toHaveBeenCalledWith(600)
    expect(w.find('.integrity-progress').text()).toContain('复验中 · 3/10 页（30%）')
    expect(w.findAll('button').find((b) => b.text() === '中断')).toBeDefined()

    // 轮询间隔后到终态：Job.result 展示为统计。
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    const stats = w.find('[data-testid="reverify-stats"]')
    expect(stats.exists()).toBe(true)
    expect(stats.text()).toContain('共 10 页')
    expect(stats.text()).toContain('完好 9')
    expect(stats.text()).toContain('损坏 1')
    expect(stats.text()).toContain('修复 1')
    expect(w.find('.integrity-progress').exists()).toBe(false)
  })

  it('sends the interrupt flag on the active job without stopping the poll', async () => {
    vi.useFakeTimers()
    mockedReverify.mockResolvedValue({ jobId: 'job-1', state: 'PENDING' })
    mockedGetJob.mockResolvedValue(jobFixture({ state: 'RUNNING', processed: 1, total: 10, percent: 10 }))

    const w = await mountView()
    await w.find('input[aria-label="复验画廊 gid"]').setValue('600')
    await w.findAll('button').find((b) => b.text() === '开始复验')!.trigger('click')
    await flushPromises()

    await w.findAll('button').find((b) => b.text() === '中断')!.trigger('click')
    await flushPromises()
    expect(mockedReverify).toHaveBeenLastCalledWith(600, { interrupt: true })
    expect(w.text()).toContain('中断请求已发出')
  })

  it('rejects an invalid gid client-side and maps the 404 not-found error', async () => {
    const w = await mountView()
    await w.findAll('button').find((b) => b.text() === '开始复验')!.trigger('click')
    await flushPromises()
    expect(mockedReverify).not.toHaveBeenCalled()
    expect(w.text()).toContain('请输入有效的画廊 gid')

    mockedReverify.mockRejectedValue(apiError('INTEGRITY_NOT_FOUND'))
    await w.find('input[aria-label="复验画廊 gid"]').setValue('999')
    await w.findAll('button').find((b) => b.text() === '开始复验')!.trigger('click')
    await flushPromises()
    expect(w.text()).toContain('该 gid 没有本地下载记录')
  })

  /* --------------------------- 单画廊页数回填（U1） -------------------------- */

  it('runs the backfill two-step: dry-run stats first, then apply on confirmation', async () => {
    mockedBackfill
      .mockResolvedValueOnce({ gids: 1, rowsExamined: 3, rowsPagesUpdated: 1, rowsCompleted: 1, dryRun: true })
      .mockResolvedValueOnce({ gids: 1, rowsExamined: 3, rowsPagesUpdated: 1, rowsCompleted: 1, dryRun: false })

    const w = await mountView()
    await w.find('input[aria-label="回填画廊 gid"]').setValue('600')

    // 第一步：dry-run（默认开）→ 只统计不写入。
    await w.findAll('button').find((b) => b.text() === '预演回填（dry-run）')!.trigger('click')
    await flushPromises()
    expect(mockedBackfill).toHaveBeenCalledTimes(1)
    expect(mockedBackfill).toHaveBeenNthCalledWith(1, { kind: 'pageCounts', gid: 600, dryRun: true })

    const stats = w.find('[data-testid="backfill-stats"]')
    expect(stats.text()).toContain('检查 3 行')
    expect(stats.text()).toContain('校正页数 1 行')
    expect(stats.text()).toContain('完成化 1 行')
    expect(stats.text()).toContain('（dry-run，未写入）')

    // 第二步：确认应用 → 同端点 dryRun=false 真实写入。
    await w.findAll('button').find((b) => b.text() === '确认应用')!.trigger('click')
    await flushPromises()
    expect(mockedBackfill).toHaveBeenCalledTimes(2)
    expect(mockedBackfill).toHaveBeenNthCalledWith(2, { kind: 'pageCounts', gid: 600, dryRun: false })
    expect(w.find('[data-testid="backfill-stats"]').text()).toContain('（已写入）')
    expect(w.text()).toContain('回填完成：校正 1 行、完成化 1 行')
  })

  it('applies directly when the dry-run switch is off', async () => {
    mockedBackfill.mockResolvedValue({ gids: 1, rowsExamined: 2, rowsPagesUpdated: 0, rowsCompleted: 0, dryRun: false })
    const w = await mountView()
    await w.find('input[aria-label="回填画廊 gid"]').setValue('600')

    const sw = w.findComponent(AppSwitch)
    expect(sw.attributes('aria-checked')).toBe('true')
    await sw.trigger('click')
    await w.findAll('button').find((b) => b.text() === '按磁盘回填页数')!.trigger('click')
    await flushPromises()
    expect(mockedBackfill).toHaveBeenCalledTimes(1)
    expect(mockedBackfill).toHaveBeenCalledWith({ kind: 'pageCounts', gid: 600, dryRun: false })
    expect(w.text()).not.toContain('确认应用')
  })
})

/* ----------------------- U1：AdminDownload 的回填入口 ----------------------- */

describe('AdminDownload U1 entry (回填下载页数)', () => {
  let wrapper: VueWrapper
  let router: Router

  function fullSettings(): Settings {
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
      processing: { enabled: false, defaultType: 'UPSCALE_2X', outputFormat: 'png', outputQuality: 90, entrypointUrl: 'http://192.168.6.141:9800', entrypointTokenSet: false, automationEnabled: false, periodicEnabled: false, periodicIntervalMinutes: 60 },
      proxy: { enabled: false, type: 'http', host: '', port: 0, username: '', password: '' },
    }
  }

  beforeEach(async () => {
    vi.mocked(settingsApi.get).mockResolvedValue(fullSettings())
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/settings/server/download', component: AdminDownload },
        { path: '/settings/server/integrity', component: { template: '<div />' } },
      ],
    })
    await router.push('/settings/server/download')
  })

  afterEach(() => {
    wrapper?.unmount()
    vi.clearAllMocks()
  })

  it('navigates to the integrity backfill section from the maintenance row', async () => {
    wrapper = mount(AdminDownload, { global: { plugins: [router] } })
    await flushPromises()

    const titles = wrapper.findAllComponents(PrefRow).map((r) => r.props('title'))
    expect(titles).toContain('回填下载页数')

    await wrapper.find('[aria-label="前往页数回填"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/settings/server/integrity')
  })
})
