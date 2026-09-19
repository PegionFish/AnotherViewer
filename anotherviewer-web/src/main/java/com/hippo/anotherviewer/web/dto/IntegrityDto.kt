package com.hippo.anotherviewer.web.dto

/**
 * 下载文件完整性端点请求/响应体（文件完整性 Wave 2 / S5 + Wave 3 / S6），
 * 字段对齐 contracts/openapi.yaml「Integrity」段。Wave 3 S6 的 report/reverify/
 * scrub/backfill DTO 归本文件；repair/reverify 的结果类型
 * [com.hippo.anotherviewer.web.service.integrity.RepairResult] /
 * [com.hippo.anotherviewer.web.service.integrity.ReverifyStats] 由 S7 定义在
 * service.integrity 包（DownloadService 契约返回类型），不在此重复。
 */

/**
 * POST /api/v1/integrity/reverify/{gid} 请求体（契约 ReverifyRequest；可省略）。
 *
 * @param interrupt true = 对当前活跃 REVERIFY 任务置协作中断旗标（无论 gid 路径
 *   参数为何——任务按 type 单实例）；跑完当前页后以部分统计
 *   （ReverifyStats.interrupted=true）COMPLETED 收场。无活跃任务 → 404
 *   NO_ACTIVE_JOB。false/省略 = 提交新的 REVERIFY 任务。
 */
data class ReverifyRequest(
    val interrupt: Boolean = false,
)

/**
 * GET /api/v1/integrity/report 的一个坏页条目（契约 IntegrityBadPageDto）。
 *
 * @param gid 画廊 ID
 * @param page 1-based 页号（契约口径；基线表 0-based，出口换算）
 * @param verdict MISSING = 磁盘无文件；MISMATCH = 哈希与基线不符；
 *   READ_ERROR = 文件在但读不出。取自最近巡检快照，无快照时按启发式回退
 *   （无基线哈希的 struct_bad 行 → MISSING，否则 MISMATCH）
 * @param size 磁盘文件字节数；文件缺失为 null
 * @param hashShort 展示用短摘要（计算哈希前 12 个 hex 字符）；缺失/读不出为 null
 */
data class IntegrityBadPageDto(
    val gid: Long,
    val page: Int,
    val verdict: String,
    val size: Long? = null,
    val hashShort: String? = null,
)

/**
 * GET /api/v1/integrity/report 的一个基线-vs-peer 分歧条目（契约
 * IntegrityDivergenceDto）：巡检时交叉审计发现的版本分歧快照。
 *
 * @param page 1-based 页号（契约口径）
 * @param localHash 服务器基线哈希（page_file_hash）
 * @param peerHash 对端（App）哈希（peer_hash）
 */
data class IntegrityDivergenceDto(
    val gid: Long,
    val page: Int,
    val localHash: String,
    val peerHash: String,
)

/**
 * GET /api/v1/integrity/report 的最近巡检摘要（契约 IntegrityScrubRunDto）。
 * 契约外加 **oneSided**（附加字段，向后兼容）：仅单侧证据的页数——任务书要求
 * 单侧计数进 report，契约 schema 无对应槽位，故做加法不放顶层。
 */
data class IntegrityScrubRunDto(
    val startedAt: Long,
    val finishedAt: Long?,
    val totalPages: Int,
    val badPages: Int,
    val divergences: Int,
    val oneSided: Int = 0,
)

/**
 * GET /api/v1/integrity/report 响应（契约 IntegrityReport）：最近巡检摘要 +
 * 坏页清单（page_file_hash.verdict='struct_bad' 当前态，分页）+ 分歧清单
 * （最近巡检的交叉审计快照，分页），两清单各带未过滤总数。
 */
data class IntegrityReport(
    val lastRun: IntegrityScrubRunDto?,
    val badPages: List<IntegrityBadPageDto>,
    val badPageTotal: Int,
    val divergences: List<IntegrityDivergenceDto>,
    val divergenceTotal: Int,
)

/** POST /api/v1/integrity/scrub 响应：巡检已受理、在后台线程执行。 */
data class ScrubTriggerResponse(
    val accepted: Boolean,
)

/**
 * POST /api/v1/integrity/backfill 请求体：同步触发 S4 维护动作。
 *
 * @param kind "hashes"（TOFU 哈希回填）| "pageCounts"（磁盘页数回填）
 * @param gid 必填（避免请求线程跑全库）
 * @param dryRun 只统计不写，默认 false
 */
data class BackfillRequest(
    val kind: String? = null,
    val gid: Long? = null,
    val dryRun: Boolean = false,
)

/**
 * POST /api/v1/integrity/hashes/{gid} 的一条页哈希上送（App 以 peer 证据身份
 * 上报；required = page/ext/size/hash）。
 *
 * @param page 1-based 页号（契约口径；落库 peer_hash 前由控制器转 0-based，
 *   对齐 page_file_hash 基线与 App 侧 PageHashStore 的 0-based 页号）
 * @param ext  页文件扩展名（不带点）；peer_hash 表不落此列，仅随上送校验
 * @param size 上送端哈希时文件字节数；peer_hash 表不落此列，仅随上送校验
 * @param hash SHA-256 hex 摘要（64 个十六进制字符，大小写均可，落库归一小写）
 * @param algo 哈希算法枚举，缺省即 SHA-256；显式给出非 SHA-256 值 → 400
 */
data class IntegrityHashEntry(
    val page: Int,
    val ext: String,
    val size: Long,
    val hash: String,
    val algo: String? = null,
)

/** POST /api/v1/integrity/hashes/{gid} 响应；accepted=0 表示空清单 no-op。 */
data class IntegrityHashAcceptResponse(
    val accepted: Int,
)
