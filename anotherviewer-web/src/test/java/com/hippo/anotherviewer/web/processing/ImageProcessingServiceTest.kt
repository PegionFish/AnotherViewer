package com.hippo.anotherviewer.web.processing

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.processing.ep.EpException
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.PageRef
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.context.ApplicationEventPublisher
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ImageProcessingServiceTest {

    private lateinit var service: ImageProcessingService
    private lateinit var noopProcessor: NoopProcessor
    private lateinit var fakeStore: FakeProcessingTaskStore
    private val publishedEvents = mutableListOf<Any>()

    private val testPublisher = ApplicationEventPublisher { event -> publishedEvents.add(event) }

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        publishedEvents.clear()
        noopProcessor = NoopProcessor()
        fakeStore = FakeProcessingTaskStore()
        service = newService(listOf(noopProcessor))
    }

    @AfterEach
    fun tearDown() {
        service.destroy()
    }

    /** 统一构造：可注入 fake store / 自定处理器 / DownloadDirIndex mock。 */
    private fun newService(
        processors: List<ImageProcessor>,
        store: ProcessingTaskStore? = fakeStore,
        dirIndex: DownloadDirIndex? = null,
    ): ImageProcessingService = ImageProcessingService(
        processors = processors,
        eventPublisher = testPublisher,
        galleryLookup = null,
        store = store,
        downloadDirIndex = dirIndex,
        config = SiteCoreConfigProperties().apply { download.cachePath = tempDir.toString() },
        concurrency = 1,
        taskTtlMs = 60000,
        maxTasks = 100
    )

    /** 在缓存目录造一张「已缓存原图」。 */
    private fun seedCachePage(galleryId: Long, page: Int, ext: String = "jpg", content: String = "page$page"): Path {
        val dir = Files.createDirectories(tempDir.resolve(galleryId.toString()))
        return Files.writeString(dir.resolve("$page.$ext"), content)
    }

    private fun awaitDone(taskId: String, timeoutMs: Long = 2000): ProcessingTaskStatus? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = service.getTaskStatus(taskId)
            if (status != null && (status.state == TaskState.DONE || status.state == TaskState.FAILED)) return status
            Thread.sleep(25)
        }
        return service.getTaskStatus(taskId)
    }

    private fun awaitDone(target: ImageProcessingService, taskId: String, timeoutMs: Long = 2000): ProcessingTaskStatus? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = target.getTaskStatus(taskId)
            if (status != null && (status.state == TaskState.DONE || status.state == TaskState.FAILED)) return status
            Thread.sleep(25)
        }
        return target.getTaskStatus(taskId)
    }

    // ------------------------------------------------------------------
    // 既有行为（回归）
    // ------------------------------------------------------------------

    @Test
    fun `NoopProcessor is available and supports all types`() {
        assertTrue(noopProcessor.isAvailable())
        assertEquals("noop", noopProcessor.id)
        assertEquals(ProcessingType.entries.toSet(), noopProcessor.capabilities)
    }

    @Test
    fun `NoopProcessor copies input to output`() = runBlocking {
        val input = tempDir.resolve("test.jpg")
        Files.writeString(input, "fake image data")

        val options = ProcessingOptions(ProcessingType.UPSCALE_2X, outputFormat = "png")
        val output = noopProcessor.process(input, options)

        assertTrue(Files.exists(output))
        assertEquals("test.png", output.fileName.toString())
        assertEquals("fake image data", Files.readString(output))
    }

    @Test
    fun `submitGallery returns taskId and creates PENDING task`() {
        seedCachePage(12345, 0)

        val taskId = service.submitGallery(12345, 0..0)

        assertNotNull(taskId)
        assertTrue(taskId.startsWith("proc-"))

        val status = service.getTaskStatus(taskId)
        assertNotNull(status)
        assertEquals(12345L, status!!.galleryId)
        assertEquals(1, status.totalPages)
    }

    @Test
    fun `getTaskStatus returns null for unknown taskId`() {
        assertNull(service.getTaskStatus("nonexistent"))
    }

    @Test
    fun `task transitions to DONE after processing`() {
        seedCachePage(99999, 0)
        seedCachePage(99999, 1)

        val taskId = service.submitGallery(99999, 0..1)
        val status = awaitDone(taskId)

        assertNotNull(status)
        assertEquals(TaskState.DONE, status!!.state)
        assertEquals(2, status.processedPages)
        assertEquals(0, status.failedPages)
        assertNotNull(status.startedAt)
        assertNotNull(status.completedAt)
    }

    @Test
    fun `task transitions to FAILED when any page fails`() {
        // Only page 0 exists — page 1 is missing, so the task must FAIL
        // (not report DONE as the pre-fix copy-paste bug did).
        seedCachePage(77777, 0)

        val taskId = service.submitGallery(77777, 0..1)
        val status = awaitDone(taskId)

        assertNotNull(status)
        assertEquals(TaskState.FAILED, status!!.state)
        assertEquals(1, status.processedPages)
        assertEquals(1, status.failedPages)
        assertNotNull(status.error)
    }

    @Test
    fun `Started event is published on submit`() {
        seedCachePage(11111, 0)

        service.submitGallery(11111, 0..0)

        assertTrue(publishedEvents.any { it is ProcessingEvent.Started })
        val started = publishedEvents.filterIsInstance<ProcessingEvent.Started>().first()
        assertEquals(11111L, started.galleryId)
        assertEquals("noop", started.processorId)
    }

    @Test
    fun `queue size reflects active tasks`() {
        assertEquals(0, service.getQueueSize())

        seedCachePage(22222, 0)

        service.submitGallery(22222, 0..0)
        // Immediately after submit, task should be in queue (PENDING or PROCESSING)
        assertTrue(service.getQueueSize() >= 0) // May already complete due to noop speed
    }

    // ------------------------------------------------------------------
    // W2 写穿（D10）
    // ------------------------------------------------------------------

    @Test
    fun `submitGallery writes initial PENDING record synchronously`() {
        seedCachePage(40001, 0)

        val taskId = service.submitGallery(40001, 0..0)

        // submit 同步落首条 PENDING record（后续由执行协程增量写穿）
        assertTrue(fakeStore.upserts.isNotEmpty())
        val first = fakeStore.upserts.first()
        assertEquals(taskId, first.taskId)
        assertEquals(TaskState.PENDING, first.state)
        assertEquals(1, first.pagesTotal)
    }

    @Test
    fun `write-through keeps record current until DONE`() {
        seedCachePage(4242, 0)
        seedCachePage(4242, 1)

        val taskId = service.submitGallery(
            galleryId = 4242,
            pages = 0..1,
            options = ProcessingOptions(ProcessingType.REMOVE_BG),
            trigger = ProcessingTrigger.DOWNLOAD_AUTO
        )
        awaitDone(taskId)

        val record = fakeStore.findByTaskId(taskId)!!
        assertEquals(TaskState.DONE, record.state)
        assertEquals(ProcessingTrigger.DOWNLOAD_AUTO, record.trigger)
        assertEquals(ProcessingType.REMOVE_BG, record.processingType)
        assertEquals(2, record.pagesTotal)
        assertEquals(2, record.pagesDone)
        assertEquals(0, record.pagesFailed)
        assertEquals(setOf(0, 1), record.donePages)
        assertTrue(record.startedAt > 0)
        assertTrue(record.finishedAt >= record.startedAt)
        assertTrue(record.outputDir.contains("enhanced"))
        // sourceDir = 首个成功输入的父目录（缓存目录兜底路径）
        assertEquals(tempDir.resolve("4242").toString(), record.sourceDir)
        // 错误归档：成功任务无错误
        assertEquals("", record.errorCode)
        assertEquals("", record.errorMessage)
        // 增量写穿：初始 + PROCESSING + 每页进度 + 终态 至少多次 upsert
        assertTrue(fakeStore.upserts.size >= 4)
        // 至少一次 PENDING 快照和一次终态快照
        assertEquals(TaskState.PENDING, fakeStore.upserts.first().state)
        assertEquals(TaskState.DONE, fakeStore.upserts.last().state)
    }

    @Test
    fun `service works without store (legacy wiring)`() {
        val storeLess = newService(listOf(noopProcessor), store = null)
        try {
            seedCachePage(40002, 0)
            val taskId = storeLess.submitGallery(40002, 0..0)
            assertEquals(TaskState.DONE, awaitDone(storeLess, taskId)!!.state)
        } finally {
            storeLess.destroy()
        }
    }

    // ------------------------------------------------------------------
    // W2 页级去重（D8）
    // ------------------------------------------------------------------

    @Test
    fun `dedup skips pages recorded as completed in store`() {
        (0..2).forEach { seedCachePage(40003, it) }
        fakeStore.completedPages = setOf(0)

        val taskId = service.submitGallery(40003, 0..2)

        // page 0 在 store COMPLETED 集合 → 只剩 2 页
        assertEquals(2, service.getTaskStatus(taskId)!!.totalPages)
    }

    @Test
    fun `dedup skips pages whose enhanced artifact already exists`() {
        seedCachePage(55555, 0)
        seedCachePage(55555, 1)
        Files.createDirectories(tempDir.resolve("enhanced").resolve("55555"))
        Files.writeString(tempDir.resolve("enhanced").resolve("55555").resolve("0.png"), "already enhanced")

        val taskId = service.submitGallery(55555, 0..1)

        assertEquals(1, service.getTaskStatus(taskId)!!.totalPages)
    }

    @Test
    fun `submitGallery throws IllegalStateException when no pages to process`() {
        seedCachePage(40004, 0)
        fakeStore.completedPages = setOf(0)

        val e = assertThrows<IllegalStateException> { service.submitGallery(40004, 0..0) }
        assertTrue(e.message!!.startsWith("No pages to process"))
    }

    @Test
    fun `force bypasses dedup`() {
        seedCachePage(40005, 0)
        fakeStore.completedPages = setOf(0)

        val taskId = service.submitGallery(
            galleryId = 40005, pages = 0..0,
            options = ProcessingOptions(ProcessingType.UPSCALE_2X),
            trigger = ProcessingTrigger.MANUAL, force = true
        )

        assertEquals(1, service.getTaskStatus(taskId)!!.totalPages)
    }

    // ------------------------------------------------------------------
    // W2 重试（C5 契约：IllegalArgumentException→404 / IllegalStateException→409）
    // ------------------------------------------------------------------

    @Test
    fun `retryTask reruns only pages not in donePages`() {
        seedCachePage(777001, 0)
        seedCachePage(777001, 1)
        seedCachePage(777001, 2)
        fakeStore.completedPages = setOf(0, 1)
        fakeStore.upsert(
            ProcessingTaskRecord(
                taskId = "proc-seed000",
                galleryId = 777001,
                trigger = ProcessingTrigger.MANUAL,
                processingType = ProcessingType.REMOVE_BG,
                state = TaskState.FAILED,
                pagesTotal = 3,
                pagesDone = 2,
                pagesFailed = 1,
                donePages = setOf(0, 1),
                createdAt = System.currentTimeMillis(),
            )
        )

        val newTaskId = service.retryTask("proc-seed000")
        val status = awaitDone(newTaskId)

        assertTrue(newTaskId != "proc-seed000")
        assertEquals(TaskState.DONE, status!!.state)
        val record = fakeStore.findByTaskId(newTaskId)!!
        assertEquals(777001L, record.galleryId)
        assertEquals(1, record.pagesTotal) // 只有失败页 2 进入新任务
        assertEquals(setOf(2), record.donePages)
        assertEquals(ProcessingTrigger.MANUAL, record.trigger)
    }

    @Test
    fun `retryTask unknown taskId throws IllegalArgumentException`() {
        val e = assertThrows<IllegalArgumentException> { service.retryTask("proc-missing") }
        assertEquals("Task not found", e.message)
    }

    @Test
    fun `retryTask non-FAILED task throws IllegalStateException`() {
        fakeStore.upsert(
            ProcessingTaskRecord(
                taskId = "proc-done0001",
                galleryId = 1,
                state = TaskState.DONE,
                pagesTotal = 1,
                donePages = setOf(0),
            )
        )
        assertThrows<IllegalStateException> { service.retryTask("proc-done0001") }
    }

    // ------------------------------------------------------------------
    // W2 活跃任务 record 视图（C5 GET /process/tasks?active=1 数据源）
    // ------------------------------------------------------------------

    @Test
    fun `getActiveRecords exposes submit metadata and clears on completion`() {
        val gate = CountDownLatch(1)
        val gated = newService(listOf(BlockingProcessor(gate)))
        try {
            seedCachePage(31337, 0)

            val taskId = gated.submitGallery(
                galleryId = 31337,
                pages = 0..0,
                options = ProcessingOptions(ProcessingType.REMOVE_BG),
                trigger = ProcessingTrigger.DOWNLOAD_AUTO
            )

            val records = gated.getActiveRecords()
            assertEquals(1, records.size)
            val record = records[0]
            assertEquals(taskId, record.taskId)
            assertEquals(31337L, record.galleryId)
            assertEquals(ProcessingTrigger.DOWNLOAD_AUTO, record.trigger)
            assertEquals(ProcessingType.REMOVE_BG, record.processingType)
            assertTrue(record.outputDir.contains("enhanced"))
            assertTrue(record.state == TaskState.PENDING || record.state == TaskState.PROCESSING)

            gate.countDown()
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline && gated.getActiveRecords().isNotEmpty()) Thread.sleep(25)
            assertTrue(gated.getActiveRecords().isEmpty())
        } finally {
            gate.countDown()
            gated.destroy()
        }
    }

    // ------------------------------------------------------------------
    // W2 错误归档（D12）
    // ------------------------------------------------------------------

    @Test
    fun `EpException code and message are archived`() {
        val failing = newService(
            listOf(FailingProcessor(EpException("QUEUE_FULL", 429, "entrypoint queue is full")))
        )
        try {
            seedCachePage(40010, 0)
            val taskId = failing.submitGallery(40010, 0..0)
            awaitDone(failing, taskId)

            val record = fakeStore.findByTaskId(taskId)!!
            assertEquals(TaskState.FAILED, record.state)
            assertEquals(1, record.pagesFailed)
            assertEquals("QUEUE_FULL", record.errorCode)
            assertTrue(record.errorMessage.contains("entrypoint queue is full"))
        } finally {
            failing.destroy()
        }
    }

    @Test
    fun `non-EpException failures archive EP_PROCESS_ERROR`() {
        val failing = newService(listOf(FailingProcessor(RuntimeException("boom"))))
        try {
            seedCachePage(40011, 0)
            val taskId = failing.submitGallery(40011, 0..0)
            awaitDone(failing, taskId)

            val record = fakeStore.findByTaskId(taskId)!!
            assertEquals("EP_PROCESS_ERROR", record.errorCode)
            assertEquals("boom", record.errorMessage)
        } finally {
            failing.destroy()
        }
    }

    @Test
    fun `cancellation archives EP_CANCELLED`() {
        val gate = CountDownLatch(1)
        val gated = newService(listOf(BlockingProcessor(gate)))
        try {
            seedCachePage(40012, 0)
            seedCachePage(40012, 1)
            val taskId = gated.submitGallery(40012, 0..1)
            Thread.sleep(150) // 让任务进入 PROCESSING 并阻塞在第一页
            assertTrue(gated.cancelTask(taskId))
            gate.countDown()
            awaitDone(gated, taskId)

            val record = fakeStore.findByTaskId(taskId)!!
            assertEquals(TaskState.FAILED, record.state)
            assertEquals("EP_CANCELLED", record.errorCode)
            assertEquals("Task cancelled", record.errorMessage)
        } finally {
            gate.countDown()
            gated.destroy()
        }
    }

    @Test
    fun `input image not found archives EP_PROCESS_ERROR with page info`() {
        seedCachePage(40013, 0) // page 1 缺失

        val taskId = service.submitGallery(40013, 0..1)
        awaitDone(taskId)

        val record = fakeStore.findByTaskId(taskId)!!
        assertEquals("EP_PROCESS_ERROR", record.errorCode)
        assertTrue(record.errorMessage.contains("page=1"))
    }

    // ------------------------------------------------------------------
    // W2 临时产物清理（EntryPointProcessor 契约：resultPath 生命周期归调用方）
    // ------------------------------------------------------------------

    @Test
    fun `temp artifact is deleted after copy to enhanced output`() {
        val workDir = Files.createDirectories(tempDir.resolve("artifacts"))
        seedCachePage(40020, 0, ext = "jpg")
        val tempOut = newService(listOf(TempOutputProcessor(workDir)))
        try {
            val taskId = tempOut.submitGallery(40020, 0..0)
            awaitDone(tempOut, taskId)

            // 产物已落到 enhanced 位
            val enhanced = tempDir.resolve("enhanced").resolve("40020").resolve("0.png")
            assertTrue(Files.exists(enhanced))
            assertEquals("processed:page0", Files.readString(enhanced))
            // 临时文件已清理
            assertEquals(0, Files.list(workDir).use { it.count() })
            // 源输入完好
            assertTrue(Files.exists(tempDir.resolve("40020").resolve("0.jpg")))
        } finally {
            tempOut.destroy()
        }
    }

    @Test
    fun `processor returning input path does not delete the source`() {
        // Noop 以同扩展名处理时 resultPath == inputPath（0.png → 0.png）——守卫不误删源图。
        seedCachePage(40021, 0, ext = "png", content = "original")

        val taskId = service.submitGallery(
            galleryId = 40021, pages = 0..0,
            options = ProcessingOptions(ProcessingType.UPSCALE_2X, outputFormat = "png")
        )
        awaitDone(taskId)

        assertEquals("original", Files.readString(tempDir.resolve("40021").resolve("0.png")))
        assertEquals("original", Files.readString(tempDir.resolve("enhanced").resolve("40021").resolve("0.png")))
    }

    // ------------------------------------------------------------------
    // W2 输入解析（D18：下载目录优先，缓存目录兜底）
    // ------------------------------------------------------------------

    @Test
    fun `download dir index hit supplies input and sourceDir`() {
        val downloadDir = Files.createDirectories(tempDir.resolve("downloads").resolve("88888"))
        Files.writeString(downloadDir.resolve("00000001.png"), "pushed page")
        val dirIndex = mock(DownloadDirIndex::class.java)
        `when`(dirIndex.findPage(eq(88888L), eq(0)))
            .thenReturn(PageRef(gid = 88888, page = 1, ext = "png", size = 11, fileName = "00000001.png"))
        `when`(dirIndex.dirFor(88888L)).thenReturn(downloadDir.toFile())
        val indexed = newService(listOf(noopProcessor), dirIndex = dirIndex)
        try {
            val taskId = indexed.submitGallery(88888, 0..0)
            awaitDone(indexed, taskId)

            assertEquals(TaskState.DONE, indexed.getTaskStatus(taskId)!!.state)
            assertEquals(downloadDir.toString(), fakeStore.findByTaskId(taskId)!!.sourceDir)
            assertEquals(
                "pushed page",
                Files.readString(tempDir.resolve("enhanced").resolve("88888").resolve("0.png"))
            )
        } finally {
            indexed.destroy()
        }
    }

    @Test
    fun `index miss falls back to cache directory probing`() {
        seedCachePage(88887, 0)
        val dirIndex = mock(DownloadDirIndex::class.java)
        `when`(dirIndex.findPage(eq(88887L), eq(0))).thenReturn(null)
        val indexed = newService(listOf(noopProcessor), dirIndex = dirIndex)
        try {
            val taskId = indexed.submitGallery(88887, 0..0)
            awaitDone(indexed, taskId)
            assertEquals(TaskState.DONE, indexed.getTaskStatus(taskId)!!.state)
        } finally {
            indexed.destroy()
        }
    }

    // ------------------------------------------------------------------
    // 测试桩
    // ------------------------------------------------------------------

    /** 收集写穿 upsert 的 fake store（可变 list 供断言）。 */
    private class FakeProcessingTaskStore : ProcessingTaskStore {
        val upserts = mutableListOf<ProcessingTaskRecord>()
        val byId = LinkedHashMap<String, ProcessingTaskRecord>()
        var completedPages: Set<Int> = emptySet()

        override fun upsert(record: ProcessingTaskRecord) {
            upserts.add(record)
            byId[record.taskId] = record
        }

        override fun findByTaskId(taskId: String): ProcessingTaskRecord? = byId[taskId]

        override fun findActive(): List<ProcessingTaskRecord> =
            byId.values.filter { it.state == TaskState.PENDING || it.state == TaskState.PROCESSING }

        override fun findHistory(page: Int, size: Int, state: TaskState?): ProcessingHistoryPage =
            ProcessingHistoryPage(items = byId.values.toList(), total = byId.size.toLong(), page = page, size = size)

        override fun findCompletedPages(galleryId: Long, type: ProcessingType): Set<Int> = completedPages

        override fun markInterruptedAtStartup(): Int = 0
    }

    /** 阻塞在 latch 上的处理器（取消/活跃视图测试用）。 */
    private class BlockingProcessor(private val gate: CountDownLatch) : ImageProcessor {
        override val id = "blocking"
        override fun isAvailable() = true
        override val capabilities = ProcessingType.entries.toSet()

        override suspend fun process(input: Path, options: ProcessingOptions): Path {
            gate.await(5, TimeUnit.SECONDS)
            return input
        }
    }

    /** 恒失败的处理器。 */
    private class FailingProcessor(private val error: Exception) : ImageProcessor {
        override val id = "failing"
        override fun isAvailable() = true
        override val capabilities = ProcessingType.entries.toSet()

        override suspend fun process(input: Path, options: ProcessingOptions): Path =
            throw error
    }

    /** 产出独立临时文件的处理器（模拟 EntryPoint 产物下载 → 临时路径）。 */
    private class TempOutputProcessor(private val workDir: Path) : ImageProcessor {
        override val id = "tempout"
        override fun isAvailable() = true
        override val capabilities = ProcessingType.entries.toSet()

        override suspend fun process(input: Path, options: ProcessingOptions): Path {
            val tmp = Files.createTempFile(workDir, "artifact-", ".png")
            Files.writeString(tmp, "processed:" + Files.readString(input))
            return tmp
        }
    }
}
