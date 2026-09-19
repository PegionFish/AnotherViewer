package com.hippo.anotherviewer.web.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

/** page_file_hash 复合主键 (gid, page)。 */
data class PageFileHashId(
    var gid: Long = 0,
    var page: Int = 0,
) : Serializable

/**
 * 页文件完整性基线（文件完整性 Wave 1）：下载目录里每页文件的 SHA-256 基线，
 * 供巡检（比对当前文件）、交叉审计（vs peer_hash，零网络）与修复归因使用。
 *
 * PK(gid, page)：gid 前缀查询（findByGid/deleteByGid）直接吃主键自动索引，
 * 不再另建 gid 单列索引。origin 记录本行基线的写入来源；
 * verdict/last_verified_at 由巡检回写，null = 尚未巡检。
 *
 * SQLite DDL 惯例：NOT NULL 列必须带 columnDefinition default（ddl-auto update 加列限制）；
 * 时间戳一律 Long epoch millis。
 */
@Entity
@Table(name = "page_file_hash")
@IdClass(PageFileHashId::class)
class PageFileHashEntity {
    @Id
    @Column(nullable = false)
    var gid: Long = 0

    /** 0-based 页号（与 download_info / processing_task 页号同一口径）。 */
    @Id
    @Column(nullable = false)
    var page: Int = 0

    /** 文件扩展名（不带点，如 jpg），修复/重取时拼 URL 用。 */
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) not null default ''")
    var ext: String = ""

    /** 页文件字节数（建基线时快照）。 */
    @Column(nullable = false, columnDefinition = "bigint not null default 0")
    var size: Long = 0

    /** SHA-256 hex（64 字符）。 */
    @Column(nullable = false, length = 64, columnDefinition = "varchar(64) not null default ''")
    var hash: String = ""

    /** 哈希算法名（现役 sha256，留位给将来换算法）。 */
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) not null default 'sha256'")
    var algo: String = "sha256"

    /** 基线写入来源：downloader|read_sync|heal|import。 */
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) not null default 'downloader'")
    var origin: String = "downloader"

    /** 基线建立时间（epoch millis）。 */
    @Column(name = "created_at", nullable = false, columnDefinition = "bigint not null default 0")
    var createdAt: Long = 0

    /** 上次巡检通过/判定时间（epoch millis）；null = 尚未巡检。 */
    @Column(name = "last_verified_at")
    var lastVerifiedAt: Long? = null

    /** 巡检结论：null（未巡检）| ok | struct_bad。 */
    @Column(length = 16)
    var verdict: String? = null
}
