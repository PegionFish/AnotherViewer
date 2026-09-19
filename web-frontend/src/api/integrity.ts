import client from './client'
import type { JobState } from './jobs'

/**
 * 下载文件完整性 API（文件完整性 Wave 3，契约 contracts/openapi.yaml
 * 「Integrity」段 + 契约外管理端点 /scrub、/backfill）：
 *   GET  /api/v1/integrity/report?page=&pageSize= → 最近巡检摘要 + 坏页/分歧清单（各自分页）
 *   POST /api/v1/integrity/refresh/{gid}/{page}   → 单页强制重取（healed/failed 都 ride 200）
 *   POST /api/v1/integrity/reverify/{gid}         → 202 JobSubmitResponse（REVERIFY Job，轮询 /jobs/{jobId}）
 *   POST /api/v1/integrity/scrub                  → 202 {accepted}（409 = 已有巡检在跑）
 *   POST /api/v1/integrity/backfill               → 同步回填（kind=hashes|pageCounts）
 *
 * 页号口径：所有端点均为 1-based（契约口径；服务端基线表 0-based，出口已换算）。
 */

/** GET /integrity/report 的最近巡检摘要（契约 IntegrityScrubRunDto；oneSided 为契约外附加字段：仅单侧证据的页数）。 */
export interface IntegrityScrubRun {
  /** 巡检开始（ms epoch）。 */
  startedAt: number
  /** 巡检结束（ms epoch）；仍在跑为 null。 */
  finishedAt: number | null
  /** 检查过的页文件数。 */
  totalPages: number
  /** 判坏的页数。 */
  badPages: number
  /** 基线-vs-peer 哈希分歧数。 */
  divergences: number
  /** 仅单侧证据的页数（契约外附加，向后兼容）。 */
  oneSided: number
}

/** 坏页判定（契约 IntegrityBadPageDto.verdict）。 */
export type IntegrityBadPageVerdict = 'MISSING' | 'MISMATCH' | 'READ_ERROR'

/** GET /integrity/report 的一条坏页条目（契约 IntegrityBadPageDto）。 */
export interface IntegrityBadPage {
  gid: number
  /** 1-based 页号。 */
  page: number
  verdict: IntegrityBadPageVerdict
  /** 磁盘文件字节数；文件缺失为 null。 */
  size?: number | null
  /** 展示用短摘要（前 12 hex 字符）；缺失/读不出为 null。 */
  hashShort?: string | null
}

/** GET /integrity/report 的一条基线-vs-peer 分歧条目（契约 IntegrityDivergenceDto）。 */
export interface IntegrityDivergence {
  gid: number
  /** 1-based 页号。 */
  page: number
  /** 服务器基线哈希（page_file_hash）。 */
  localHash?: string
  /** 对端（App）哈希（peer_hash）。 */
  peerHash?: string
}

/** GET /integrity/report 响应（契约 IntegrityReport）：两清单各带未过滤总数。 */
export interface IntegrityReport {
  lastRun: IntegrityScrubRun | null
  badPages: IntegrityBadPage[]
  badPageTotal: number
  divergences: IntegrityDivergence[]
  divergenceTotal: number
}

/** POST /integrity/refresh/{gid}/{page} 响应（契约 RepairResult）；failed 时本地文件不动。 */
export interface IntegrityRepairResult {
  status: 'healed' | 'failed'
  /** 归因诊断：local_corrupt = 本地介质劣化；source_changed = 源已变；null = 无旧基线可比。 */
  attribution: 'local_corrupt' | 'source_changed' | null
  /** 失败原因（status=failed 时）。 */
  message?: string | null
}

/** POST /integrity/reverify/{gid} 请求体（契约 ReverifyRequest）。 */
export interface IntegrityReverifyRequest {
  /** true = 对活跃 REVERIFY 任务置协作中断旗标；false/省略 = 提交新任务。 */
  interrupt?: boolean
}

/** 异步任务提交端点的 202 响应体（契约 JobSubmitResponse）。 */
export interface IntegrityJobSubmitResponse {
  jobId: string
  state: JobState
}

