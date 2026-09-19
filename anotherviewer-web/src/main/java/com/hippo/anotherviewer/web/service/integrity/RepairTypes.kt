package com.hippo.anotherviewer.web.service.integrity

import com.hippo.anotherviewer.web.entity.PageFileHashEntity
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * 单页强制重取（forceRefetchPage）的结果（文件完整性 Wave 3 / S7）。
 *
 * @param status "healed"（已覆写池文件并刷新基线）| "failed"（源不可得 / V 门拒收，
 *   本地文件未动）。
 * @param attribution 归因（仅 healed 时有意义；failed 恒为 null）：
 *   "local_corrupt" = 覆写字节哈希 == 旧基线（本地介质劣化，源未变）；
 *   "source_changed" = 覆写字节哈希 != 旧基线（源内容已变）；
 *   null = 无旧基线可比（无法归因，两种处置相同）。
 * @param message 失败原因（healed 时为 null）。
 */
data class RepairResult(
    val status: String,
    val attribution: String?,
    val message: String? = null,
) {
    companion object {
        const val STATUS_HEALED = "healed"
        const val STATUS_FAILED = "failed"

        const val ATTR_LOCAL_CORRUPT = "local_corrupt"
        const val ATTR_SOURCE_CHANGED = "source_changed"
    }
}

/**
 * 整本复验（reverifyGallery）统计。
 *
 * @param total 实际逐页检查的页数（中断时 = 已检查的页数，不含未检查的尾部）。
 * @param ok 好页数（读盘哈希 == 基线；或无基线但结构完好——均不拉源）。
 * @param bad 坏页数（文件缺失 / 读不出 / 哈希不符 / 结构坏），含修复失败的页。
 * @param refreshed 坏页中成功修复（healed）的页数。
 * @param interrupted 被调用方 isCancelled 中断（部分统计）。
 */
data class ReverifyStats(
    val total: Int,
    val ok: Int,
    val bad: Int,
    val refreshed: Int,
    val interrupted: Boolean = false,
)

/**
 * SHA-256 基线 upsert 的 integrity 包共享实现（Wave 3 / S7 从 DownloadService
 * 上取）：downloader 落盘钩子与 heal 修复覆写共用同一条「改行再 save」路径，
 * 保证 origin 各异但刷新语义（verdict/last_verified_at/origin/createdAt 全复位）
 * 恒一致。
 *
 * upsert 契约（Wave 1）：先 [PageFileHashRepository.findByGidAndPage] 取旧行改
 * 字段再 save——复合主键覆盖语义，不误伤巡检字段；覆盖写已有基线 = 刷新
 * hash/size/ext 并把 verdict/last_verified_at 复位为未巡检（内容已变，旧巡检
 * 结论作废）。任何异常只记日志不上抛：基线是 best-effort 附属产物，不得让已
 * 落盘的页被记为失败。
 */
object PageHashWriter {

    private val logger = LoggerFactory.getLogger(PageHashWriter::class.java)

    const val ALGO_SHA256 = "sha256"

    /** SHA-256 hex（64 字符小写）。 */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * 对已落盘的页字节 upsert 基线行（0-based [page0]），origin 记写入来源
     * （downloader|read_sync|heal|import）。
     */
    fun upsert(
        repository: PageFileHashRepository,
        gid: Long,
        page0: Int,
        file: File,
        bytes: ByteArray,
        writeOrigin: String,
    ) {
        try {
            val now = System.currentTimeMillis()
            val row = repository.findByGidAndPage(gid, page0) ?: PageFileHashEntity().also {
                it.gid = gid
                it.page = page0
            }
            row.ext = file.extension.lowercase().ifEmpty { "jpg" }
            row.size = bytes.size.toLong()
            row.hash = sha256Hex(bytes)
            row.algo = ALGO_SHA256
            row.origin = writeOrigin
            row.createdAt = now
            row.lastVerifiedAt = null
            row.verdict = null
            repository.save(row)
        } catch (e: Exception) {
            logger.warn(
                "Failed to persist page hash baseline for gid={} page={}: {}",
                gid, page0, e.message
            )
        }
    }
}
