package com.hippo.anotherviewer.web.api

import com.hippo.anotherviewer.web.dto.ProcessingHistoryResponse
import com.hippo.anotherviewer.web.dto.ProcessingRequest
import com.hippo.anotherviewer.web.dto.ProcessingStatus
import com.hippo.anotherviewer.web.dto.ProcessingTaskListResponse
import com.hippo.anotherviewer.web.dto.ProcessingTaskRecordDto
import com.hippo.anotherviewer.web.dto.ProcessingTaskResponse
import com.hippo.anotherviewer.web.processing.ImageProcessingService
import com.hippo.anotherviewer.web.processing.ProcessingOptions
import com.hippo.anotherviewer.web.processing.ProcessingTaskStore
import com.hippo.anotherviewer.web.processing.TaskState
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * REST controller for the image processing pipeline.
 *
 * Endpoints:
 * - POST /api/v1/process/gallery/{id} — trigger enhancement for all pages
 * - GET /api/v1/process/status/{taskId} — query progress
 * - POST /api/v1/process/cancel/{taskId} — request cancellation
 * - GET /api/v1/process/tasks?active=1 — active in-memory tasks (or recent
 *   history when the parameter is absent)
 * - GET /api/v1/process/history — paged task history with optional state filter
 * - POST /api/v1/process/retry/{taskId} — re-submit a failed task's failed pages
 *
 * See: contracts/openapi.yaml Processing tag, contracts/websocket-protocol.md §3.2,
 * docs/dev-plan-2026-09-08-image-processing-execution-handoff.md §4
 * Error paths use the uniform error envelope (M-6).
 */
@RestController
@RequestMapping("/api/v1/process")
class ProcessingController(
    private val processingService: ImageProcessingService,
    private val taskStore: ProcessingTaskStore
) {

    @PostMapping("/gallery/{id}")
    fun triggerGalleryProcessing(
        @PathVariable id: Long,
        @Valid @RequestBody(required = false) request: ProcessingRequest?
    ): ResponseEntity<*> {
        val req = request ?: ProcessingRequest()
        val options = ProcessingOptions(
            type = req.type,
            outputFormat = req.outputFormat,
            quality = req.quality
        )

        return try {
            // Resolve the real page count from Gallery Site gallery metadata so the
            // whole gallery is processed, not a placeholder single page.
            val count = processingService.resolvePageCount(id)
                ?: return errorEnvelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "Gallery not found")
            if (count <= 0) {
                return errorEnvelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "Gallery not found")
            }
            val pages = 0 until count
            val taskId = processingService.submitGallery(id, pages, options)
            val status = processingService.getTaskStatus(taskId)!!

            ResponseEntity.ok(ProcessingTaskResponse(
                taskId = status.taskId,
                galleryId = status.galleryId,
                totalPages = status.totalPages,
                state = status.state
            ))
        } catch (e: IllegalStateException) {
            // 区分「无可用处理器」与真正的冲突：前者是服务端能力缺失（503），
            // 后者才报 409；错误文案取自服务层，不再硬编码成 "already in progress"。
            val noProcessor = e.message?.contains("No available processor") == true
            val status = if (noProcessor) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.CONFLICT
            errorEnvelope(status, if (noProcessor) "PROCESSOR_UNAVAILABLE" else "CONFLICT",
                e.message ?: "Processing request rejected")
        }
    }

    @GetMapping("/status/{taskId}")
    fun getProcessingStatus(@PathVariable taskId: String): ResponseEntity<*> {
        val status = processingService.getTaskStatus(taskId)
            ?: return errorEnvelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "Task not found")

        return ResponseEntity.ok(ProcessingStatus(
            taskId = status.taskId,
            galleryId = status.galleryId,
            state = status.state,
            totalPages = status.totalPages,
            processedPages = status.processedPages,
            failedPages = status.failedPages,
            currentPage = status.currentPage,
            startedAt = status.startedAt,
            completedAt = status.completedAt,
            error = status.error
        ))
    }

    @PostMapping("/cancel/{taskId}")
    fun cancelProcessing(@PathVariable taskId: String): ResponseEntity<*> {
        val cancelled = processingService.cancelTask(taskId)
        if (!cancelled) {
            return errorEnvelope(HttpStatus.NOT_FOUND, "NOT_FOUND", "Task not found")
        }
        return ResponseEntity.ok(mapOf("success" to true))
    }

    /**
     * Task list for the processing monitor page.
     *
     * `active=1` → in-memory active tasks only (transient progress included);
     * without the parameter → the most recent 200 history records from the
     * store, newest first (mixed terminal + active). Both render the same
     * [ProcessingTaskRecordDto] shape (C5, handoff §4).
     */
    @GetMapping("/tasks")
    fun getTasks(
        @RequestParam(name = "active", required = false) active: String?
    ): ResponseEntity<*> {
        val records = if (active == "1") {
            processingService.getActiveRecords()
        } else {
            taskStore.findHistory(0, RECENT_HISTORY_LIMIT).items
        }
        return ResponseEntity.ok(
            ProcessingTaskListResponse(tasks = records.map { ProcessingTaskRecordDto.from(it) })
        )
    }

    /**
     * Paged task history, newest first, with optional state filter. size is
     * clamped to 1..500 (D13); an unknown state is a PARAM_INVALID envelope.
     */
    @GetMapping("/history")
    fun getHistory(
        @RequestParam(name = "page", required = false, defaultValue = "0") page: Int,
        @RequestParam(name = "size", required = false, defaultValue = "50") size: Int,
        @RequestParam(name = "state", required = false) state: String?
    ): ResponseEntity<*> {
        val stateFilter = if (state.isNullOrBlank()) {
            null
        } else {
            TaskState.entries.firstOrNull { it.name == state }
                ?: return errorEnvelope(
                    HttpStatus.BAD_REQUEST, "PARAM_INVALID",
                    "state must be one of ${TaskState.entries.joinToString("/")}"
                )
        }
        val result = taskStore.findHistory(page.coerceAtLeast(0), size.coerceIn(1, MAX_HISTORY_SIZE), stateFilter)
        return ResponseEntity.ok(
            ProcessingHistoryResponse(
                items = result.items.map { ProcessingTaskRecordDto.from(it) },
                total = result.total,
                page = result.page,
                size = result.size
            )
        )
    }

    /**
     * Retry a failed task: re-submits its original parameters for the failed
     * pages only (page-level dedup exemption) and returns the new task.
     */
    @PostMapping("/retry/{taskId}")
    fun retryProcessing(@PathVariable taskId: String): ResponseEntity<*> {
        return try {
            val newTaskId = processingService.retryTask(taskId)
            val status = processingService.getTaskStatus(newTaskId)!!
            ResponseEntity.ok(ProcessingTaskResponse(
                taskId = status.taskId,
                galleryId = status.galleryId,
                totalPages = status.totalPages,
                state = status.state
            ))
        } catch (e: IllegalArgumentException) {
            errorEnvelope(HttpStatus.NOT_FOUND, "NOT_FOUND", e.message ?: "Task not found")
        } catch (e: IllegalStateException) {
            errorEnvelope(HttpStatus.CONFLICT, "CONFLICT", e.message ?: "Task cannot be retried")
        }
    }

    companion object {
        /** Recent-mixed list size for GET /tasks without `active=1` (handoff §4). */
        private const val RECENT_HISTORY_LIMIT = 200

        /** History page size upper bound (D13). */
        private const val MAX_HISTORY_SIZE = 500
    }
}
