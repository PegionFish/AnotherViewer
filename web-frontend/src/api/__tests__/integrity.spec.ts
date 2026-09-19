import { describe, expect, it, vi, beforeEach } from 'vitest'
import type { Job } from '@/api/jobs'

vi.mock('@/api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

import client from '@/api/client'
import { integrityApi } from '@/api/integrity'

const mockedGet = vi.mocked(client.get)
const mockedPost = vi.mocked(client.post)

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
})

describe('integrityApi — 下载文件完整性端点封装', () => {
  it('getReport sends 0-based page + pageSize as query params', async () => {
    const report = { lastRun: null, badPages: [], badPageTotal: 0, divergences: [], divergenceTotal: 0 }
    mockedGet.mockResolvedValue({ data: report })
    await expect(integrityApi.getReport(2, 50)).resolves.toBe(report)
    expect(mockedGet).toHaveBeenCalledWith('/integrity/report', { params: { page: 2, pageSize: 50 } })
  })

  it('getReport defaults to page 0 / pageSize 20', async () => {
    mockedGet.mockResolvedValue({ data: {} })
    await integrityApi.getReport()
    expect(mockedGet).toHaveBeenCalledWith('/integrity/report', { params: { page: 0, pageSize: 20 } })
  })

  it('refreshPage posts to the 1-based gid/page path', async () => {
    const result = { status: 'healed', attribution: null, message: null }
    mockedPost.mockResolvedValue({ data: result })
    await expect(integrityApi.refreshPage(1382450, 3)).resolves.toBe(result)
    expect(mockedPost).toHaveBeenCalledWith('/integrity/refresh/1382450/3')
  })

  it('reverify submits a new job with an empty body by default', async () => {
    const job = { jobId: 'job-abc', state: 'PENDING' } as Job
    mockedPost.mockResolvedValue({ data: job })
    await expect(integrityApi.reverify(600)).resolves.toBe(job)
    expect(mockedPost).toHaveBeenCalledWith('/integrity/reverify/600', {})
  })

  it('reverify forwards the interrupt flag', async () => {
    mockedPost.mockResolvedValue({ data: { jobId: 'job-abc', state: 'RUNNING' } })
    await integrityApi.reverify(600, { interrupt: true })
    expect(mockedPost).toHaveBeenCalledWith('/integrity/reverify/600', { interrupt: true })
  })

  it('scrub posts without a body', async () => {
    mockedPost.mockResolvedValue({ data: { accepted: true } })
    await expect(integrityApi.scrub()).resolves.toEqual({ accepted: true })
    expect(mockedPost).toHaveBeenCalledWith('/integrity/scrub')
  })

  it('backfill posts the kind/gid/dryRun body (pageCounts)', async () => {
    const stats = { gids: 1, rowsExamined: 3, rowsPagesUpdated: 1, rowsCompleted: 1, dryRun: true }
    mockedPost.mockResolvedValue({ data: stats })
    await expect(integrityApi.backfill({ kind: 'pageCounts', gid: 600, dryRun: true })).resolves.toBe(stats)
    expect(mockedPost).toHaveBeenCalledWith('/integrity/backfill', { kind: 'pageCounts', gid: 600, dryRun: true })
  })

  it('backfill posts the hashes kind with the same shape', async () => {
    const stats = { gids: 1, scanned: 10, accepted: 9, rejected: 1, skipped: 0, ioErrors: 0, dryRun: false }
    mockedPost.mockResolvedValue({ data: stats })
    await expect(integrityApi.backfill({ kind: 'hashes', gid: 600, dryRun: false })).resolves.toBe(stats)
    expect(mockedPost).toHaveBeenCalledWith('/integrity/backfill', { kind: 'hashes', gid: 600, dryRun: false })
  })
})
