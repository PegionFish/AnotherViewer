package com.hippo.anotherviewer.web.processing

/**
 * 处理任务历史的持久化门面：内存任务表（ImageProcessingService）在每次状态
 * 迁移时写穿到这里；历史查询/页级去重/启动对账走这里。
 *
 * 实现落 SQLite（processing_task 表）；写穿失败只 WARN 不阻断处理（D10——
 * DB 落后由历史端点诚实显示，不得拖垮进行中的处理）。
 */

/** 触发来源。 */
enum class ProcessingTrigger {
    /** 用户在处理页/阅读器手动提交。 */
    MANUAL,

    /** 下载完成自动触发（DownloadProgress state=3 监听）。 */
    DOWNLOAD_AUTO,

    /** 定期补跑扫描（@Scheduled 心跳）。 */
    SCHEDULED,
}

/**
 * 任务历史记录（内存与 DB 的共同形状）。
 * state 复用 [TaskState]（取消=FAILED + errorCode=EP_CANCELLED，与现有
 * cancelTask 的 "Task cancelled" 语义一致）。
 */
data class ProcessingTaskRecord(
    val taskId: String,
    val galleryId: Long,
    val title: String = "",
    val trigger: ProcessingTrigger = ProcessingTrigger.MANUAL,
    val processingType: ProcessingType = ProcessingType.UPSCALE_2X,
    val state: TaskState = TaskState.PENDING,
    val pagesTotal: Int = 0,
    val pagesDone: Int = 0,
    val pagesFailed: Int = 0,
    val sourceDir: String = "",
    val outputDir: String = "",
    val epTaskIds: List<String> = emptyList(),
    val errorCode: String = "",
    val errorMessage: String = "",
    val createdAt: Long = 0,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val updatedAt: Long = 0,
)

/** 分页历史页（GET /process/history 的服务层形状）。 */
data class ProcessingHistoryPage(
    val items: List<ProcessingTaskRecord>,
    val total: Long,
    val page: Int,
    val size: Int,
)

interface ProcessingTaskStore {
    /** 插入或按 taskId 全量覆盖（状态迁移的写穿入口）。 */
    fun upsert(record: ProcessingTaskRecord)

    fun findByTaskId(taskId: String): ProcessingTaskRecord?

    /** 活跃任务（PENDING/PROCESSING），新→旧。 */
    fun findActive(): List<ProcessingTaskRecord>

    /** 历史分页（新→旧）；state 过滤可选。 */
    fun findHistory(page: Int, size: Int, state: TaskState? = null): ProcessingHistoryPage

    /** 页级去重（D8）：某画廊某类型下已 DONE 的 0-based 页集合。 */
    fun findCompletedPages(galleryId: Long, type: ProcessingType): Set<Int>

    /** 启动对账：遗留 PENDING/PROCESSING → FAILED/EP_INTERRUPTED，返回改写行数。 */
    fun markInterruptedAtStartup(): Int
}
