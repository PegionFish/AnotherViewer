package com.hippo.anotherviewer.web.entity

import com.hippo.anotherviewer.web.processing.ProcessingTrigger
import com.hippo.anotherviewer.web.processing.ProcessingType
import com.hippo.anotherviewer.web.processing.TaskState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

/**
 * 图像处理任务历史（2026-09-08 EntryPoint 集成）：内存任务表（ImageProcessingService）
 * 的写穿落盘——重启不丢，历史查询/页级去重/补跑判定都走这张表。
 *
 * 非同步域（不参与 App 同步）：不 stamp username、无墓碑（A7 不适用）。
 * SQLite DDL 惯例：NOT NULL 列必须带 columnDefinition default（ddl-auto update 加列限制）；
 * 时间戳一律 Long epoch millis。
 */
@Entity
@Table(
    name = "processing_task",
    indexes = [
        Index(name = "idx_processing_task_state", columnList = "state, created_at"),
        Index(name = "idx_processing_task_gallery", columnList = "gallery_id, processing_type"),
    ],
)
class ProcessingTaskEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    /** 任务号（`proc-xxxxxxxx`，与内存任务表同键）。 */
    @Column(name = "task_id", nullable = false, unique = true, length = 64)
    var taskId: String = ""

    @Column(name = "gallery_id", nullable = false, columnDefinition = "integer not null default 0")
    var galleryId: Long = 0

    /** 提交时快照（列表展示免联查；未知留空）。 */
    @Column(nullable = false, columnDefinition = "varchar(256) not null default ''")
    var title: String = ""

    /** MANUAL / DOWNLOAD_AUTO / SCHEDULED。 */
    @Column(nullable = false, columnDefinition = "varchar(16) not null default 'MANUAL'")
    var trigger: String = ProcessingTrigger.MANUAL.name

    @Column(name = "processing_type", nullable = false, columnDefinition = "varchar(24) not null default ''")
    var processingType: String = ProcessingType.UPSCALE_2X.name

    /** TaskState.name()：PENDING/PROCESSING/DONE/FAILED（取消=FAILED，error 记录原因）。 */
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) not null default 'PENDING'")
    var state: String = TaskState.PENDING.name

    @Column(name = "pages_total", nullable = false, columnDefinition = "integer not null default 0")
    var pagesTotal: Int = 0

    @Column(name = "pages_done", nullable = false, columnDefinition = "integer not null default 0")
    var pagesDone: Int = 0

    @Column(name = "pages_failed", nullable = false, columnDefinition = "integer not null default 0")
    var pagesFailed: Int = 0

    /** 输入页所在目录（下载目录或缓存目录，绝对路径，展示用）。 */
    @Column(name = "source_dir", nullable = false, columnDefinition = "varchar(512) not null default ''")
    var sourceDir: String = ""

    /** enhanced 产物目录（`{cache}/enhanced/{gid}`，绝对路径，展示用）。 */
    @Column(name = "output_dir", nullable = false, columnDefinition = "varchar(512) not null default ''")
    var outputDir: String = ""

    /** EntryPoint 侧 task_id（逗号分隔，调试用；不解析）。 */
    @Column(name = "ep_task_ids", nullable = false, columnDefinition = "TEXT")
    var epTaskIds: String = ""

    /** 本任务内已成功完成的 0-based 页号（逗号分隔，页级去重 D8 的数据源）。 */
    @Column(name = "done_pages", nullable = false, columnDefinition = "TEXT")
    var donePages: String = ""

    /** 机读码：EntryPoint code 或 EP_UNREACHABLE/EP_TIMEOUT/EP_INTERRUPTED/EP_CANCELLED。 */
    @Column(name = "error_code", nullable = false, length = 64, columnDefinition = "varchar(64) not null default ''")
    var errorCode: String = ""

    /** 人类可读文案（截断 2048；不机判）。 */
    @Column(name = "error_message", nullable = false, columnDefinition = "varchar(2048) not null default ''")
    var errorMessage: String = ""

    @Column(name = "created_at", nullable = false, columnDefinition = "bigint not null default 0")
    var createdAt: Long = 0

    @Column(name = "started_at", nullable = false, columnDefinition = "bigint not null default 0")
    var startedAt: Long = 0

    @Column(name = "finished_at", nullable = false, columnDefinition = "bigint not null default 0")
    var finishedAt: Long = 0

    @Column(name = "updated_at", nullable = false, columnDefinition = "bigint not null default 0")
    var updatedAt: Long = 0
}
