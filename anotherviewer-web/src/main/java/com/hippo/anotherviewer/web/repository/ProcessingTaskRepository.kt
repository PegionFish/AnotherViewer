package com.hippo.anotherviewer.web.repository

import com.hippo.anotherviewer.web.entity.ProcessingTaskEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProcessingTaskRepository : JpaRepository<ProcessingTaskEntity, Long> {

    fun findByTaskId(taskId: String): ProcessingTaskEntity?

    /** 活跃任务（PENDING/PROCESSING），新→旧。 */
    fun findByStateInOrderByCreatedAtDesc(states: Collection<String>): List<ProcessingTaskEntity>

    /** 历史分页；state 为 null 时走全量重载。 */
    fun findByStateInOrderByCreatedAtDesc(states: Collection<String>, pageable: Pageable): Page<ProcessingTaskEntity>

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<ProcessingTaskEntity>

    /** 页级去重（D8）：某画廊某类型下已 DONE 任务的完成页 CSV 集合。 */
    @Query(
        "select t.donePages from ProcessingTaskEntity t" +
            " where t.galleryId = :galleryId and t.processingType = :type and t.state = 'DONE'",
    )
    fun findDonePageCsv(
        @Param("galleryId") galleryId: Long,
        @Param("type") type: String,
    ): List<String>

    /**
     * 启动对账：把遗留的 PENDING/PROCESSING 行批量置 FAILED（EP_INTERRUPTED）。
     * 服务重启后内存任务表与 EntryPoint 注册表双双清空，无从续查——诚实标失败。
     */
    @Modifying
    @Query(
        "update ProcessingTaskEntity t set t.state = 'FAILED', t.errorCode = 'EP_INTERRUPTED'," +
            " t.errorMessage = concat(t.errorMessage, '服务重启中断'), t.finishedAt = :now, t.updatedAt = :now" +
            " where t.state in ('PENDING', 'PROCESSING')",
    )
    fun markInterrupted(@Param("now") now: Long): Int
}
