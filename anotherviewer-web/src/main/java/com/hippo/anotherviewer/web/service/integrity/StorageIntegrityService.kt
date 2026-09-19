package com.hippo.anotherviewer.web.service.integrity

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.dto.IntegrityBadPageDto
import com.hippo.anotherviewer.web.dto.IntegrityDivergenceDto
import com.hippo.anotherviewer.web.dto.IntegrityReport
import com.hippo.anotherviewer.web.dto.IntegrityScrubRunDto
import com.hippo.anotherviewer.web.entity.PageFileHashEntity
import com.hippo.anotherviewer.web.entity.ScrubRunEntity
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import com.hippo.anotherviewer.web.repository.PeerHashRepository
import com.hippo.anotherviewer.web.repository.ScrubRunRepository
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.ServerConfigService
import com.hippo.anotherviewer.web.service.storage.StorageTuning
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 存储完整性巡检引擎（文件完整性 Wave 3 / S6）：逐页读盘 + SHA-256 + 比对基线，
 * 附带零网络交叉审计（page_file_hash vs peer_hash）。写 scrub_run 运行记录。
 *
 * 语义：
 * - **只判定不修复**：一致 → verdict=ok 并刷新 last_verified_at；不一致 → 复读
 *   一次确认（排除传输毛刺）→ verdict=struct_bad（基线 hash/size 等字段绝不动，
 *   last_verified_at 不刷新——坏页在 LRU 里保持靠前，下轮优先复查）。修复只走
 *   refresh 端点（S7 forceRefetchPage）/运维。
 * - LRU 顺序：last_verified_at null 最先，再按时间升序（同刻按 gid,page 稳定排序）
 *   ——每月 cron 逐月摊平全库，坏页永远先查。
 * - 目录解析一律走 [DownloadDirIndex]（磁盘目录是 `{gid}-{title}` 形态，绝不裸
 *   `File(root, gid)`）。
 * - 限速/优先级：[StorageTuning.current()]——scrubRateLimitMbPerSec（SSD400/HDD60/
 *   ZFS120）做累计字节睡眠节流；maintenanceIoPriority=IDLE 时巡检线程降到
 *   MIN_PRIORITY 让路前台读。
 * - 断点（[ServerConfigService] KV，低频每 [CHECKPOINT_EVERY_ROWS] 行一写）：
 *   键值 `v1|<seqStartedAt>|<gid>|<page>`。seqStartedAt 是本轮巡检**序列**的起点
 *   （中断续跑时向后传递，不清零）：续跑按 LRU 重排后跳过
 *   last_verified_at >= seqStartedAt 的行——它们本轮已判 ok。struct_bad 行不刷
 *   last_verified_at，天然落在续跑复查范围。跑完清 KV；进程崩溃后重启同理续跑。
 * - 交叉审计（零网络）：巡检对同 (gid,page) 比对基线与 peer_hash——两侧都有且
 *   一致 = 一致；都有但不一致 = 版本分歧（记 divergences_json 快照）；仅单侧有 =
 *   单侧计数。peer_hash 只读（红线：本服务绝不写 peer 证据，也绝不以 peer 覆盖
 *   己方基线）。
 */
