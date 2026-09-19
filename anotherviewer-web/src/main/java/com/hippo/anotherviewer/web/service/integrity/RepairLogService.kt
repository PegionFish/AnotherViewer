package com.hippo.anotherviewer.web.service.integrity

import com.hippo.anotherviewer.web.entity.RepairLogEntity
import com.hippo.anotherviewer.web.repository.RepairLogRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 页修复日志服务（文件完整性 Wave 3 / S7）：forceRefetchPage / reverifyGallery
 * 的每次修复尝试落一行 [RepairLogEntity]。
 *
 * 日志是 best-effort 遥测：落行失败只记日志，绝不影响修复本身的结果。
 */
@Service
class RepairLogService(
    private val repository: RepairLogRepository,
) {
    private val logger = LoggerFactory.getLogger(RepairLogService::class.java)

    /**
     * 记一次修复尝试。
     *
     * @param page0 0-based 页号（与 page_file_hash 同口径）。
     * @param attribution [RepairResult] 的归因；失败/无基线为 null。
     * @param oldHash 修复前基线哈希；无基线为 null。
     * @param newHash 覆写后的新哈希；失败（未覆写）为 null。
     * @param source [SOURCE_REFRESH]（单页强制重取）| [SOURCE_REVERIFY]（整本复验）。
     */
    fun record(
        gid: Long,
        page0: Int,
        attribution: String?,
        oldHash: String?,
        newHash: String?,
        source: String,
    ) {
        try {
            repository.save(
                RepairLogEntity().apply {
                    this.gid = gid
                    this.page = page0
                    this.attribution = attribution
                    this.oldHash = oldHash
                    this.newHash = newHash
                    this.repairedAt = System.currentTimeMillis()
                    this.source = source
                }
            )
        } catch (e: Exception) {
            logger.warn(
                "Failed to persist repair log for gid={} page={}: {}",
                gid, page0, e.message
            )
        }
    }

    /** 某画廊的修复历史（新在前），供管理端点/遥测聚合使用。 */
    fun history(gid: Long): List<RepairLogEntity> = repository.findByGidOrderByIdDesc(gid)

    companion object {
        /** 单页强制重取（管理端点直发）。 */
        const val SOURCE_REFRESH = "refresh"

        /** 整本复验（reverifyGallery）连带修复。 */
        const val SOURCE_REVERIFY = "reverify"
    }
}
