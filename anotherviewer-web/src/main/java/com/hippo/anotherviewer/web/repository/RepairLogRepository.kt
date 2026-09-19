package com.hippo.anotherviewer.web.repository

import com.hippo.anotherviewer.web.entity.RepairLogEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 页修复日志（repair_log）仓库：只追加（record 落行），按画廊倒序读（遥测/
 * 管理端历史）。
 */
interface RepairLogRepository : JpaRepository<RepairLogEntity, Long> {

    /** 某画廊的修复历史（新在前）。 */
    fun findByGidOrderByIdDesc(gid: Long): List<RepairLogEntity>
}
