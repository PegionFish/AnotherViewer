import { describe, expect, it, vi, beforeEach } from 'vitest'
import type { ProcessingHistoryPage, ProcessingTaskRecord } from '@/api/processing'

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

import client from '@/api/client'
import { processingApi } from '@/api/processing'

const mockedGet = vi.mocked(client.get)
const mockedPost = vi.mocked(client.post)

function recordFixture(overrides: Partial<ProcessingTaskRecord> = {}): ProcessingTaskRecord {
  return {
    taskId: 'ptask-1',
    galleryId: 42,
    title: 'Some Gallery',
    trigger: 'MANUAL',
    processingType: 'REMOVE_BG',
    state: 'PROCESSING',
    pagesTotal: 20,
    pagesDone: 3,
    pagesFailed: 0,
    sourceDir: '/data/site/g/42',
    outputDir: '/data/site/g/42/enhanced',
    epTaskIds: ['ep-1'],
    errorCode: '',
    errorMessage: '',
    createdAt: 1760000000000,
    startedAt: 1760000001000,
    finishedAt: 0,
    updatedAt: 1760000002000,
    ...overrides,
  }
}

function historyPageFixture(overrides: Partial<ProcessingHistoryPage> = {}): ProcessingHistoryPage {
  return {
    items: [recordFixture({ state: 'DONE' })],
    total: 1,
    page: 0,
    size: 50,
    ...overrides,
  }
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
})

describe('processingApi — 任务监控（handoff §4 契约）', () => {
  it('getActiveTasks hits /process/tasks with active=1 and passes tasks through', async () => {
    const record = recordFixture()
    mockedGet.mockResolvedValue({ data: { tasks: [record] } })
    await expect(processingApi.getActiveTasks()).resolves.toEqual([record])
    expect(mockedGet).toHaveBeenCalledWith('/process/tasks', { params: { active: 1 } })
  })

  it('getActiveTasks tolerates a missing tasks array as an empty list', async () => {
    mockedGet.mockResolvedValue({ data: {} })
    await expect(processingApi.getActiveTasks()).resolves.toEqual([])
  })

  it('getHistory forwards the paged payload with default page/size and no state param', async () => {
    const page = historyPageFixture()
    mockedGet.mockResolvedValue({ data: page })
    await expect(processingApi.getHistory()).resolves.toBe(page)
    expect(mockedGet).toHaveBeenCalledWith('/process/history', { params: { page: 0, size: 50 } })
  })

  it('getHistory includes the state param only when one is given', async () => {
    const page = historyPageFixture({ page: 2, size: 100 })
    mockedGet.mockResolvedValue({ data: page })
    await expect(processingApi.getHistory(2, 100, 'FAILED')).resolves.toBe(page)
    expect(mockedGet).toHaveBeenCalledWith('/process/history', {
      params: { page: 2, size: 100, state: 'FAILED' },
    })
  })

  it('retry interpolates the taskId into the path and returns the new task', async () => {
    const created = { taskId: 'ptask-9f2c', galleryId: 42, totalPages: 20, state: 'PENDING' as const }
    mockedPost.mockResolvedValue({ data: created })
    await expect(processingApi.retry('ptask-9f2c')).resolves.toEqual(created)
    expect(mockedPost).toHaveBeenCalledWith('/process/retry/ptask-9f2c')
  })

  it('cancel posts to /process/cancel/{taskId} and resolves regardless of the body', async () => {
    mockedPost.mockResolvedValue({ data: { success: true } })
    await expect(processingApi.cancel('ptask-1')).resolves.toBeUndefined()
    expect(mockedPost).toHaveBeenCalledWith('/process/cancel/ptask-1')
  })

  it('retry surfaces a 409 rejection whose error code errorCodeOf can extract', async () => {
    const conflict = {
      response: {
        status: 409,
        data: { error: { code: 'CONFLICT', message: 'Task is not in a retryable state' } },
      },
    }
    mockedPost.mockRejectedValue(conflict)
    await expect(processingApi.retry('ptask-1')).rejects.toBe(conflict)
    // The mock factory replaces the whole module — pull the real pure helper
    // through importActual (plain object suffices, no AxiosError needed).
    const { errorCodeOf } = await vi.importActual<typeof import('@/api/client')>('@/api/client')
    expect(errorCodeOf(conflict)).toBe('CONFLICT')
  })
})
