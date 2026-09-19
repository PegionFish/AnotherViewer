package com.hippo.anotherviewer.web.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

/** peer_hash 复合主键 (gid, page)。 */
data class PeerHashId(
    var gid: Long = 0,
    var page: Int = 0,
) : Serializable

/**
 * 对端（Android）页哈希证据（文件完整性 Wave 1）：同步时对端上报的每页哈希，
 * 供服务器侧交叉审计（page_file_hash vs peer_hash，零网络）与差异归因。
 *
 * C1 契约：本表只写不改己方基线——写入 peer_hash 永远不触碰 page_file_hash，
 * 也不作为本地巡检的基准。对端证据是纯 KV 覆盖语义（无生命周期列），
 * 同 (gid, page) 二次上报整行替换（见 PeerHashRepository.upsert）。
 *
 * SQLite DDL 惯例：NOT NULL 列必须带 columnDefinition default（ddl-auto update 加列限制）；
 * 时间戳一律 Long epoch millis。
 */
@Entity
@Table(name = "peer_hash")
@IdClass(PeerHashId::class)
class PeerHashEntity {
    @Id
    @Column(nullable = false)
    var gid: Long = 0

    /** 0-based 页号。 */
    @Id
    @Column(nullable = false)
    var page: Int = 0

    /** 对端上报的 SHA-256 hex（64 字符）。 */
    @Column(nullable = false, length = 64, columnDefinition = "varchar(64) not null default ''")
    var hash: String = ""

    /** 对端证据最近一次上报时间（epoch millis）。 */
    @Column(name = "updated_at", nullable = false, columnDefinition = "bigint not null default 0")
    var updatedAt: Long = 0
}
