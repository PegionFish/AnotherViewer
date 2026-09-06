package com.hippo.anotherviewer.web.repository

import com.hippo.anotherviewer.web.entity.HistoryInfoEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface HistoryInfoRepository : JpaRepository<HistoryInfoEntity, Long> {
    fun findByGid(gid: Long): HistoryInfoEntity?
    /**
     * A7-3（P1-1）：同 gid 多行（历史脏数据/属主并存）时单实体 [findByGid] 派生查询
     * 会抛 IncorrectResultSizeDataAccessException，毒化整条同步通道。同步仲裁与
     * 写前查找一律走本 List 版本 + 属主/存活 firstOrNull。
     */
    fun findAllByGid(gid: Long): List<HistoryInfoEntity>
    /** S7: 列表端点（history/favorites/downloads）批量取阅读进度，避免逐行 findByGid 的 N+1。 */
    fun findByGidIn(gids: Collection<Long>): List<HistoryInfoEntity>
    fun findAllByOrderByTimeDesc(): List<HistoryInfoEntity>
    fun findAllByUsernameIsNull(): List<HistoryInfoEntity>
    fun countByUsername(username: String): Long
    @Transactional
    fun deleteByGid(gid: Long)
    /** H-3: 同步增量拉取按 (username, lastModified) 走索引查询，避免全表扫描后内存过滤。 */
    fun findByUsernameAndLastModifiedGreaterThan(username: String, lastModified: Long): List<HistoryInfoEntity>
    /** H-3: 全量拉取 (since=0) —— 必须返回 lastModified=0 的合法记录，故不过滤 lastModified。 */
    fun findByUsername(username: String): List<HistoryInfoEntity>

    /** DB-paginated local history, newest first (empty-keyword fallback). 墓碑行不列进 REST 列表。 */
    @Query("select h from HistoryInfoEntity h where h.deleted = false order by h.time desc")
    fun findHistoryPaged(pageable: Pageable): Page<HistoryInfoEntity>

    /** A7-2（H2）: DB-paginated local history filtered by category, newest first, live rows only. */
    fun findByCategoryAndDeletedFalseOrderByTimeDesc(category: Int, pageable: Pageable): Page<HistoryInfoEntity>

    /**
     * P2: 子串 q 过滤下沉 DB（title/titleJpn LIKE %kw%，大小写不敏感，仅存活行），
     * time 倒序 + DB 分页——替代旧的不分页全表载入 + 内存过滤路径。
     * 已知取舍：q 中的 LIKE 通配符（%/_）不转义，只可能放大匹配面、不会漏配，
     * 与下方既有 keyword 先例查询保持同一行为。
     */
    @Query("""
        select h from HistoryInfoEntity h
        where h.deleted = false
          and ( lower(h.title) like lower(concat('%', :keyword, '%'))
             or lower(h.titleJpn) like lower(concat('%', :keyword, '%')) )
        order by h.time desc
    """)
    fun findLiveByTitleOrTitleJpnContainingPaged(
        @Param("keyword") keyword: String,
        pageable: Pageable
    ): Page<HistoryInfoEntity>

    /** P2: 同上但不分页——仅作 regex 路径的 DB 预过滤窗口（内存 regex 只跑在该集合上）。 */
    @Query("""
        select h from HistoryInfoEntity h
        where h.deleted = false
          and ( lower(h.title) like lower(concat('%', :keyword, '%'))
             or lower(h.titleJpn) like lower(concat('%', :keyword, '%')) )
        order by h.time desc
    """)
    fun findLiveByTitleOrTitleJpnContaining(@Param("keyword") keyword: String): List<HistoryInfoEntity>
}
