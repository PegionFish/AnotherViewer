import { describe, expect, it, vi, beforeEach } from 'vitest'
import type {
  StorageProfileResponse,
  StorageRedetectResponse,
} from '@/api/storage'

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

import client from '@/api/client'
import { storageApi } from '@/api/storage'

const mockedGet = vi.mocked(client.get)
const mockedPost = vi.mocked(client.post)

const profileResponse: StorageProfileResponse = {
  profile: {
    profile: 'SSD',
    evidence: {
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
    },
  },
  deployment: {
    invariantHeld: true,
    cache: {
      path: '/srv/av/cache',
      profile: 'SSD',
      evidence: {} as StorageProfileResponse['profile']['evidence'],
      compliant: true,
    },
    db: {
      path: '/srv/av/data/anotherviewer.db',
      profile: 'SSD',
      evidence: {} as StorageProfileResponse['profile']['evidence'],
      compliant: true,
    },
  },
  tuning: {
    profile: 'SSD',
    poolReadConcurrencyLimit: Number.MAX_SAFE_INTEGER,
    pageTurnPrefetchEnabled: false,
    scrubRateLimitMbPerSec: 400,
    maintenanceIoPriority: 'NORMAL',
  },
  zfsRecommendations: [],
}

const redetectResponse: StorageRedetectResponse = {
  profile: profileResponse.profile,
  tuning: profileResponse.tuning,
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
})

describe('storageApi（存储自适配 P3）', () => {
  it('getProfile GETs /admin/storage/profile', async () => {
    mockedGet.mockResolvedValue({ data: profileResponse })
    await expect(storageApi.getProfile()).resolves.toBe(profileResponse)
    expect(mockedGet).toHaveBeenCalledWith('/admin/storage/profile')
  })

  it('redetect POSTs /admin/storage/redetect', async () => {
    mockedPost.mockResolvedValue({ data: redetectResponse })
    await expect(storageApi.redetect()).resolves.toBe(redetectResponse)
    expect(mockedPost).toHaveBeenCalledWith('/admin/storage/redetect')
  })
})
