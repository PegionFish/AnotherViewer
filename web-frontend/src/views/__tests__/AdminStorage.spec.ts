import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import AdminStorage from '../admin/AdminStorage.vue'
import {
  storageApi,
  type StoragePathCheck,
  type StorageProfileResponse,
  type StorageProfileResult,
} from '@/api/storage'

vi.mock('@/api/storage', () => ({
  storageApi: { getProfile: vi.fn(), redetect: vi.fn() },
  UNLIMITED_POOL_CONCURRENCY: 2147483647,
}))

/* ------------------------------- fixtures --------------------------------- */

function evidenceFixture(overrides: Partial<StorageProfileResult['evidence']> = {}): StorageProfileResult['evidence'] {
  return {
    rootPath: '/srv/av/downloads',
    fsType: 'ext4',
    fsTypeSource: 'file-store',
    mountSource: '/dev/nvme0n1p2',
    leafDevices: ['nvme0n1'],
    rotational: { nvme0n1: 0 },
    zfsPool: null,
    zfsCacheHint: null,
    failureReason: null,
    overrideApplied: null,
    naturalProfile: null,
    ...overrides,
  }
}

function legFixture(overrides: Partial<StoragePathCheck> = {}): StoragePathCheck {
  return {
    path: '/srv/av/cache',
    profile: 'SSD',
    evidence: evidenceFixture(),
    compliant: true,
    ...overrides,
  }
}

function profileFixture(overrides: {
  profile?: StorageProfileResult['profile']
  evidence?: Partial<StorageProfileResult['evidence']>
  invariantHeld?: boolean
  cache?: StoragePathCheck | null
  db?: StoragePathCheck | null
  tuning?: Partial<StorageProfileResponse['tuning']>
  zfsRecommendations?: StorageProfileResponse['zfsRecommendations']
} = {}): StorageProfileResponse {
  const profile = overrides.profile ?? 'SSD'
  const invariantHeld = overrides.invariantHeld ?? true
  return {
    profile: { profile, evidence: evidenceFixture(overrides.evidence ?? {}) },
    deployment: {
      invariantHeld,
      cache: overrides.cache !== undefined ? overrides.cache : legFixture({ path: '/srv/av/cache' }),
      db: overrides.db !== undefined ? overrides.db : legFixture({ path: '/srv/av/data/anotherviewer.db' }),
    },
    tuning: {
      profile,
      poolReadConcurrencyLimit: 2147483647,
      pageTurnPrefetchEnabled: false,
      scrubRateLimitMbPerSec: 400,
      maintenanceIoPriority: 'NORMAL',
      ...overrides.tuning,
    },
    zfsRecommendations: overrides.zfsRecommendations ?? [],
  }
}

const ZFS_RECOMMENDATIONS = [
  { id: 'recordsize', title: 'downloads 数据集 recordsize=512K–1M', description: '贴合页文件尺寸' },
  { id: 'special-vdev', title: '元数据 special vdev（NVMe）', description: '中和 stat 风暴' },
  { id: 'compression', title: 'downloads 数据集 compression=off', description: 'webp/jpg 不可压' },
  { id: 'arc-max', title: 'zfs_arc_max 显式限制（建议 6–8GB）', description: 'ARC 与 JVM 争 RAM' },
  { id: 'l2arc', title: 'L2ARC（NVMe，可选）', description: '重启后热集保温' },
  { id: 'fs-tweaks', title: 'atime=off · xattr=sa · ashift=12', description: '通用小项' },
]

