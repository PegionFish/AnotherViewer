package com.hippo.anotherviewer.web.dto

import com.hippo.anotherviewer.web.processing.ProcessingTaskRecord
import com.hippo.anotherviewer.web.processing.ProcessingTrigger
import com.hippo.anotherviewer.web.processing.ProcessingType
import com.hippo.anotherviewer.web.processing.TaskState
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Request body for POST /api/v1/process/gallery/{id}
 * See: contracts/openapi.yaml ProcessingRequest schema
 */
data class ProcessingRequest(
    val type: ProcessingType = ProcessingType.UPSCALE_2X,
    @field:Size(max = 16, message = "outputFormat must be at most 16 characters")
    @field:Pattern(regexp = "^(?i)(png|jpe?g|webp)$", message = "outputFormat must be png, jpg, jpeg or webp")
    val outputFormat: String = "png",
    @field:Min(1, message = "quality must be between 1 and 100")
    @field:Max(100, message = "quality must be between 1 and 100")
    val quality: Int = 90
)

/**
 * Response for POST /api/v1/process/gallery/{id}
 * See: contracts/openapi.yaml ProcessingTaskResponse schema
 */
data class ProcessingTaskResponse(
    val taskId: String,
    val galleryId: Long,
    val totalPages: Int,
    val state: TaskState
)

/**
 * Response for GET /api/v1/process/status/{taskId}
 * See: contracts/openapi.yaml ProcessingStatus schema
 */
data class ProcessingStatus(
    val taskId: String,
    val galleryId: Long,
    val state: TaskState,
    val totalPages: Int,
    val processedPages: Int,
    val failedPages: Int,
    val currentPage: Int,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val error: String?
)

/**
 * JSON mirror of a [ProcessingTaskRecord] (processing_task row; active tasks
 * carry the same shape with in-memory transient progress). Field-for-field
 * identical to web-frontend `ProcessingTaskRecord` — timestamps are epoch
 * millis (not Instant) to mirror the DB row.
 * See: contracts/openapi.yaml ProcessingTaskRecordDto schema,
 * docs/dev-plan-2026-09-08-image-processing-execution-handoff.md §3/§4
 */
data class ProcessingTaskRecordDto(
    val taskId: String,
    val galleryId: Long,
    val title: String,
    val trigger: ProcessingTrigger,
    val processingType: ProcessingType,
    val state: TaskState,
    val pagesTotal: Int,
    val pagesDone: Int,
    val pagesFailed: Int,
    val sourceDir: String,
    val outputDir: String,
    val epTaskIds: List<String>,
    val errorCode: String,
    val errorMessage: String,
    val createdAt: Long,
    val startedAt: Long,
    val finishedAt: Long,
    val updatedAt: Long
) {
    companion object {
        /** Maps the store/service record; `donePages` is internal (page dedup) and not exposed. */
        fun from(record: ProcessingTaskRecord): ProcessingTaskRecordDto = ProcessingTaskRecordDto(
            taskId = record.taskId,
            galleryId = record.galleryId,
            title = record.title,
            trigger = record.trigger,
            processingType = record.processingType,
            state = record.state,
            pagesTotal = record.pagesTotal,
            pagesDone = record.pagesDone,
            pagesFailed = record.pagesFailed,
            sourceDir = record.sourceDir,
            outputDir = record.outputDir,
            epTaskIds = record.epTaskIds,
            errorCode = record.errorCode,
            errorMessage = record.errorMessage,
            createdAt = record.createdAt,
            startedAt = record.startedAt,
            finishedAt = record.finishedAt,
            updatedAt = record.updatedAt
        )
    }
}

/**
 * Response for GET /api/v1/process/tasks?active=1 (and the no-param recent
 * variant) — `{"tasks":[...]}`.
 */
data class ProcessingTaskListResponse(
    val tasks: List<ProcessingTaskRecordDto>
)

/**
 * Response for GET /api/v1/process/history — `{"items":[...],"total":n,"page":p,"size":s}`.
 */
data class ProcessingHistoryResponse(
    val items: List<ProcessingTaskRecordDto>,
    val total: Long,
    val page: Int,
    val size: Int
)
