package com.hippo.anotherviewer.web.processing

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.processing.ep.EpException
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.GalleryLookupService
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

/**
 * Queue scheduler for image processing tasks.
 *
 * Maintains an in-memory task queue with configurable concurrency.
 * Publishes progress events via Spring [ApplicationEventPublisher]
 * for WebSocket forwarding.
 *
 * W2 (2026-09-08, EntryPoint 集成 C4)：
 * - 内存 map 仍是运行时主拷贝（D10），每次状态迁移写穿到 [ProcessingTaskStore]
 *   （SQLite processing_task 表）；写穿失败只 WARN，不阻断处理。
 * - submitGallery 增加 trigger/force：触发来源落库（MANUAL/DOWNLOAD_AUTO/SCHEDULED）；
 *   D8 页级去重（历史 COMPLETED 页 ∪ enhanced 产物文件已存在），force=true 豁免。
 * - [retryTask]/[getActiveRecords] 供 ProcessingController 的 retry/tasks 端点消费。
 * - 输入解析优先 App 推送下载目录（[DownloadDirIndex.findPage]），回落缓存目录（D18）。
 *
 * See: docs/webui-roadmap.md Phase 1 §1.3, contracts/websocket-protocol.md §3.2,
 * docs/dev-plan-2026-09-08-image-processing-execution-handoff.md §2/§5/§11.
 */
