package com.hippo.anotherviewer.web.processing

import com.hippo.anotherviewer.web.any
import com.hippo.anotherviewer.web.api.ProcessingController
import com.hippo.anotherviewer.web.config.GlobalExceptionHandler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * Standalone MockMvc tests for [ProcessingController] (M-6 error envelope via
 * [GlobalExceptionHandler]).
 *
 * C5 endpoints (`/tasks`, `/history`, `/retry/{taskId}`) are written against
 * the C4 contract pinned in the execution handoff §4/C5:
 * - ImageProcessingService.getActiveRecords(): List<ProcessingTaskRecord>
 * - ImageProcessingService.retryTask(taskId): String
 *   (IllegalArgumentException→404, IllegalStateException→409)
 * - ProcessingTaskStore.findHistory(page, size, state?): ProcessingHistoryPage
 */
class ProcessingControllerTest {

    private lateinit var processingService: ImageProcessingService
    private lateinit var taskStore: ProcessingTaskStore
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        processingService = mock(ImageProcessingService::class.java)
        taskStore = mock(ProcessingTaskStore::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(ProcessingController(processingService, taskStore))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    // --- fixtures -----------------------------------------------------------

    /** Fully populated record so every DTO field can be asserted field-by-field. */
    private fun record(
        taskId: String = "task-1",
        galleryId: Long = 123L,
        state: TaskState = TaskState.PROCESSING
    ) = ProcessingTaskRecord(
        taskId = taskId,
        galleryId = galleryId,
        title = "Some Gallery",
        trigger = ProcessingTrigger.DOWNLOAD_AUTO,
        processingType = ProcessingType.REMOVE_BG,
        state = state,
        pagesTotal = 10,
        pagesDone = 4,
        pagesFailed = 1,
        sourceDir = "/data/cache/123",
        outputDir = "/data/cache/enhanced/123",
        epTaskIds = listOf("ep-1", "ep-2"),
        errorCode = "EP_TIMEOUT",
        errorMessage = "EntryPoint timed out",
        createdAt = 1_700_000_000_000,
        startedAt = 1_700_000_005_000,
        finishedAt = 0,
        updatedAt = 1_700_000_010_000
    )

    // --- POST /gallery/{id} (existing behaviour) ----------------------------

    @Test
    fun `trigger accepts a valid payload and returns the task`() {
        `when`(processingService.resolvePageCount(123L)).thenReturn(5)
        `when`(processingService.submitGallery(anyLong(), any(), any(), any(), anyBoolean())).thenReturn("task-1")
        val status = ProcessingTaskStatus(
            taskId = "task-1", galleryId = 123L, state = TaskState.PROCESSING,
            totalPages = 5, processedPages = 0, failedPages = 0, currentPage = 0,
            startedAt = null, completedAt = null, error = null
        )
        `when`(processingService.getTaskStatus("task-1")).thenReturn(status)

        mockMvc.perform(
            post("/api/v1/process/gallery/123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"UPSCALE_2X","outputFormat":"png","quality":90}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.taskId").value("task-1"))
    }

    @Test
    fun `trigger rejects quality out of range with 400 envelope`() {
        mockMvc.perform(
            post("/api/v1/process/gallery/123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"UPSCALE_2X","outputFormat":"png","quality":0}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.status").value(400))
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.error.message").value("quality must be between 1 and 100"))
            .andExpect(jsonPath("$.error.traceId").exists())
        verify(processingService, never()).resolvePageCount(anyLong())
    }

    @Test
    fun `trigger returns 404 envelope when the gallery page count cannot be resolved`() {
        `when`(processingService.resolvePageCount(404L)).thenReturn(null)

        mockMvc.perform(
            post("/api/v1/process/gallery/404")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{}""")
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.status").value(404))
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.error.traceId").exists())
    }

    @Test
    fun `trigger returns 409 envelope when a task is already running`() {
        `when`(processingService.resolvePageCount(123L)).thenReturn(5)
        `when`(processingService.submitGallery(anyLong(), any(), any(), any(), anyBoolean()))
            .thenThrow(IllegalStateException("already running"))

        mockMvc.perform(
            post("/api/v1/process/gallery/123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.status").value(409))
            .andExpect(jsonPath("$.error.code").value("CONFLICT"))
            .andExpect(jsonPath("$.error.traceId").exists())
    }

    @Test
    fun `status returns 404 envelope for an unknown task`() {
        `when`(processingService.getTaskStatus("nope")).thenReturn(null)

        mockMvc.perform(get("/api/v1/process/status/nope"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.error.traceId").exists())
    }

    // --- GET /tasks ---------------------------------------------------------

    @Test
    fun `active tasks are mapped field by field`() {
        `when`(processingService.getActiveRecords()).thenReturn(listOf(record()))

        mockMvc.perform(get("/api/v1/process/tasks").param("active", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tasks[0].taskId").value("task-1"))
            .andExpect(jsonPath("$.tasks[0].galleryId").value(123))
            .andExpect(jsonPath("$.tasks[0].title").value("Some Gallery"))
            .andExpect(jsonPath("$.tasks[0].trigger").value("DOWNLOAD_AUTO"))
            .andExpect(jsonPath("$.tasks[0].processingType").value("REMOVE_BG"))
            .andExpect(jsonPath("$.tasks[0].state").value("PROCESSING"))
            .andExpect(jsonPath("$.tasks[0].pagesTotal").value(10))
            .andExpect(jsonPath("$.tasks[0].pagesDone").value(4))
            .andExpect(jsonPath("$.tasks[0].pagesFailed").value(1))
            .andExpect(jsonPath("$.tasks[0].sourceDir").value("/data/cache/123"))
            .andExpect(jsonPath("$.tasks[0].outputDir").value("/data/cache/enhanced/123"))
            .andExpect(jsonPath("$.tasks[0].epTaskIds.length()").value(2))
            .andExpect(jsonPath("$.tasks[0].epTaskIds[0]").value("ep-1"))
            .andExpect(jsonPath("$.tasks[0].epTaskIds[1]").value("ep-2"))
            .andExpect(jsonPath("$.tasks[0].errorCode").value("EP_TIMEOUT"))
            .andExpect(jsonPath("$.tasks[0].errorMessage").value("EntryPoint timed out"))
            .andExpect(jsonPath("$.tasks[0].createdAt").value(1_700_000_000_000))
            .andExpect(jsonPath("$.tasks[0].startedAt").value(1_700_000_005_000))
            .andExpect(jsonPath("$.tasks[0].finishedAt").value(0))
            .andExpect(jsonPath("$.tasks[0].updatedAt").value(1_700_000_010_000))
            // donePages is internal page-dedup bookkeeping and must not leak.
            .andExpect(jsonPath("$.tasks[0].donePages").doesNotExist())
    }

    @Test
    fun `active tasks returns an empty list when nothing is running`() {
        `when`(processingService.getActiveRecords()).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/process/tasks").param("active", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tasks").isArray())
            .andExpect(jsonPath("$.tasks").isEmpty())
    }

    @Test
    fun `tasks without the active parameter fall back to recent history`() {
        `when`(taskStore.findHistory(0, 200, null)).thenReturn(
            ProcessingHistoryPage(items = listOf(record(taskId = "task-h")), total = 42, page = 0, size = 200)
        )

        mockMvc.perform(get("/api/v1/process/tasks"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tasks[0].taskId").value("task-h"))
        verify(taskStore).findHistory(0, 200, null)
        verify(processingService, never()).getActiveRecords()
    }

    // --- GET /history -------------------------------------------------------

    @Test
    fun `history uses default paging when no parameters are given`() {
        `when`(taskStore.findHistory(0, 50, null)).thenReturn(
            ProcessingHistoryPage(items = listOf(record(state = TaskState.DONE)), total = 7, page = 0, size = 50)
        )

        mockMvc.perform(get("/api/v1/process/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].taskId").value("task-1"))
            .andExpect(jsonPath("$.items[0].state").value("DONE"))
            .andExpect(jsonPath("$.total").value(7))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(50))
        verify(taskStore).findHistory(0, 50, null)
    }

    @Test
    fun `history forwards paging and state filter`() {
        `when`(taskStore.findHistory(1, 25, TaskState.FAILED)).thenReturn(
            ProcessingHistoryPage(items = emptyList(), total = 3, page = 1, size = 25)
        )

        mockMvc.perform(
            get("/api/v1/process/history")
                .param("page", "1")
                .param("size", "25")
                .param("state", "FAILED")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(25))
        verify(taskStore).findHistory(1, 25, TaskState.FAILED)
    }

    @Test
    fun `history rejects an unknown state with a 400 PARAM_INVALID envelope`() {
        mockMvc.perform(get("/api/v1/process/history").param("state", "BOGUS"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.status").value(400))
            .andExpect(jsonPath("$.error.code").value("PARAM_INVALID"))
            .andExpect(jsonPath("$.error.traceId").exists())
        verifyNoInteractions(taskStore)
    }

    @Test
    fun `history clamps size above the 500 upper bound before hitting the store`() {
        `when`(taskStore.findHistory(0, 500, null)).thenReturn(
            ProcessingHistoryPage(items = emptyList(), total = 0, page = 0, size = 500)
        )

        mockMvc.perform(get("/api/v1/process/history").param("size", "5000"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.size").value(500))
        verify(taskStore).findHistory(0, 500, null)
    }

    // --- POST /retry/{taskId} ----------------------------------------------

    @Test
    fun `retry returns the new task assembled from its status`() {
        `when`(processingService.retryTask("task-9")).thenReturn("task-10")
        `when`(processingService.getTaskStatus("task-10")).thenReturn(
            ProcessingTaskStatus(
                taskId = "task-10", galleryId = 123L, state = TaskState.PENDING,
                totalPages = 3, processedPages = 0, failedPages = 0, currentPage = -1,
                startedAt = null, completedAt = null, error = null
            )
        )

        mockMvc.perform(post("/api/v1/process/retry/task-9"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.taskId").value("task-10"))
            .andExpect(jsonPath("$.galleryId").value(123))
            .andExpect(jsonPath("$.totalPages").value(3))
            .andExpect(jsonPath("$.state").value("PENDING"))
    }

    @Test
    fun `retry returns a 404 envelope for an unknown task`() {
        `when`(processingService.retryTask("nope")).thenThrow(IllegalArgumentException("Task not found"))

        mockMvc.perform(post("/api/v1/process/retry/nope"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.status").value(404))
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("Task not found"))
            .andExpect(jsonPath("$.error.traceId").exists())
    }

    @Test
    fun `retry returns a 409 envelope when the task is not retryable`() {
        `when`(processingService.retryTask("task-2"))
            .thenThrow(IllegalStateException("Task is not in a retryable state"))

        mockMvc.perform(post("/api/v1/process/retry/task-2"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.status").value(409))
            .andExpect(jsonPath("$.error.code").value("CONFLICT"))
            .andExpect(jsonPath("$.error.message").value("Task is not in a retryable state"))
            .andExpect(jsonPath("$.error.traceId").exists())
    }
}
