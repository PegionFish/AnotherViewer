package com.hippo.anotherviewer.web.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 一次完整性巡检（scrub）的运行记录（文件完整性 Wave 3 / S6）。
 *
 * 每次（定时或手动）巡检落一行：开始建行（status=running），结束时回填计数与
 * 两份 JSON 明细并落 status。GET /api/v1/integrity/report 的 lastRun 读最近一行
 * （running 行也可见，finishedAt=null 即进行中）；
 *
 * - 坏页清单的**当前态**以 page_file_hash.verdict='struct_bad' 行为准（巡检只标
 *   记不修复，修复由 refresh 端点/heal 刷新基线后自然出清单）——run 行里的
 *   bad_pages_json 是巡检当刻的判定快照（带 MISSING/MISMATCH/READ_ERROR 细分），
 *   report 用它给 struct_bad 行补充判定原因；
 * - 交叉审计（基线 vs peer_hash）结果只在巡检时产出，落 divergences_json 使其
 *   在 scrub_run 之间可查（peer 证据随时可能被重新上送覆盖，审计结果是时点快照）。
 *
 * 明细 JSON 与计数的对应：badPages/divergences 计数 = 巡检发现的总量；JSON 数组
 * 同长（个人库规模，无上限截断）。
 *
 * 非同步域（不参与 App 同步）：不 stamp username、无墓碑（A7 不适用）。
 * SQLite DDL 惯例：NOT NULL 列必须带 columnDefinition default（ddl-auto update
 * 加列限制）；时间戳一律 Long epoch millis。
 */
@Entity
@Table(name = "scrub_run")
class ScrubRunEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    /** 巡检开始时间（epoch millis）。 */
    @Column(name = "started_at", nullable = false, columnDefinition = "bigint not null default 0")
    var startedAt: Long = 0

    /** 巡检结束时间（epoch millis）；null = 仍在进行。 */
    @Column(name = "finished_at")
    var finishedAt: Long? = null

    /** running | completed | interrupted。 */
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) not null default 'running'")
    var status: String = STATUS_RUNNING

    /** 本轮实际检查的页文件数（断点跳过与无基线跳过的不计）。 */
    @Column(name = "total_pages", nullable = false, columnDefinition = "integer not null default 0")
    var totalPages: Int = 0

    /** 哈希与基线一致的页数。 */
    @Column(name = "ok_pages", nullable = false, columnDefinition = "integer not null default 0")
    var okPages: Int = 0

    /** 坏页数（文件缺失 / 读不出 / 哈希不符；复读确认后的最终判定）。 */
    @Column(name = "bad_pages", nullable = false, columnDefinition = "integer not null default 0")
    var badPages: Int = 0

    /** 交叉审计：基线与 peer 哈希同为存在但不一致（版本分歧）的页数。 */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    var divergences: Int = 0

    /** 交叉审计：仅单侧有（只有基线或只有 peer 证据）的页数。 */
    @Column(name = "one_sided", nullable = false, columnDefinition = "integer not null default 0")
    var oneSided: Int = 0

    /** 坏页判定快照 JSON：[{gid,page,verdict,size,hashShort}]（page 为 1-based）。 */
    @Column(name = "bad_pages_json", columnDefinition = "text")
    var badPagesJson: String? = null

    /** 交叉审计分歧快照 JSON：[{gid,page,localHash,peerHash}]（page 为 1-based）。 */
    @Column(name = "divergences_json", columnDefinition = "text")
    var divergencesJson: String? = null

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_INTERRUPTED = "interrupted"
    }
}