@Service
class ImageProcessingService(
    private val processors: List<ImageProcessor>,
    private val eventPublisher: ApplicationEventPublisher,
    private val galleryLookup: GalleryLookupService? = null,
    /** 任务历史写穿门面（D10）；null = 不落库（旧测试路径），行为不变。 */
    private val store: ProcessingTaskStore? = null,
    /** App 推送下载目录索引（D18 输入解析优先源）；null = 只用缓存目录探测。 */
    private val downloadDirIndex: DownloadDirIndex? = null,
    private val config: SiteCoreConfigProperties,
    @Value("\${anotherviewer.processing.concurrency:1}") private val concurrency: Int,
    @Value("\${anotherviewer.processing.task-ttl-ms:600000}") private val taskTtlMs: Long,
    @Value("\${anotherviewer.processing.max-tasks:100}") private val maxTasks: Int
) : DisposableBean {
    private val logger = LoggerFactory.getLogger(ImageProcessingService::class.java)

    private val tasks = ConcurrentHashMap<String, ProcessingTaskStatus>()
    private val cancelRequests = ConcurrentHashMap.newKeySet<String>()
    private val completedTasks = AtomicLong(0)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("image-processing")
    )
    private val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency)

    /**
     * 落库辅助状态（taskId → …），与内存任务表同生命周期（TTL 清理/驱逐时一并清除）：
     * - [recordMeta]：提交时快照（trigger/type/输出目录/创建时间 + 运行期发现的 sourceDir）。
     * - [recordMirror]：最后一次写穿的 record（增量写穿的基底）。
     * - [donePagesByTask]：本任务内成功页集合（D8 数据载体，随 upsert 落 done_pages CSV）。
     * - [errorInfo]：首个页级失败的机读码 + 文案（D12；取消时被 EP_CANCELLED 覆盖）。
     */
    private class RecordMeta(
        val trigger: ProcessingTrigger,
        val type: ProcessingType,
        val outputDir: String,
        val createdAt: Long,
        /** 首个成功解析的输入页父目录（D14 展示用）；空串 = 尚未发现。 */
        @Volatile var sourceDir: String = "",
    )

    private val recordMeta = ConcurrentHashMap<String, RecordMeta>()
    private val recordMirror = ConcurrentHashMap<String, ProcessingTaskRecord>()
    private val donePagesByTask = ConcurrentHashMap<String, MutableSet<Int>>()
    private val errorInfo = ConcurrentHashMap<String, PageError>()

    private class PageError(val code: String, val message: String)

    /**
     * Resolve the real page count of a gallery from Gallery Site metadata so the
     * whole gallery is processed, not a placeholder single page.
     *
     * @return number of pages (0-based range `0 until count`), or null when
     * the gallery is unknown or the count cannot be fetched.
     */
    fun resolvePageCount(galleryId: Long): Int? = galleryLookup?.resolvePageCount(galleryId)

    /**
     * Submit a gallery for image enhancement processing.
     *
     * @param galleryId the gallery to process
     * @param pages range of page indices to process (0-based)
     * @param options processing options (type, format, quality)
     * @param trigger 触发来源（落库展示；手动默认 MANUAL）
     * @param force D8 去重豁免（手动重试路径用）；默认 false = 已处理页跳过
     * @return taskId for status polling
     * @throws IllegalStateException if no processor is available, or when after
     *   dedup no page remains ("No pages to process ...")
     */
    fun submitGallery(
        galleryId: Long,
        pages: IntRange,
        options: ProcessingOptions = ProcessingOptions(ProcessingType.UPSCALE_2X),
        trigger: ProcessingTrigger = ProcessingTrigger.MANUAL,
        force: Boolean = false
    ): String {
        val processor = selectProcessor(options.type)
            ?: throw IllegalStateException("No available processor for type ${options.type}")

        // D8 页级去重：历史 COMPLETED 页 ∪ enhanced 产物文件已存在；force 豁免。
        // 去重查询失败按「无已知进度」fail-open（WARN）——宁可重跑，不因 DB 抖动拒绝提交。
        val pending = if (force) {
            pages.toList()
        } else {
            val done = runCatching { store?.findCompletedPages(galleryId, options.type) ?: emptySet() }
                .onFailure { logger.warn("Dedup lookup failed for gallery {} (fail-open): {}", galleryId, it.message) }
                .getOrDefault(emptySet())
            pages.filterNot { page -> page in done || Files.exists(enhancedPageFile(galleryId, page, options.outputFormat)) }
        }
        if (pending.isEmpty()) {
            throw IllegalStateException(
                "No pages to process for gallery $galleryId: all ${pages.count()} page(s) already processed"
            )
        }

        evictFinishedIfNeeded()

        val taskId = "proc-${UUID.randomUUID().toString().take(8)}"
        val totalPages = pending.size
        val outputDir = enhancedDir(galleryId)

        val status = ProcessingTaskStatus(
            taskId = taskId,
            galleryId = galleryId,
            state = TaskState.PENDING,
            totalPages = totalPages,
            processedPages = 0,
            failedPages = 0,
            currentPage = -1,
            startedAt = null,
            completedAt = null,
            error = null
        )
        tasks[taskId] = status

        // 提交快照落库（D10/D14）。title 尽力解析：GalleryLookupService 无标题
        // API（只有 token/页数解析），故暂存空串——列表端点自行兜底展示。
        val now = System.currentTimeMillis()
        recordMeta[taskId] = RecordMeta(
            trigger = trigger,
            type = options.type,
            outputDir = outputDir.toString(),
            createdAt = now,
        )
        donePagesByTask[taskId] = ConcurrentHashMap.newKeySet()
        recordMirror[taskId] = ProcessingTaskRecord(
            taskId = taskId,
            galleryId = galleryId,
            title = "",
            trigger = trigger,
            processingType = options.type,
            state = TaskState.PENDING,
            pagesTotal = totalPages,
            sourceDir = "",
            outputDir = outputDir.toString(),
            createdAt = now,
            updatedAt = now,
        )
        writeThrough(taskId)

        logger.info(
            "Processing task submitted: taskId={}, galleryId={}, pages={}, processor={}, trigger={}",
            taskId, galleryId, totalPages, processor.id, trigger
        )

        eventPublisher.publishEvent(ProcessingEvent.Started(
            taskId = taskId,
            galleryId = galleryId,
            totalPages = totalPages,
            processingType = options.type,
            processorId = processor.id
        ))

        scope.launch {
            semaphore.acquire()
            try {
                executeTask(taskId, galleryId, pending, options, processor)
            } finally {
                semaphore.release()
            }
        }

        return taskId
    }

    /**
     * Retry a previously FAILED task: resubmit the original gallery/type with
     * D8 dedup active — pages already recorded as done (in donePages or with an
     * existing enhanced artifact) are skipped naturally, so only the failed
     * pages actually rerun.
     *
     * @return the new taskId
     * @throws IllegalArgumentException when the taskId is unknown to the store
     * @throws IllegalStateException when the task is not FAILED (or nothing to rerun)
     */
    fun retryTask(taskId: String): String {
        val record = store?.findByTaskId(taskId)
            ?: throw IllegalArgumentException("Task not found")
        if (record.state != TaskState.FAILED) {
            throw IllegalStateException(
                "Task $taskId is ${record.state}; only FAILED tasks can be retried"
            )
        }
        // 传全页区间即可：失败页不在 donePages，D8 去重天然放行（免 force）。
        return submitGallery(
            galleryId = record.galleryId,
            pages = 0 until record.pagesTotal,
            options = ProcessingOptions(record.processingType),
            trigger = ProcessingTrigger.MANUAL,
            force = false
        )
    }

    /**
     * Request cancellation of a task. The running worker checks the flag
     * between pages; the task finishes as FAILED with a "cancelled" error.
     */
    fun cancelTask(taskId: String): Boolean {
        if (!tasks.containsKey(taskId)) return false
        cancelRequests.add(taskId)
        return true
    }

    /**
     * Query the current status of a processing task.
     *
     * @return task status, or null if taskId is unknown
     */
    fun getTaskStatus(taskId: String): ProcessingTaskStatus? = tasks[taskId]

    /**
     * Get all active tasks (PENDING or PROCESSING).
     */
    fun getActiveTasks(): List<ProcessingTaskStatus> =
        tasks.values.filter { it.state == TaskState.PENDING || it.state == TaskState.PROCESSING }

    /**
     * Active tasks as persisted-record shape (含 submit 元数据：trigger/title/
     * sourceDir/outputDir/type 与瞬时进度)——GET /process/tasks?active=1 的数据源。
     */
    fun getActiveRecords(): List<ProcessingTaskRecord> =
        getActiveTasks().mapNotNull { status ->
            val meta = recordMeta[status.taskId] ?: return@mapNotNull null
            buildRecord(status, meta)
        }

    /**
     * Get the number of tasks in the queue (pending + processing).
     */
    fun getQueueSize(): Int =
        tasks.values.count { it.state == TaskState.PENDING || it.state == TaskState.PROCESSING }

    /** Total number of tasks that finished in the DONE state (monotonic counter). */
    fun getCompletedTaskCount(): Long = completedTasks.get()

    private suspend fun executeTask(
        taskId: String,
        galleryId: Long,
        pages: List<Int>,
        options: ProcessingOptions,
        processor: ImageProcessor
    ) {
        val status = tasks[taskId] ?: return
        val startTime = System.currentTimeMillis()

        updateStatus(taskId) {
            it.copy(state = TaskState.PROCESSING, startedAt = Instant.now())
        }

        val outputDir = enhancedDir(galleryId)
        Files.createDirectories(outputDir)

        var processed = 0
        var failed = 0
        var firstFailedPage: Int? = null

        for (page in pages) {
            if (taskId in cancelRequests) break

            updateStatus(taskId) { it.copy(currentPage = page) }

            try {
                val inputPath = resolveInputImage(galleryId, page)
                if (inputPath == null || !Files.exists(inputPath)) {
                    logger.warn("Input image not found for gallery={} page={}, skipping", galleryId, page)
                    failed++
                    if (firstFailedPage == null) firstFailedPage = page
                    errorInfo.putIfAbsent(
                        taskId,
                        PageError(
                            ERROR_CODE_PROCESS,
                            "Input image not found: gallery=$galleryId page=$page"
                        )
                    )
                    updateStatus(taskId) {
                        it.copy(processedPages = processed, failedPages = failed)
                    }
                    continue
                }
                recordMeta[taskId]?.let { meta -> if (meta.sourceDir.isEmpty()) {
                    meta.sourceDir = inputPath.parent?.toString() ?: ""
                } }

                // Honor the configured outputPath: write the enhanced image to
                // {cachePath}/enhanced/{galleryId}/{page}.{format} so the image
                // endpoint can serve it via ?enhanced=1.
                val resultPath = processor.process(inputPath, options)
                val outputPath = outputDir.resolve("$page.${options.outputFormat}")
                if (resultPath != outputPath) {
                    Files.deleteIfExists(outputPath)
                    Files.copy(resultPath, outputPath)
                }
                // 临时产物清理（EntryPointProcessor 契约：process 返回的 Path 生命周期
                // 归调用方）。守卫：resultPath 即 outputPath（处理器直写产物位）或即
                // inputPath（Noop 同扩展名原路径返回）时绝不删——防误删源图。
                if (resultPath != outputPath && resultPath != inputPath) {
                    runCatching { Files.deleteIfExists(resultPath) }
                        .onFailure {
                            logger.warn(
                                "Temp artifact cleanup failed (best-effort): {}: {}", resultPath, it.message
                            )
                        }
                }

                val durationMs = System.currentTimeMillis() - startTime
                logger.debug(
                    "Image processed: taskId={}, galleryId={}, page={}, processor={}, durationMs={}",
                    taskId, galleryId, page, processor.id, durationMs
                )

                processed++
                donePagesByTask[taskId]?.add(page)
                updateStatus(taskId) {
                    it.copy(processedPages = processed, failedPages = failed)
                }

                eventPublisher.publishEvent(ProcessingEvent.Progress(
                    taskId = taskId,
                    galleryId = galleryId,
                    processedPages = processed,
                    totalPages = pages.size,
                    currentPage = page
                ))

                eventPublisher.publishEvent(ProcessingEvent.EnhancedReady(
                    taskId = taskId,
                    galleryId = galleryId,
                    page = page,
                    enhancedUrl = "/api/v1/image/$galleryId/$page?enhanced=1",
                    originalUrl = "/api/v1/image/$galleryId/$page",
                    processingType = options.type,
                    fileSize = runCatching { Files.size(outputPath) }.getOrDefault(0L),
                    width = readImageWidth(outputPath),
                    height = readImageHeight(outputPath)
                ))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(
                    "Image processing failed: taskId={}, galleryId={}, page={}",
                    taskId, galleryId, page, e
                )
                recordError(taskId, e)
                failed++
                if (firstFailedPage == null) firstFailedPage = page
                updateStatus(taskId) {
                    it.copy(processedPages = processed, failedPages = failed)
                }
            }
        }

        val elapsedMs = System.currentTimeMillis() - startTime
        val cancelled = taskId in cancelRequests
        val finalState = when {
            cancelled -> TaskState.FAILED
            failed > 0 -> TaskState.FAILED
            else -> TaskState.DONE
        }
        val error = when {
            cancelled -> "Task cancelled"
            failed > 0 -> "$failed of ${pages.size} pages failed to process"
            else -> null
        }
        if (cancelled) {
            // D12：取消是终结原因——机读码固定 EP_CANCELLED（覆盖此前首个页失败）。
            errorInfo[taskId] = PageError(ERROR_CODE_CANCELLED, "Task cancelled")
        }

        updateStatus(taskId) {
            it.copy(
                state = finalState,
                currentPage = -1,
                completedAt = Instant.now(),
                processedPages = processed,
                failedPages = failed,
                error = error
            )
        }

        if (finalState == TaskState.FAILED) {
            eventPublisher.publishEvent(ProcessingEvent.Failed(
                taskId = taskId,
                galleryId = galleryId,
                error = error ?: "Processing failed",
                failedPage = firstFailedPage,
                processedBeforeFailure = processed
            ))
        } else {
            completedTasks.incrementAndGet()
            eventPublisher.publishEvent(ProcessingEvent.Completed(
                taskId = taskId,
                galleryId = galleryId,
                enhancedPages = processed,
                elapsedMs = elapsedMs
            ))
        }

        logger.info(
            "Processing task finished: taskId={}, galleryId={}, state={}, processed={}, failed={}, elapsedMs={}",
            taskId, galleryId, tasks[taskId]?.state, processed, failed, elapsedMs
        )

        scheduleCleanup(taskId)
    }

    /**
     * Evict finished tasks once the map exceeds [maxTasks] (oldest first) and
     * drop finished tasks after [taskTtlMs], keeping the map bounded.
     */
    private fun evictFinishedIfNeeded() {
        if (tasks.size < maxTasks) return
        val finished = tasks.values
            .filter { it.state == TaskState.DONE || it.state == TaskState.FAILED }
            .sortedBy { it.completedAt ?: Instant.EPOCH }
        finished.take((tasks.size - maxTasks).coerceAtLeast(1)).forEach {
            tasks.remove(it.taskId)
            purgeSideState(it.taskId)
        }
    }

    private fun scheduleCleanup(taskId: String) {
        scope.launch {
            delay(taskTtlMs)
            tasks.remove(taskId)
            purgeSideState(taskId)
        }
    }

    private fun purgeSideState(taskId: String) {
        recordMeta.remove(taskId)
        recordMirror.remove(taskId)
        donePagesByTask.remove(taskId)
        errorInfo.remove(taskId)
        cancelRequests.remove(taskId)
    }

    private fun selectProcessor(type: ProcessingType): ImageProcessor? {
        return processors.firstOrNull { it.isAvailable() && type in it.capabilities }
    }

    /**
     * MASTER-2026-08-22 P5：processorAvailable 的真实语义（observability.md §4.3
     * "Whether a non-noop processor is connected"）——与「是否有活跃任务」无关。
     * 当前仅有 NoopProcessor 占位，恒 false；接入真实处理器（waifu2x 等）后自动为 true。
     */
    fun nonNoopProcessorAvailable(): Boolean = availableNonNoopProcessor() != null

    /** 当前可用的非占位处理器 id；无则 null。 */
    fun availableNonNoopProcessorId(): String? = availableNonNoopProcessor()?.id

    private fun availableNonNoopProcessor(): ImageProcessor? =
        processors.firstOrNull { it.id != NoopProcessor.PROCESSOR_ID && it.isAvailable() }

    /**
     * D18 输入解析：优先 App 推送下载目录（DownloadDirIndex 索引，命中即实际
     * 落盘文件名——兼容 %04d/%08d 两种布局），未命中回落缓存目录扩展名探测。
     */
    private fun resolveInputImage(galleryId: Long, page: Int): Path? {
        val index = downloadDirIndex
        if (index != null) {
            val ref = index.findPage(galleryId, page)
            val dir = ref?.let { index.dirFor(galleryId) }
            if (ref != null && dir != null) {
                val candidate = dir.toPath().resolve(ref.fileName)
                if (Files.exists(candidate)) return candidate
            }
        }
        // Look for cached original image in standard cache locations
        val cacheDir = Path.of(config.download.cachePath, galleryId.toString())
        if (!Files.isDirectory(cacheDir)) return null

        // Try common extensions
        for (ext in listOf("jpg", "jpeg", "png", "webp", "gif")) {
            val candidate = cacheDir.resolve("$page.$ext")
            if (Files.exists(candidate)) return candidate
        }
        return null
    }

    private fun enhancedDir(galleryId: Long): Path =
        Path.of(config.download.cachePath, "enhanced", galleryId.toString())

    private fun enhancedPageFile(galleryId: Long, page: Int, format: String): Path =
        enhancedDir(galleryId).resolve("$page.$format")

    private fun readImageWidth(path: Path): Int = readImageDimension(path).first

    private fun readImageHeight(path: Path): Int = readImageDimension(path).second

    /** Best-effort header-only dimension read; 0 when the format is unsupported (e.g. webp). */
    private fun readImageDimension(path: Path): Pair<Int, Int> {
        return try {
            ImageIO.createImageInputStream(path.toFile()).use { stream ->
                if (stream == null) return Pair(0, 0)
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) return Pair(0, 0)
                val reader = readers.next()
                try {
                    reader.setInput(stream)
                    Pair(reader.getWidth(0), reader.getHeight(0))
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: Exception) {
            Pair(0, 0)
        }
    }

    private fun updateStatus(taskId: String, transform: (ProcessingTaskStatus) -> ProcessingTaskStatus) {
        tasks.computeIfPresent(taskId) { _, status -> transform(status) }
        writeThrough(taskId)
    }

    /**
     * D10 写穿：内存状态迁移同步 upsert 到 store（pagesDone/pagesFailed/donePages/
     * state/startedAt/finishedAt/errorCode/errorMessage 保持最新）。任何失败只
     * WARN，绝不阻断处理——DB 落后由历史端点诚实显示。
     */
    private fun writeThrough(taskId: String) {
        val taskStore = store ?: return
        try {
            val status = tasks[taskId] ?: return
            val meta = recordMeta[taskId] ?: return
            val record = buildRecord(status, meta)
            recordMirror[taskId] = record
            taskStore.upsert(record)
        } catch (e: Exception) {
            logger.warn("Processing task write-through failed (D10, non-blocking): taskId={}, cause={}", taskId, e.message)
        }
    }

    /** 内存状态 + 提交快照 → record 最新形状（含 donePages/错误归档/时间戳）。 */
    private fun buildRecord(status: ProcessingTaskStatus, meta: RecordMeta): ProcessingTaskRecord {
        val error = errorInfo[status.taskId]
        return ProcessingTaskRecord(
            taskId = status.taskId,
            galleryId = status.galleryId,
            title = "",
            trigger = meta.trigger,
            processingType = meta.type,
            state = status.state,
            pagesTotal = status.totalPages,
            pagesDone = status.processedPages,
            pagesFailed = status.failedPages,
            sourceDir = meta.sourceDir,
            outputDir = meta.outputDir,
            errorCode = error?.code ?: "",
            errorMessage = error?.message?.take(ERROR_MESSAGE_MAX) ?: "",
            createdAt = meta.createdAt,
            startedAt = status.startedAt?.toEpochMilli() ?: 0L,
            finishedAt = status.completedAt?.toEpochMilli() ?: 0L,
            updatedAt = System.currentTimeMillis(),
            donePages = donePagesByTask[status.taskId]?.toSet() ?: emptySet(),
        )
    }

    /**
     * D12 错误归档：processor 抛 [EpException] 时取其机读 code（message 含 code
     * 前缀一并保留，截断 2048；store 层会再截）；其他异常统一 EP_PROCESS_ERROR。
     * 记「首个」页失败——重试路径是新任务新 record，不受影响。
     */
    private fun recordError(taskId: String, e: Exception) {
        val error = if (e is EpException) {
            PageError(e.code, (e.message ?: "").take(ERROR_MESSAGE_MAX))
        } else {
            PageError(ERROR_CODE_PROCESS, (e.message ?: e.javaClass.simpleName).take(ERROR_MESSAGE_MAX))
        }
        errorInfo.putIfAbsent(taskId, error)
    }

    override fun destroy() {
        scope.cancel()
        runBlocking {
            scope.coroutineContext.job.join()
        }
    }

    private companion object {
        /** D12：error_message 截断 2048 字符（store 层还会再截）。 */
        const val ERROR_MESSAGE_MAX = 2048

        /** D12：取消终结码（cancelRequests 命中）。 */
        const val ERROR_CODE_CANCELLED = "EP_CANCELLED"

        /** D12：非 EntryPoint 异常的兜底机读码。 */
        const val ERROR_CODE_PROCESS = "EP_PROCESS_ERROR"
    }
}