@Service
class StorageIntegrityService(
    private val config: SiteCoreConfigProperties,
    private val dirIndex: DownloadDirIndex,
    private val hashRepository: PageFileHashRepository,
    private val peerHashRepository: PeerHashRepository,
    private val scrubRunRepository: ScrubRunRepository,
    private val serverConfigService: ServerConfigService,
    private val storageTuning: StorageTuning,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(StorageIntegrityService::class.java)

    /** 单飞护栏：定时触发与手动触发、以及双击手动，都只允许一个巡检在跑。 */
    private val running = AtomicBoolean(false)

    /** 协作中断旗标（[requestStop] 置位；巡检在下一行边界观察到即停）。 */
    @Volatile
    private var stopRequested = false

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "integrity-scrub").apply { isDaemon = true }
    }

    /**
     * 测试缝：检查满 [testStopAfterRows] 行后按中断语义收场（落断点、status=
     * interrupted），模拟进程中断；null = 不限。生产路径恒为 null。
     */
    @Volatile
    internal var testStopAfterRows: Int? = null

    /** 测试缝：runScrub 开头在此闩上等待（≤30s 自动放行），供后台/单飞测试定格。 */
    @Volatile
    internal var testPause: CountDownLatch? = null

    /** 崩溃恢复：上次进程没跑完的 running 行改为 interrupted（finishedAt 保持 null）。 */
    @PostConstruct
    fun recoverStaleRuns() {
        scrubRunRepository.findByStatus(ScrubRunEntity.STATUS_RUNNING).forEach { stale ->
            stale.status = ScrubRunEntity.STATUS_INTERRUPTED
            scrubRunRepository.save(stale)
            logger.warn("Scrub: marked stale running run #{} as interrupted (process restart)", stale.id)
        }
    }

    // ── 触发 ─────────────────────────────────────────────────────────────────

    /** 月度定时巡检（默认每月 1 日 03:00，anotherviewer.integrity.scrub-cron）。 */
    @Scheduled(cron = "\${anotherviewer.integrity.scrub-cron:0 0 3 1 * ?}")
    fun scheduledScrub() {
        start(trigger = "scheduled")
    }

    /**
     * 手动触发（POST /integrity/scrub 由控制器调）：入队后台线程，立即返回。
     *
     * @return false = 已有巡检在跑（控制器映射 409）。
     */
    fun startManually(): Boolean = start(trigger = "manual")

    /** 请求协作中断当前巡检（当前行处理完即停，落断点，status=interrupted）。 */
    fun requestStop() {
        stopRequested = true
    }

    fun isRunning(): Boolean = running.get()

    // ── 主流程 ───────────────────────────────────────────────────────────────

    private fun start(trigger: String): Boolean {
        if (!running.compareAndSet(false, true)) return false
        executor.execute {
            try {
                runScrub(trigger)
            } finally {
                running.set(false)
                stopRequested = false
            }
        }
        return true
    }

    /**
     * 巡检主流程（[start] 的后台线程体；internal 供测试同步直调）。
     */
    internal fun runScrub(trigger: String) {
        testPause?.await(30, TimeUnit.SECONDS)
        val tuning = storageTuning.current()
        val rateLimitMbPerSec = tuning.scrubRateLimitMbPerSec
        val startedAt = System.currentTimeMillis()
        val startNanos = System.nanoTime()

        // IDLE 优先级（HDD/ZFS）：巡检线程让路前台读；NORMAL（SSD）不动。
        if (tuning.maintenanceIoPriority == StorageTuning.IoPriority.IDLE) {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
        }

        // 断点：中断续跑锚点。seqStartedAt 向后传递（见类 KDoc「断点」）。
        val checkpoint = readCheckpoint()
        val resumed = checkpoint != null
        val seqStartedAt = checkpoint?.seqStartedAt ?: startedAt
        if (resumed) {
            logger.info("Scrub: resuming interrupted run sequence started at {}", seqStartedAt)
        }

        val run = scrubRunRepository.save(ScrubRunEntity().apply { this.startedAt = startedAt })

        // LRU 快照：last_verified_at null 最先，再按升序；断点跳过在循环内做
        // （跳过的行不计入 examined——「totalPages = 本轮实际检查的页文件数」）。
        val baselineRows = hashRepository.findAll()
            .sortedWith(compareBy({ it.lastVerifiedAt ?: Long.MIN_VALUE }, { it.gid }, { it.page }))

        var examined = 0
        var ok = 0
        var bad = 0
        var bytesRead = 0L
        var sinceCheckpoint = 0
        var lastProcessed: Pair<Long, Int>? = null
        var stopped = false
        val findings = mutableListOf<IntegrityBadPageDto>()

        loop@ for (row in baselineRows) {
            if (stopRequested) {
                stopped = true
                break@loop
            }
            // 无基线行（BackfillService 落的 struct_bad 空哈希行）：无可比对，
            // 不读盘不计 examined——它们已在坏页清单里，等回填/复验处置。
            if (row.hash.isBlank()) continue
            // 本轮序列已判 ok 的行：断点续跑直接跳过（last_verified_at 已是本轮写的）。
            val verifiedAt = row.lastVerifiedAt
            if (resumed && verifiedAt != null && verifiedAt >= seqStartedAt) continue

            examined++
            lastProcessed = row.gid to row.page
            // 目录解析走 DownloadDirIndex（绝不裸 File(root, gid)）；findPage 入参
            // 0-based 基线页号，内部 +1 对 1-based 文件名。
            val dir = dirIndex.dirFor(row.gid)
            val ref = dirIndex.findPage(row.gid, row.page)
            val file = if (dir != null && ref != null) File(dir, ref.fileName) else null

            when {
                file == null || !file.isFile -> {
                    bad++
                    findings += IntegrityBadPageDto(row.gid, row.page + 1, VERDICT_MISSING)
                    markStructBad(row)
                }
                else -> {
                    bytesRead += file.length()
                    val hash = runCatching { sha256Of(file) }
                    if (hash.isSuccess && hash.getOrDefault("") == row.hash) {
                        ok++
                        markOk(row)
                    } else {
                        // 复读确认：毛刺（并发覆写/瞬时 IO）会让单次读偶发不一致。
                        bytesRead += file.length()
                        val confirmed = runCatching { sha256Of(file) }.getOrNull()
                        if (confirmed != null && confirmed == row.hash) {
                            ok++
                            markOk(row)
                        } else if (confirmed == null) {
                            bad++
                            findings += IntegrityBadPageDto(row.gid, row.page + 1, VERDICT_READ_ERROR)
                            markStructBad(row)
                        } else {
                            bad++
                            findings += IntegrityBadPageDto(
                                gid = row.gid,
                                page = row.page + 1,
                                verdict = VERDICT_MISMATCH,
                                size = file.length(),
                                hashShort = confirmed.take(12),
                            )
                            markStructBad(row)
                        }
                    }
                }
            }

            sinceCheckpoint++
            if (sinceCheckpoint >= CHECKPOINT_EVERY_ROWS) {
                writeCheckpoint(seqStartedAt, lastProcessed)
                sinceCheckpoint = 0
            }
            throttle(bytesRead, rateLimitMbPerSec, startNanos)

            val limit = testStopAfterRows
            if (limit != null && examined >= limit) {
                stopped = true
                break@loop
            }
        }

        // 交叉审计（零网络，纯库比对；便宜，中断/完成都做）。
        val divergenceDtos = crossAudit(baselineRows)
        val oneSided = countOneSided(baselineRows)

        run.finishedAt = System.currentTimeMillis()
        run.status = if (stopped) ScrubRunEntity.STATUS_INTERRUPTED else ScrubRunEntity.STATUS_COMPLETED
        run.totalPages = examined
        run.okPages = ok
        run.badPages = bad
        run.divergences = divergenceDtos.size
        run.oneSided = oneSided
        run.badPagesJson = objectMapper.writeValueAsString(findings)
        run.divergencesJson = objectMapper.writeValueAsString(divergenceDtos)
        scrubRunRepository.save(run)

        if (stopped) {
            lastProcessed?.let { writeCheckpoint(seqStartedAt, it) }
        } else {
            clearCheckpoint()
        }

        logger.info(
            "Scrub done ({}): run={} status={} examined={} ok={} bad={} divergences={} oneSided={} resumed={} rateLimit={}MB/s {}ms",
            trigger, run.id, run.status, examined, ok, bad, divergenceDtos.size, oneSided,
            resumed, rateLimitMbPerSec, (System.nanoTime() - startNanos) / 1_000_000,
        )
    }

    // ── 交叉审计（零网络；peer_hash 只读） ───────────────────────────────────

    /** 两侧都有但哈希不一致（版本分歧），按 (gid,page) 升序。 */
    private fun crossAudit(baselineRows: List<PageFileHashEntity>): List<IntegrityDivergenceDto> {
        val peerByKey = peerHashRepository.findAll().associateBy { it.gid to it.page }
        return baselineRows.mapNotNull { row ->
            val peer = peerByKey[row.gid to row.page] ?: return@mapNotNull null
            if (row.hash.isNotBlank() && !row.hash.equals(peer.hash, ignoreCase = true)) {
                IntegrityDivergenceDto(
                    gid = row.gid,
                    page = row.page + 1, // 0-based 基线页号 → 1-based 契约口径
                    localHash = row.hash,
                    peerHash = peer.hash.lowercase(),
                )
            } else {
                null
            }
        }.sortedWith(compareBy({ it.gid }, { it.page }))
    }

    /** 仅单侧有：基线行无 peer 证据 + peer 证据无基线行。 */
    private fun countOneSided(baselineRows: List<PageFileHashEntity>): Int {
        val peerKeys = peerHashRepository.findAll().mapTo(HashSet()) { it.gid to it.page }
        val baselineKeys = baselineRows.mapTo(HashSet()) { it.gid to it.page }
        var count = 0
        baselineRows.forEach { if ((it.gid to it.page) !in peerKeys) count++ }
        peerKeys.forEach { if (it !in baselineKeys) count++ }
        return count
    }

    // ── 基线回写（只动 verdict/last_verified_at，绝不碰基线内容） ─────────────

    private fun markOk(row: PageFileHashEntity) {
        row.verdict = VERDICT_OK
        row.lastVerifiedAt = System.currentTimeMillis()
        hashRepository.save(row)
    }

    /** verdict 标坏即止：last_verified_at 不动（坏页保持 LRU 靠前），hash 不动（基线是修复归因的锚）。 */
    private fun markStructBad(row: PageFileHashEntity) {
        row.verdict = VERDICT_STRUCT_BAD
        hashRepository.save(row)
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(READ_BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // ── 断点 KV（紧凑管道格式：v1|seqStartedAt|gid|page）─────────────────────

    private data class Checkpoint(val seqStartedAt: Long, val gid: Long, val page: Int)

    private fun readCheckpoint(): Checkpoint? {
        val raw = serverConfigService.get(KEY_CHECKPOINT, "")
        if (raw.isBlank()) return null
        val parts = raw.split("|", limit = 4)
        if (parts.size != 4 || parts[0] != CHECKPOINT_FORMAT) {
            logger.warn("Scrub: ignoring malformed checkpoint [{}]", raw)
            return null
        }
        val seqStartedAt = parts[1].toLongOrNull() ?: return null
        val gid = parts[2].toLongOrNull() ?: return null
        val page = parts[3].toIntOrNull() ?: return null
        return Checkpoint(seqStartedAt, gid, page)
    }

    private fun writeCheckpoint(seqStartedAt: Long, position: Pair<Long, Int>) {
        serverConfigService.set(KEY_CHECKPOINT, "$CHECKPOINT_FORMAT|$seqStartedAt|${position.first}|${position.second}")
    }

    private fun clearCheckpoint() {
        serverConfigService.findByKeyStartingWith(KEY_CHECKPOINT).forEach { serverConfigService.delete(it) }
    }

    // ── 限速：累计字节 + 睡眠补齐预算（与 BackfillService 同式） ───────────────

    private fun throttle(bytesRead: Long, rateLimitMbPerSec: Int, startNanos: Long) {
        if (rateLimitMbPerSec <= 0 || bytesRead <= 0) return
        val budgetNanos = bytesRead * 1_000_000_000L / (rateLimitMbPerSec.toLong() * 1024 * 1024)
        val sleepNanos = budgetNanos - (System.nanoTime() - startNanos)
        if (sleepNanos > 0) {
            runCatching { Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt()) }
        }
    }

    // ── report（GET /api/v1/integrity/report 的取数与装配） ──────────────────

    /**
     * 最近巡检摘要 + 坏页清单（struct_bad 当前态行，分页）+ 分歧清单（最近巡检的
     * 交叉审计快照，分页）。坏页判定细分取自最近巡检快照（run 行 bad_pages_json），
     * 无快照时启发式回退（无基线哈希 → MISSING，否则 MISMATCH）。
     */
    fun buildReport(page: Int, pageSize: Int?): IntegrityReport {
        val safePage = page.coerceAtLeast(0)
        val size = (pageSize ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)

        val lastRun = scrubRunRepository.findFirstByOrderByIdDesc()
        val lastRunDto = lastRun?.let {
            IntegrityScrubRunDto(
                startedAt = it.startedAt,
                finishedAt = it.finishedAt,
                totalPages = it.totalPages,
                badPages = it.badPages,
                divergences = it.divergences,
                oneSided = it.oneSided,
            )
        }

        // 坏页清单 = verdict=struct_bad 当前态行（修复/heal 刷新后自然出清单）。
        val reasons = lastRun?.badPagesJson
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { objectMapper.readValue<List<IntegrityBadPageDto>>(it) }.getOrNull() }
            ?.associateBy { "${it.gid}:${it.page}" }
            ?: emptyMap()
        val badPages = hashRepository.findAll()
            .filter { it.verdict == VERDICT_STRUCT_BAD }
            .sortedWith(compareBy({ it.gid }, { it.page }))
            .map { row ->
                val snapshot = reasons["${row.gid}:${row.page + 1}"]
                IntegrityBadPageDto(
                    gid = row.gid,
                    page = row.page + 1, // 0-based 基线页号 → 1-based 契约口径
                    verdict = snapshot?.verdict
                        ?: if (row.hash.isBlank()) VERDICT_MISSING else VERDICT_MISMATCH,
                    size = snapshot?.size ?: row.size.takeIf { row.hash.isNotBlank() },
                    hashShort = snapshot?.hashShort ?: row.hash.take(12).ifEmpty { null },
                )
            }

        // 分歧清单 = 最近一份交叉审计快照（scrub_run 之间可查）。
        val divergences = scrubRunRepository.findFirstByDivergencesJsonIsNotNullOrderByIdDesc()
            ?.divergencesJson
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { objectMapper.readValue<List<IntegrityDivergenceDto>>(it) }.getOrNull() }
            ?: emptyList()

        return IntegrityReport(
            lastRun = lastRunDto,
            badPages = badPages.drop(safePage * size).take(size),
            badPageTotal = badPages.size,
            divergences = divergences.drop(safePage * size).take(size),
            divergenceTotal = divergences.size,
        )
    }

    companion object {
        /** 断点 KV 键（ServerConfigService）。 */
        const val KEY_CHECKPOINT = "integrity.scrub.checkpoint"

        private const val CHECKPOINT_FORMAT = "v1"

        /** 断点 KV 写入节奏（行数）：写即失效 ServerConfigService 60s 缓存，刻意低频。 */
        private const val CHECKPOINT_EVERY_ROWS = 50

        private const val READ_BUFFER = 64 * 1024

        const val VERDICT_OK = "ok"
        const val VERDICT_STRUCT_BAD = "struct_bad"
        const val VERDICT_MISSING = "MISSING"
        const val VERDICT_MISMATCH = "MISMATCH"
        const val VERDICT_READ_ERROR = "READ_ERROR"

        private const val DEFAULT_PAGE_SIZE = 20
        private const val MAX_PAGE_SIZE = 200
    }
}
