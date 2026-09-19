package com.hippo.anotherviewer.web.api

import com.hippo.anotherviewer.web.dto.BackfillRequest
import com.hippo.anotherviewer.web.dto.IntegrityHashAcceptResponse
import com.hippo.anotherviewer.web.dto.IntegrityHashEntry
import com.hippo.anotherviewer.web.dto.JobSubmitResponse
import com.hippo.anotherviewer.web.dto.JobType
import com.hippo.anotherviewer.web.dto.ReverifyRequest
import com.hippo.anotherviewer.web.dto.ScrubTriggerResponse
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.repository.PeerHashRepository
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.DownloadService
import com.hippo.anotherviewer.web.service.Job
import com.hippo.anotherviewer.web.service.JobService
import com.hippo.anotherviewer.web.service.integrity.BackfillService
import com.hippo.anotherviewer.web.service.integrity.RepairResult
import com.hippo.anotherviewer.web.service.integrity.ReverifyStats
import com.hippo.anotherviewer.web.service.integrity.StorageIntegrityService
import com.hippo.anotherviewer.web.service.storage.StorageTuning
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * 下载文件完整性 API（文件完整性 Wave 2 / S5 建 + Wave 3 / S6 扩展；契约
 * contracts/openapi.yaml「Integrity」段）。鉴权与其余 /api 端点一致——Bearer
 * token，由 SecurityConfig 的全局规则（/api 前缀通配 authenticated）覆盖，
 * 本控制器零额外配置。
 *
 * 端点一览：
 * - POST /hashes/{gid}：App 上送 peer 证据（S5）。
 * - GET /report：最近巡检摘要 + 坏页清单（struct_bad 当前态）+ 分歧清单
 *   （最近巡检的交叉审计快照），两清单独立分页（S6）。
 * - POST /reverify/{gid}：REVERIFY 后台任务提交/中断（Job 模式，worker 调
 *   S7 DownloadService.reverifyGallery），202 + JobSubmitResponse（S6）。
 * - POST /refresh/{gid}/{page}：单页强制重取（同步调 S7
 *   DownloadService.forceRefetchPage，修复日志由 S7 RepairLogService 落行，
 *   source=refresh），200 + RepairResult（S6）。
 * - POST /scrub：手动触发巡检（S6 StorageIntegrityService），202 立即返回。
 * - POST /backfill：同步触发 S4 维护动作（gid 必填，避免请求线程跑全库）。
 *
 * 语义红线（Wave 1 C1 契约）：peer 证据只进 peer_hash 表，绝不触碰
 * page_file_hash 基线——peer 上送路径根本不注入 PageFileHashRepository；
 * 巡检/交叉审计路径对 peer_hash 只读（StorageIntegrityService）。
 */