describe('AdminStorage（存储管理）', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    vi.mocked(storageApi.redetect).mockResolvedValue({
      profile: { profile: 'SSD', evidence: evidenceFixture() },
      tuning: {
        profile: 'SSD',
        poolReadConcurrencyLimit: 2147483647,
        pageTurnPrefetchEnabled: false,
        scrubRateLimitMbPerSec: 400,
        maintenanceIoPriority: 'NORMAL',
      },
    })
  })

  afterEach(() => {
    wrapper?.unmount()
    vi.clearAllMocks()
  })

  async function mountView(response: StorageProfileResponse = profileFixture()) {
    vi.mocked(storageApi.getProfile).mockResolvedValue(response)
    wrapper = mount(AdminStorage)
    await flushPromises()
    return wrapper
  }

  it('renders the SSD normal state: profile badge, evidence rows, tuning table, green invariant, no ZFS list', async () => {
    const w = await mountView()

    // 徽章与证据明细
    expect(w.find('.profile-badge').text()).toBe('SSD')
    const rows = w.findAll('.evidence__row').map((r) => r.text())
    expect(rows.some((t) => t.includes('文件系统') && t.includes('ext4'))).toBe(true)
    expect(rows.some((t) => t.includes('挂载源') && t.includes('/dev/nvme0n1p2'))).toBe(true)
    expect(rows.some((t) => t.includes('底层盘') && t.includes('nvme0n1'))).toBe(true)
    expect(rows.some((t) => t.includes('rotational') && t.includes('nvme0n1=0'))).toBe(true)
    // 非 ZFS：池/加速卡行与建议清单都不出现
    expect(rows.some((t) => t.includes('ZFS 池'))).toBe(false)
    expect(w.find('.zfs-list').exists()).toBe(false)

    // 自适应参数（SSD 档：不设闸 / 关预读 / 400MB/s / 正常优先级）
    const tuning = w.findAll('.tuning-value').map((v) => v.text())
    expect(tuning).toEqual(['不设闸', '关闭', '400 MB/s', '正常'])

    // 部署不变量：正常态，两腿合规
    expect(w.find('.dep-card').classes()).toContain('dep-card--ok')
    expect(w.find('.dep-card__chip').text()).toBe('正常')
    const legs = w.findAll('.dep-leg').map((l) => l.text())
    expect(legs[0]).toContain('/srv/av/cache')
    expect(legs[0]).toContain('合规')
    expect(legs[1]).toContain('/srv/av/data/anotherviewer.db')
    expect(legs[1]).toContain('SSD')
  })

  it('renders the ZFS state: zfs badge, pool evidence, zfs tuning and the amber suggestion list', async () => {
    const w = await mountView(
      profileFixture({
        profile: 'ZFS',
        evidence: {
          fsType: 'zfs',
          mountSource: 'tank/downloads',
          leafDevices: [],
          rotational: {},
          zfsPool: 'tank',
          zfsCacheHint: true,
        },
        tuning: {
          poolReadConcurrencyLimit: 16,
          pageTurnPrefetchEnabled: true,
          scrubRateLimitMbPerSec: 120,
          maintenanceIoPriority: 'IDLE',
        },
        zfsRecommendations: ZFS_RECOMMENDATIONS,
      }),
    )

    expect(w.find('.profile-badge').text()).toBe('ZFS 池')
    const rows = w.findAll('.evidence__row').map((r) => r.text())
    expect(rows.some((t) => t.includes('ZFS 池') && t.includes('tank'))).toBe(true)
    expect(rows.some((t) => t.includes('cache / special vdev') && t.includes('有'))).toBe(true)

    // ZFS 档参数：16 路并发 / 开预读 / 120MB/s / 让路
    const tuning = w.findAll('.tuning-value').map((v) => v.text())
    expect(tuning).toEqual(['16 路', '开启', '120 MB/s', '让路（IDLE）'])

    // 建议清单：6 项、每项带「建议」chip 与说明，黄牌样式容器
    const items = w.findAll('.zfs-item')
    expect(items.length).toBe(6)
    expect(items.map((i) => i.find('.zfs-item__title').text())).toEqual(ZFS_RECOMMENDATIONS.map((r) => r.title))
    expect(items.every((i) => i.find('.zfs-item__chip').text() === '建议')).toBe(true)
    expect(items[0].text()).toContain('贴合页文件尺寸')
    expect(w.find('.zfs-list').classes().length).toBeGreaterThan(0)
  })

  it('raises a violation yellow card with per-leg detail when DB sits on an HDD', async () => {
    const w = await mountView(
      profileFixture({
        invariantHeld: false,
        db: legFixture({
          path: '/mnt/hdd/anotherviewer.db',
          profile: 'HDD',
          evidence: evidenceFixture({ leafDevices: ['sdb'], rotational: { sdb: 1 } }),
          compliant: false,
        }),
      }),
    )

    expect(w.find('.dep-card').classes()).toContain('dep-card--violation')
    expect(w.find('.dep-card__title').text()).toContain('违例')
    const legs = w.findAll('.dep-leg')
    expect(legs[0].text()).toContain('合规')
    expect(legs[1].text()).toContain('/mnt/hdd/anotherviewer.db')
    expect(legs[1].text()).toContain('机械盘（HDD）')
    expect(legs[1].text()).toContain('违例')
    // ZFS 建议清单仍不出现（非 ZFS profile）
    expect(w.find('.zfs-list').exists()).toBe(false)
  })

  it('shows a grey unverifiable card when legs are UNKNOWN network fs instead of a violation', async () => {
    const w = await mountView(
      profileFixture({
        profile: 'UNKNOWN',
        invariantHeld: false,
        cache: legFixture({ profile: 'UNKNOWN', compliant: false }),
        db: legFixture({ path: '/mnt/nas/anotherviewer.db', profile: 'UNKNOWN', compliant: false }),
        tuning: {
          poolReadConcurrencyLimit: 3,
          pageTurnPrefetchEnabled: true,
          scrubRateLimitMbPerSec: 60,
          maintenanceIoPriority: 'IDLE',
        },
      }),
    )

    expect(w.find('.dep-card').classes()).toContain('dep-card--unverifiable')
    expect(w.find('.dep-card__title').text()).toContain('不可验证')
    expect(w.findAll('.dep-leg__status--unknown').length).toBe(2)
    expect(w.text()).toContain('请人工确认 cache 与 DB 位于 SSD')
  })

  it('redetect button re-probes then reloads the whole page and flashes a snackbar', async () => {
    const w = await mountView()
    expect(storageApi.getProfile).toHaveBeenCalledTimes(1)

    await w.find('button.admin-storage__redetect').trigger('click')
    await flushPromises()

    expect(storageApi.redetect).toHaveBeenCalledTimes(1)
    // 整页刷新：redetect 成功后重拉 GET /profile
    expect(storageApi.getProfile).toHaveBeenCalledTimes(2)
    expect(w.text()).toContain('已重新探测')
    expect(w.find('.profile-badge').text()).toBe('SSD')
  })

  it('redetect failure keeps the page data and shows an error snackbar', async () => {
    const w = await mountView()
    vi.mocked(storageApi.redetect).mockRejectedValueOnce(new Error('network down'))

    await w.find('button.admin-storage__redetect').trigger('click')
    await flushPromises()

    expect(storageApi.getProfile).toHaveBeenCalledTimes(1) // 失败不重拉
    expect(w.text()).toContain('重新探测失败，请稍后重试')
    expect(w.find('.profile-badge').exists()).toBe(true)
  })

  it('shows the error state when the initial load fails', async () => {
    vi.mocked(storageApi.getProfile).mockRejectedValue(new Error('boom'))
    wrapper = mount(AdminStorage)
    await flushPromises()

    expect(wrapper.text()).toContain('无法加载存储信息')
    expect(wrapper.find('.profile-badge').exists()).toBe(false)
  })
})
