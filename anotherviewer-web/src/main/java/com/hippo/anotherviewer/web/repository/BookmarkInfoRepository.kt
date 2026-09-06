package com.hippo.anotherviewer.web.repository

import com.hippo.anotherviewer.web.entity.BookmarkInfoEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional

interface BookmarkInfoRepository : JpaRepository<BookmarkInfoEntity, Long> {
    fun findByGid(gid: Long): BookmarkInfoEntity?
    /**
     * A7-3（P1-1）：同 gid 多行（历史脏数据/属主并存）时单实体 [findByGid] 派生查询
     * 会抛 IncorrectResultSizeDataAccessException，毒化整条同步通道。同步仲裁与
     * 写前查找一律走本 List 版本 + 属主/存活 firstOrNull。
     */
    fun findAllByGid(gid: Long): List<BookmarkInfoEntity>
    fun findByCategory(category: Int): List<BookmarkInfoEntity>
    fun findAllByUsernameIsNull(): List<BookmarkInfoEntity>
    fun countByUsername(username: String): Long
    @Transactional
    fun deleteByGid(gid: Long)
    /** H-3: 同步增量拉取按 (username, lastModified) 走索引查询，避免全表扫描后内存过滤。 */
    fun findByUsernameAndLastModifiedGreaterThan(username: String, lastModified: Long): List<BookmarkInfoEntity>
    /** H-3: 全量拉取 (since=0) —— 必须返回 lastModified=0 的合法记录，故不过滤 lastModified。 */
    fun findByUsername(username: String): List<BookmarkInfoEntity>
}
