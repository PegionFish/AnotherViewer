package com.hippo.anotherviewer.web.repository

import com.hippo.anotherviewer.web.entity.PageFileHashEntity
import com.hippo.anotherviewer.web.entity.PageFileHashId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional

/**
 * 页文件完整性基线（page_file_hash）仓库。
 *
 * upsert 契约：save() 对本实体（复合主键、无 @Version）走 merge 语义——
 * 同 (gid, page) 二次写入 = 覆盖更新不增行；调用方先 findByGidAndPage
 * 取旧行改字段再 save（保护 verdict/last_verified_at 不被整行替换误伤）。
 */
interface PageFileHashRepository : JpaRepository<PageFileHashEntity, PageFileHashId> {

    /** 单页基线（写前查找、巡检读取）；无基线返回 null。 */
    fun findByGidAndPage(gid: Long, page: Int): PageFileHashEntity?

    /** 整本基线（交叉审计 peer_hash、整本 reverify）；页序不保证，需要时调用方排序。 */
    fun findByGid(gid: Long): List<PageFileHashEntity>

    /** 删除整本基线（画廊删除时清库）。 */
    @Transactional
    fun deleteByGid(gid: Long)
}
