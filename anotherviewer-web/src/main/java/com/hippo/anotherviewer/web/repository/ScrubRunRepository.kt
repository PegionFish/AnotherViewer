package com.hippo.anotherviewer.web.repository

import com.hippo.anotherviewer.web.entity.ScrubRunEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 巡检运行记录（scrub_run）仓库。
 *
 * 行只在巡检结束时整体回填一次（running → completed/interrupted），无并发更新；
 * report 读最近一行（含 running）与最近一份分歧快照。
 */
interface ScrubRunRepository : JpaRepository<ScrubRunEntity, Long> {

    /** 最近一次巡检（含 running——finishedAt=null 即进行中）。 */
    fun findFirstByOrderByIdDesc(): ScrubRunEntity?

    /** 最近一份带交叉审计分歧快照的巡检（peer 证据时点快照，跨 run 可查）。 */
    fun findFirstByDivergencesJsonIsNotNullOrderByIdDesc(): ScrubRunEntity?

    /** 崩溃恢复：启动时把遗留的 running 行改为 interrupted。 */
    fun findByStatus(status: String): List<ScrubRunEntity>
}