/** REVERIFY Job 的终态统计（契约 ReverifyStats，挂 JobDto.result）。 */
export interface ReverifyStats {
  /** 检查的页数（中断时 = 已检查数）。 */
  total: number
  ok: number
  bad: number
  /** 运行中以 origin=heal 刷新基线的页数；无修复为 null。 */
  refreshed: number | null
  /** 经中断旗标提前收场（计数只覆盖已处理页）。 */
  interrupted: boolean
}

/** POST /integrity/scrub 响应：巡检已受理、后台线程执行（409 = 已有巡检在跑）。 */
export interface IntegrityScrubTriggerResponse {
  accepted: boolean
}

/** POST /integrity/backfill body 的 kind 取值。 */
export type IntegrityBackfillKind = 'hashes' | 'pageCounts'

/** POST /integrity/backfill 请求体（gid 必填，避免请求线程跑全库）。 */
export interface IntegrityBackfillRequest {
  kind: IntegrityBackfillKind
  gid: number
  /** 只统计不写（dry-run 时两个计数表示「将要」改的量）。 */
  dryRun: boolean
}

/** kind=hashes 的回填统计（TOFU 哈希回填：V 门 + SHA-256 补基线）。 */
export interface IntegrityBackfillHashesStats {
  gids: number
  scanned: number
  accepted: number
  rejected: number
  skipped: number
  ioErrors: number
  /** 拒绝明细（V 门 struct_bad）。 */
  rejectedPages?: { gid: number; page: number; reason: string }[]
  resumedFromCheckpoint?: boolean
  dryRun: boolean
}

/** kind=pageCounts 的回填统计（磁盘页数回填 + 遗留行完成化）。 */
export interface IntegrityBackfillPageCountsStats {
  gids: number
  rowsExamined: number
  rowsPagesUpdated: number
  rowsCompleted: number
  dryRun: boolean
}

/** POST /integrity/backfill 响应（按 kind 二选一）。 */
export type IntegrityBackfillStats = IntegrityBackfillHashesStats | IntegrityBackfillPageCountsStats

export const integrityApi = {
  /**
   * GET /integrity/report — 最近巡检报告。page 0-based、只切两个清单
   * （各带未过滤总数），pageSize clamp 1..200（缺省 20）；lastRun 永不分页。
   */
  async getReport(page = 0, pageSize = 20): Promise<IntegrityReport> {
    const { data } = await client.get<IntegrityReport>('/integrity/report', {
      params: { page, pageSize },
    })
    return data
  },

  /** POST /integrity/refresh/{gid}/{page} — 单页强制重取（page 1-based）。 */
  async refreshPage(gid: number, page: number): Promise<IntegrityRepairResult> {
    const { data } = await client.post<IntegrityRepairResult>(`/integrity/refresh/${gid}/${page}`)
    return data
  },

  /**
   * POST /integrity/reverify/{gid} — 提交 REVERIFY Job（202，轮询
   * jobsApi.getJob）或对活跃任务置中断旗标（{interrupt:true}；无活跃任务 404
   * NO_ACTIVE_JOB，已有活跃任务 409 CONFLICT）。
   */
  async reverify(gid: number, body: IntegrityReverifyRequest = {}): Promise<IntegrityJobSubmitResponse> {
    const { data } = await client.post<IntegrityJobSubmitResponse>(`/integrity/reverify/${gid}`, body)
    return data
  },

  /** POST /integrity/scrub — 手动触发巡检（202 受理；409 CONFLICT = 已有巡检在跑）。 */
  async scrub(): Promise<IntegrityScrubTriggerResponse> {
    const { data } = await client.post<IntegrityScrubTriggerResponse>('/integrity/scrub')
    return data
  },

  /** POST /integrity/backfill — 同步单画廊维护回填（kind=hashes|pageCounts）。 */
  async backfill(body: IntegrityBackfillRequest): Promise<IntegrityBackfillStats> {
    const { data } = await client.post<IntegrityBackfillStats>('/integrity/backfill', body)
    return data
  },
}
