package com.hippo.anotherviewer.web.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

/**
 * 页修复日志（文件完整性 Wave 3 / S7）：每次单页强制重取（含整本复验连带修复）
 * 落一行，只追加不改。
 *
 * 遥测语义：同一端修复频率抬升 = 介质劣化信号——本表即遥测原始数据，按
 * (gid, repaired_at) 聚合看趋势；attribution 分布区分本地劣化（local_corrupt）
 * 与源变更（source_changed）。
 *
 * 非同步域（不参与 App 同步）：不 stamp username、无墓碑（A7 不适用）。
 * SQLite DDL 惯例：NOT NULL 列必须带 columnDefinition default（ddl-auto update
 * 加列限制）；时间戳一律 Long epoch millis。
 */
@Entity
@Table(
    name = "repair_log",
    indexes = [
        Index(name = "idx_repair_log_gallery", columnList = "gid, repaired_at"),
    ],
)
class RepairLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(nullable = false, columnDefinition = "integer not null default 0")
    var gid: Long = 0

    /** 0-based 页号（与 page_file_hash.page 同口径）。 */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    var page: Int = 0

    /** local_corrupt | source_changed；修复失败或无基线可归因时为 null。 */
    @Column(length = 16)
    var attribution: String? = null

    /** 修复前基线哈希（SHA-256 hex）；无基线为 null。 */
    @Column(name = "old_hash", length = 64)
    var oldHash: String? = null

    /** 修复覆写后的新哈希（SHA-256 hex）；修复失败（未覆写）为 null。 */
    @Column(name = "new_hash", length = 64)
    var newHash: String? = null

    /** 修复时间（epoch millis）。 */
    @Column(name = "repaired_at", nullable = false, columnDefinition = "bigint not null default 0")
    var repairedAt: Long = 0

    /** 修复来源：refresh（单页强制重取）| reverify（整本复验连带修复）。 */
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) not null default 'refresh'")
    var source: String = "refresh"
}