// --- Task state model ---

enum class TaskState {
    PENDING, PROCESSING, DONE, FAILED
}

data class ProcessingTaskStatus(
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

// --- Spring application events for WebSocket forwarding ---

sealed class ProcessingEvent {
    data class Started(
        val taskId: String,
        val galleryId: Long,
        val totalPages: Int,
        val processingType: ProcessingType,
        val processorId: String
    ) : ProcessingEvent()

    data class Progress(
        val taskId: String,
        val galleryId: Long,
        val processedPages: Int,
        val totalPages: Int,
        val currentPage: Int
    ) : ProcessingEvent()

    data class Completed(
        val taskId: String,
        val galleryId: Long,
        val enhancedPages: Int,
        val elapsedMs: Long
    ) : ProcessingEvent()

    data class Failed(
        val taskId: String,
        val galleryId: Long,
        val error: String,
        val failedPage: Int?,
        val processedBeforeFailure: Int
    ) : ProcessingEvent()

    /**
     * A single page's enhanced version is ready on disk and can be served via
     * `GET /api/v1/image/{galleryId}/{page}?enhanced=1`.
     * See contracts/websocket-protocol.md §3.3 (image.enhanced.ready).
     */
    data class EnhancedReady(
        val taskId: String,
        val galleryId: Long,
        val page: Int,
        val enhancedUrl: String,
        val originalUrl: String,
        val processingType: ProcessingType,
        val fileSize: Long,
        val width: Int,
        val height: Int
    ) : ProcessingEvent()
}
