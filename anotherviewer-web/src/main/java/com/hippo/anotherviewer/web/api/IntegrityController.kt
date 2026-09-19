package com.hippo.anotherviewer.web.api

import com.hippo.anotherviewer.web.dto.IntegrityHashAcceptResponse
import com.hippo.anotherviewer.web.dto.IntegrityHashEntry
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.repository.PeerHashRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.regex.Pattern

/**
 * 下载文件完整性 API（文件完整性 Wave 2 / S5；契约 contracts/openapi.yaml
 * 「Integrity」段）。鉴权与其余 /api 端点一致——Bearer token，由 SecurityConfig
 * 的全局规则（/api 前缀通配 authenticated）覆盖，本控制器零额外配置。
 *
 * 本波只实现 peer 证据接收（[pushHashes]）；Wave 3 S6 在同一文件追加
 * refresh/{gid}/{page}、reverify/{gid}、report 端点——请求 DTO 归
 * IntegrityDto.kt，端点间互不共享状态，直接加 @PostMapping/@GetMapping 即可。
 *
 * 语义红线（Wave 1 C1 契约）：peer 证据只进 peer_hash 表，绝不触碰
 * page_file_hash 基线——本控制器根本不注入 PageFileHashRepository，结构上
 * 排除误写。
 */
@RestController
@RequestMapping("/api/v1/integrity")
class IntegrityController(
    private val peerHashRepository: PeerHashRepository,
    private val downloadRepository: DownloadInfoRepository,
) {

    private val logger = LoggerFactory.getLogger(IntegrityController::class.java)

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
     *   Wave 3 交叉审计直接按 (gid,page) 联表。哈希统一归一小写落库
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

    private companion object {
        /** 单次上送条目上限（无既有先例，取 10000：千页级画廊远够，防误用撑爆请求）。 */
        const val MAX_HASHES_PER_PUSH = 10_000

        /** 契约 algo 枚举唯一值。 */
        const val VALID_ALGO = "SHA-256"

        /** 契约 hash pattern：64 个十六进制字符（大小写均可）。 */
        val HASH_PATTERN: Pattern = Pattern.compile("^[0-9a-fA-F]{64}$")
    }
}
