package com.hippo.anotherviewer.web.processing

import com.hippo.anotherviewer.web.entity.ProcessingTaskEntity
import com.hippo.anotherviewer.web.repository.ProcessingTaskRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * [ProcessingTaskStore] 的 SQLite（processing_task 表）落地：record ↔ entity
 * 双向映射 + 写穿/活跃/历史/页级去重/启动对账（C2 持久化域，手册 §3/§5.4）。
 *
 * 映射约定：
 * - trigger/processingType/state 存 `name()`；读回时未知原文宽容解析、WARN 后
 *   回落记录默认值（手工改库或旧版本残留不致炸历史端点）。
 * - epTaskIds: List<String> ↔ 逗号分隔 CSV；donePages: Set<Int> ↔ CSV（升序写出）。
 * - error_message 落库前截断 2048 字符（D12）；历史分页 size 钳 1..500（D13）。
 */
@Component
class JpaProcessingTaskStore(private val repo: ProcessingTaskRepository) : ProcessingTaskStore {

    companion object {
        private val log = LoggerFactory.getLogger(JpaProcessingTaskStore::class.java)

        /** D13：历史分页 size 上限 500。 */
        private const val MAX_HISTORY_SIZE = 500

        /** D12：error_message 截断 2048 字符。 */
        private const val ERROR_MESSAGE_MAX = 2048

        private const val CSV = ","
    }

    @Transactional
    override fun upsert(record: ProcessingTaskRecord) {
        val existing = repo.findByTaskId(record.taskId)
        val entity = (existing ?: ProcessingTaskEntity().apply { taskId = record.taskId })
            .apply { applyRecord(record) }
        repo.save(entity)
    }

    override fun findByTaskId(taskId: String): ProcessingTaskRecord? =
        repo.findByTaskId(taskId)?.toRecord()

    override fun findActive(): List<ProcessingTaskRecord> =
        repo.findByStateInOrderByCreatedAtDesc(listOf(TaskState.PENDING.name, TaskState.PROCESSING.name))
            .map { it.toRecord() }

    override fun findHistory(page: Int, size: Int, state: TaskState?): ProcessingHistoryPage {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, MAX_HISTORY_SIZE)
        val pageable = PageRequest.of(safePage, safeSize)
        val result = if (state == null) {
            repo.findAllByOrderByCreatedAtDesc(pageable)
        } else {
            repo.findByStateInOrderByCreatedAtDesc(listOf(state.name), pageable)
        }
        return ProcessingHistoryPage(
            items = result.content.map { it.toRecord() },
            total = result.totalElements,
            page = safePage,
            size = safeSize,
        )
    }

    override fun findCompletedPages(galleryId: Long, type: ProcessingType): Set<Int> =
        repo.findDonePageCsv(galleryId, type.name)
            .flatMap { it.csvToIntSet() }
            .toSet()

    @Transactional
    override fun markInterruptedAtStartup(): Int = repo.markInterrupted(System.currentTimeMillis())

    // --- mapping ---

    /** record → entity 全量覆盖（新行/旧行共用；taskId 由调用方先落好）。 */
    private fun ProcessingTaskEntity.applyRecord(r: ProcessingTaskRecord) {
        galleryId = r.galleryId
        title = r.title
        trigger = r.trigger.name
        processingType = r.processingType.name
        state = r.state.name
        pagesTotal = r.pagesTotal
        pagesDone = r.pagesDone
        pagesFailed = r.pagesFailed
        sourceDir = r.sourceDir
        outputDir = r.outputDir
        epTaskIds = r.epTaskIds.joinToString(CSV)
        donePages = r.donePages.sorted().joinToString(CSV)
        errorCode = r.errorCode
        errorMessage = r.errorMessage.take(ERROR_MESSAGE_MAX)
        createdAt = r.createdAt
        startedAt = r.startedAt
        finishedAt = r.finishedAt
        updatedAt = r.updatedAt
    }

    private fun ProcessingTaskEntity.toRecord(): ProcessingTaskRecord = ProcessingTaskRecord(
        taskId = taskId,
        galleryId = galleryId,
        title = title,
        trigger = trigger.toEnumOr(ProcessingTrigger.MANUAL),
        processingType = processingType.toEnumOr(ProcessingType.UPSCALE_2X),
        state = state.toEnumOr(TaskState.PENDING),
        pagesTotal = pagesTotal,
        pagesDone = pagesDone,
        pagesFailed = pagesFailed,
        sourceDir = sourceDir,
        outputDir = outputDir,
        epTaskIds = epTaskIds.csvToStringList(),
        errorCode = errorCode,
        errorMessage = errorMessage,
        createdAt = createdAt,
        startedAt = startedAt,
        finishedAt = finishedAt,
        updatedAt = updatedAt,
        donePages = donePages.csvToIntSet(),
    )

    /** 未知枚举原文宽容解析：WARN 后回落 [default]（记录字段的默认值）。 */
    private inline fun <reified T : Enum<T>> String.toEnumOr(default: T): T {
        val name = trim()
        val match = enumValues<T>().firstOrNull { it.name == name }
        if (match != null) return match
        log.warn("processing_task 行内未知枚举原文 [{}]，回落默认 {}", this, default.name)
        return default
    }

    /** CSV → List<String>（空段剔除；首尾空白容错）。 */
    private fun String.csvToStringList(): List<String> =
        split(CSV).map { it.trim() }.filter { it.isNotEmpty() }

    /** CSV → Set<Int>（空串/非数字段跳过——脏数据容错，不抛异常）。 */
    private fun String.csvToIntSet(): Set<Int> =
        split(CSV).mapNotNull { it.trim().toIntOrNull() }.toSet()
}