@RestController
@RequestMapping("/api/v1/integrity")
class IntegrityController(
    private val peerHashRepository: PeerHashRepository,
    private val downloadRepository: DownloadInfoRepository,
    private val downloadDirIndex: DownloadDirIndex,
    private val storageIntegrityService: StorageIntegrityService,
    private val backfillService: BackfillService,
    private val storageTuning: StorageTuning,
    private val jobService: JobService,
    private val reverifyOps: ReverifyRepairOps,
) {

    private val logger = LoggerFactory.getLogger(IntegrityController::class.java)

    /** 活跃 REVERIFY 任务的协作中断旗标（任务按 type 单实例，单槽足够）。 */
    private val reverifyCancel = AtomicReference<AtomicBoolean?>(null)

    /**
     * App 上送整本逐页哈希（peer 证据；POST /hashes/{gid}，契约
     * pushIntegrityHashes）。PeerHashRepository.upsert 是 @Modifying 原生
     * INSERT OR REPLACE，须事务内执行——校验与逐条 upsert 同批，任何一条
     * 非法则整批不落库（先全量校验再写入，不等同事务回滚也留了双保险）。
     *
     * - gid 必须存在本地下载行：对齐 completeUpload 的存在性口径
     *   （findAllByGid List 化查找容历史脏数据多行，墓碑行也算存在——
     *   证据通道不做存活过滤）；无行 → 404 INTEGRITY_NOT_FOUND。
     * - 每条 page >= 1（契约 1-based），非法 → 400 INTEGRITY_INVALID_PAGE；
     *   hash 必须 64-hex、algo 显式给出时只认 SHA-256，非法 → 400
     *   INTEGRITY_INVALID_HASH（契约：malformed hex digest 与 unsupported
     *   algo 同码）。
     * - 空数组是 no-op（accepted=0）；条目数上限 [MAX_HASHES_PER_PUSH]，
     *   超出 → 400 VALIDATION_ERROR（契约未为超限定义专用码，用既有通用码）。
     * - 页号口径：契约 1-based，peer_hash 行按 0-based 落库（page-1），
     *   与 page_file_hash 基线及 App 侧 PageHashStore（同为 0-based）对齐，
     *   交叉审计直接按 (gid,page) 联表。哈希统一归一小写落库
     *   （契约 pattern 允许大小写混合；基线 %02x 即小写，避免报告期比较歧义）。
     * - ext/size 仅随上送（契约必填字段），peer_hash 表无对应列，不落库。
     */
    @PostMapping("/hashes/{gid}")
    @Transactional
    fun pushHashes(
        @PathVariable gid: Long,
        @RequestBody entries: List<IntegrityHashEntry>,
    ): ResponseEntity<*> {
        if (entries.size > MAX_HASHES_PER_PUSH) {
            return errorEnvelope(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Too many hash entries: ${entries.size} (max $MAX_HASHES_PER_PUSH per push)",
            )
        }
        // U5 同款：List 化查找容同 gid 多行脏数据，不抛 IncorrectResultSizeDataAccessException。
        if (downloadRepository.findAllByGid(gid).isEmpty()) {
            return errorEnvelope(
                HttpStatus.NOT_FOUND,
                "INTEGRITY_NOT_FOUND",
                "No local download row for gid=$gid",
            )
        }
        entries.forEachIndexed { index, entry ->
            if (entry.page < 1) {
                return errorEnvelope(
                    HttpStatus.BAD_REQUEST,
                    "INTEGRITY_INVALID_PAGE",
                    "entry[$index]: page must be >= 1 (got ${entry.page})",
                )
            }
            if (!HASH_PATTERN.matcher(entry.hash).matches()) {
                return errorEnvelope(
                    HttpStatus.BAD_REQUEST,
                    "INTEGRITY_INVALID_HASH",
                    "entry[$index] page=${entry.page}: hash must be 64 hex characters",
                )
            }
            if (entry.algo != null && entry.algo != VALID_ALGO) {
                return errorEnvelope(
                    HttpStatus.BAD_REQUEST,
                    "INTEGRITY_INVALID_HASH",
                    "entry[$index] page=${entry.page}: unsupported algo '${entry.algo}' (only $VALID_ALGO)",
                )
            }
        }
        val now = System.currentTimeMillis()
        entries.forEach { entry ->
            peerHashRepository.upsert(gid, entry.page - 1, entry.hash.lowercase(), now)
        }
        logger.info("Integrity peer hashes accepted: gid={} entries={}", gid, entries.size)
        return ResponseEntity.ok(IntegrityHashAcceptResponse(accepted = entries.size))
    }

    /**
     * 完整性报告（GET /report，契约 getIntegrityReport）。lastRun 永不分页；
     * page/pageSize 只切 badPages 与 divergences 两个清单（各带未过滤总数），
     * page 0-based、pageSize clamp 1..200（缺省 20）。无巡检记录时 lastRun=null
     * 且两清单为空。
     */
    @GetMapping("/report")
    fun report(
        @RequestParam(value = "page", required = false, defaultValue = "0") page: Int,
        @RequestParam(value = "pageSize", required = false) pageSize: Int?,
    ): ResponseEntity<*> = ResponseEntity.ok(storageIntegrityService.buildReport(page, pageSize))

    /**
     * 整本复验（POST /reverify/{gid}，契约 reverifyIntegrityGallery；对齐统一
     * 后台任务模式 plan-2026-08-06）。
     *
     * - 无 body / interrupt=false：提交 REVERIFY 任务，worker 调 S7
     *   [ReverifyRepairOps.reverifyGallery]，终态统计（ReverifyStats）挂
     *   JobDto.result，前端轮询 GET /api/v1/jobs/{jobId}。202 + JobSubmitResponse。
     * - gid 无本地下载行 → 404 INTEGRITY_NOT_FOUND；同 type 已有活跃任务 →
     *   409 CONFLICT（JobService 单飞护栏抛 IllegalStateException，对齐全库
     *   既有 Job 端点的映射）。
     * - interrupt=true：对活跃 REVERIFY 任务置协作中断旗标（无论 gid 路径参数
     *   为何——单实例语义）。跑完当前页后以部分统计（interrupted=true）COMPLETED
     *   收场，不是 FAILED。无活跃任务 → 404 NO_ACTIVE_JOB。
     */
    @PostMapping("/reverify/{gid}")
    fun reverify(
        @PathVariable gid: Long,
        @RequestBody(required = false) request: ReverifyRequest?,
    ): ResponseEntity<*> {
        if (request?.interrupt == true) {
            val active = jobService.activeJob(JobType.REVERIFY)
                ?: return errorEnvelope(
                    HttpStatus.NOT_FOUND,
                    "NO_ACTIVE_JOB",
                    "No active REVERIFY job to interrupt",
                )
            reverifyCancel.get()?.set(true)
            logger.info("Integrity reverify interrupt requested: jobId={}", active.jobId)
            return ResponseEntity.accepted().body(JobSubmitResponse(active.jobId, active.state))
        }
        if (downloadRepository.findAllByGid(gid).isEmpty()) {
            return errorEnvelope(
                HttpStatus.NOT_FOUND,
                "INTEGRITY_NOT_FOUND",
                "No local download row for gid=$gid",
            )
        }
        val cancel = AtomicBoolean(false)
        // 先占中断旗标槽（worker 闭包引用同一实例）；提交被拒（同 type 已有活跃
        // 任务）时恢复原任务的旗标——它还活着，不能被孤儿化。
        val previous = reverifyCancel.getAndSet(cancel)
        return try {
            lateinit var job: Job
            job = jobService.submit(JobType.REVERIFY, {
                // prep：存在性已查；重活全在 worker（与 ImportController 同模式，
                // worker 自行写终态 result）。
            }) { _ ->
                try {
                    job.result = reverifyOps.reverifyGallery(gid) { cancel.get() }
                } finally {
                    reverifyCancel.compareAndSet(cancel, null)
                }
            }
            logger.info("Integrity reverify job submitted: jobId={} gid={}", job.jobId, gid)
            ResponseEntity.accepted().body(JobSubmitResponse(job.jobId, job.state))
        } catch (e: IllegalStateException) {
            // 双提交被拒：恢复活跃任务的旗标并只把冲突映射为 409。
            reverifyCancel.compareAndSet(cancel, previous)
            logger.warn("Integrity reverify submit rejected: {}", e.message)
            errorEnvelope(HttpStatus.CONFLICT, "CONFLICT", e.message ?: "已有 REVERIFY 任务进行中")
        }
    }

    /**
     * 单页强制重取（POST /refresh/{gid}/{page}，契约 repairIntegrityPage）：
     * 同步调 S7 forceRefetchPage（绕池直拉源 → V 门 → 覆写 → 基线 origin=heal
     * 刷新 → 清缓存；healed/failed 都 ride 200，failed 时本地文件不动）。
     * 修复日志由 S7 RepairLogService 在管线内落行（source=refresh），本端点
     * 不另建日志。
     *
     * - page >= 1（契约 minimum 1），非法 → 400 INTEGRITY_INVALID_PAGE。
     * - 无本地下载行，或该页无页文件 → 404 INTEGRITY_NOT_FOUND（契约 404 描述
     *   「No local download row or page file for this gid+page」的字面实现；
     *   页文件定位走 [DownloadDirIndex]，API 1-based page → findPage 入参
     *   0-based 基线页号，内部 +1 对 1-based 文件名）。
     * - S7 契约 forceRefetchPage 的 page 同为 1-based API 口径（其内部自做
     *   page-1 查基线），故直传不换算。
     */
    @PostMapping("/refresh/{gid}/{page}")
    fun refresh(
        @PathVariable gid: Long,
        @PathVariable page: Int,
    ): ResponseEntity<*> {
        if (page < 1) {
            return errorEnvelope(
                HttpStatus.BAD_REQUEST,
                "INTEGRITY_INVALID_PAGE",
                "page must be >= 1 (got $page)",
            )
        }
        if (downloadRepository.findAllByGid(gid).isEmpty()) {
            return errorEnvelope(
                HttpStatus.NOT_FOUND,
                "INTEGRITY_NOT_FOUND",
                "No local download row for gid=$gid",
            )
        }
        // 1-based API 页号 → 0-based 基线口径（DownloadDirIndex.findPage 入参）。
        if (downloadDirIndex.findPage(gid, page - 1) == null) {
            return errorEnvelope(
                HttpStatus.NOT_FOUND,
                "INTEGRITY_NOT_FOUND",
                "No page file for gid=$gid page=$page",
            )
        }
        val result = reverifyOps.forceRefetchPage(gid, page) // S7 契约 1-based，直传
        logger.info(
            "Integrity refresh: gid={} page={} status={} attribution={}",
            gid, page, result.status, result.attribution ?: "-",
        )
        return ResponseEntity.ok(result)
    }

    /**
     * 手动触发巡检（POST /scrub；契约外管理端点，任务书 S6 第 3 条）：入队
     * StorageIntegrityService 后台线程立即返回 202；已有巡检在跑 → 409 CONFLICT。
     */
    @PostMapping("/scrub")
    fun triggerScrub(): ResponseEntity<*> =
        if (storageIntegrityService.startManually()) {
            ResponseEntity.accepted().body(ScrubTriggerResponse(accepted = true))
        } else {
            errorEnvelope(HttpStatus.CONFLICT, "CONFLICT", "已有巡检在进行中")
        }

    /**
     * 同步触发 S4 维护动作（POST /backfill；契约外管理端点）。单画廊同步耗时
     * 秒级可接受；**gid 必填**（400 VALIDATION_ERROR）避免请求线程跑全库，
     * 无本地下载行 → 404 INTEGRITY_NOT_FOUND。限速取 StorageTuning 当前
     * profile 的 scrubRateLimitMbPerSec（SSD400/HDD60/ZFS120）。
     */
    @PostMapping("/backfill")
    fun backfill(@RequestBody request: BackfillRequest): ResponseEntity<*> {
        val gid = request.gid
            ?: return errorEnvelope(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "gid is required")
        if (request.kind != KIND_HASHES && request.kind != KIND_PAGE_COUNTS) {
            return errorEnvelope(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "kind must be \"$KIND_HASHES\" or \"$KIND_PAGE_COUNTS\"",
            )
        }
        if (downloadRepository.findAllByGid(gid).isEmpty()) {
            return errorEnvelope(
                HttpStatus.NOT_FOUND,
                "INTEGRITY_NOT_FOUND",
                "No local download row for gid=$gid",
            )
        }
        val rateLimit = storageTuning.current().scrubRateLimitMbPerSec
        return when (request.kind) {
            KIND_HASHES -> ResponseEntity.ok(
                backfillService.backfillHashes(gid = gid, dryRun = request.dryRun, rateLimitMbPerSec = rateLimit)
            )
            else -> ResponseEntity.ok(
                backfillService.applyDiskPageCounts(gid = gid, dryRun = request.dryRun)
            )
        }
    }

    private companion object {
        /** 单次上送条目上限（无既有先例，取 10000：千页级画廊远够，防误用撑爆请求）。 */
        const val MAX_HASHES_PER_PUSH = 10_000

        /** 契约 algo 枚举唯一值。 */
        const val VALID_ALGO = "SHA-256"

        /** 契约 hash pattern：64 个十六进制字符（大小写均可）。 */
        val HASH_PATTERN: Pattern = Pattern.compile("^[0-9a-fA-F]{64}$")

        /** /backfill body kind 取值。 */
        const val KIND_HASHES = "hashes"
        const val KIND_PAGE_COUNTS = "pageCounts"
    }
}

/**
 * S7 的窄接口缝：本控制器只依赖 reverify/refresh 两个动作，便于测试桩与未来
 * DownloadService 内部重构。生产绑定 [DownloadServiceReverifyRepairOps]（本
 * 文件内的薄适配器，一行转发，不含逻辑）。
 */
interface ReverifyRepairOps {
    /** 单页强制重取；page 为 1-based API 口径（与 S7 契约一致）。 */
    fun forceRefetchPage(gid: Long, page: Int): RepairResult

    /** 整本复验；isCancelled 每页检查，true 时以部分统计（interrupted=true）返回。 */
    fun reverifyGallery(gid: Long, isCancelled: () -> Boolean): ReverifyStats
}

/** [ReverifyRepairOps] 的生产绑定：转发 S7 的 DownloadService 契约方法。 */
@Component
class DownloadServiceReverifyRepairOps(
    private val downloadService: DownloadService,
) : ReverifyRepairOps {
    override fun forceRefetchPage(gid: Long, page: Int): RepairResult =
        downloadService.forceRefetchPage(gid, page)

    override fun reverifyGallery(gid: Long, isCancelled: () -> Boolean): ReverifyStats =
        downloadService.reverifyGallery(gid, isCancelled)
}
