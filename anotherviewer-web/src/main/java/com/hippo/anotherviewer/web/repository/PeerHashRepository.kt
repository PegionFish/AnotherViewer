package com.hippo.anotherviewer.web.repository

import com.hippo.anotherviewer.web.entity.PeerHashEntity
import com.hippo.anotherviewer.web.entity.PeerHashId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

/**
 * 对端页哈希证据（peer_hash）仓库。
 *
 * 对端证据是纯 KV 覆盖语义（无生命周期列可误伤），upsert 用 SQLite 原生
 * INSERT OR REPLACE 原子整行替换，而非 find→save 两步。
 */
interface PeerHashRepository : JpaRepository<PeerHashEntity, PeerHashId> {

    /** 单页对端证据；无记录返回 null。 */
    fun findByGidAndPage(gid: Long, page: Int): PeerHashEntity?

    /** 整本对端证据（交叉审计）；页序不保证，需要时调用方排序。 */
    fun findByGid(gid: Long): List<PeerHashEntity>

    /** 删除整本对端证据（画廊删除时清库）。 */
    @Transactional
    fun deleteByGid(gid: Long)

    /**
     * upsert：同 (gid, page) 二次写入 = 整行覆盖更新，不增行。
     * 需在事务内调用（@Modifying，绕过持久化上下文，读回前须 flush/clear）。
     */
    @Modifying
    @Query(
        value = "INSERT OR REPLACE INTO peer_hash (gid, page, hash, updated_at)" +
            " VALUES (:gid, :page, :hash, :updatedAt)",
        nativeQuery = true,
    )
    fun upsert(
        @Param("gid") gid: Long,
        @Param("page") page: Int,
        @Param("hash") hash: String,
        @Param("updatedAt") updatedAt: Long,
    )
}
