package com.hippo.anotherviewer.web.processing

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.dto.DownloadProgress
import com.hippo.anotherviewer.web.entity.DownloadInfoEntity
import com.hippo.anotherviewer.web.processing.ep.CapabilityMapper
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.ServerConfigService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentCaptor
import com.hippo.anotherviewer.web.any
import com.hippo.anotherviewer.web.captureK
import com.hippo.anotherviewer.web.eq
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.nio.file.Files
import java.nio.file.Path

/**
 * W2 C4 自动化编排单测（Mockito 风格，照 GalleryServiceTest 惯例）：
 * - listener 条件矩阵：开关（D7）/类型未映射/活跃跳过/total=0/正常提交（DOWNLOAD_AUTO）；
 * - scheduler：间隔未到不跑、到点跑且写 last_scan（D9）、每轮至多 3 个 gallery（防雪崩）；
 * - 启动对账：@PostConstruct 调 store.markInterruptedAtStartup()，失败不阻断启动。
 */
class ProcessingAutomationTest {

    @TempDir
    lateinit var tempDir: Path

    // ------------------------------------------------------------------
    // 下载完成 listener（D7 条件矩阵）
    // ------------------------------------------------------------------

    @Test
    fun `listener ignores non-finished state`() {
        val service = mockService()
        val auto = automation(service = service)

        auto.onDownloadProgress(progress(gid = 1, state = 2, total = 10))

        verifyNoInteractions(service)
    }

    @Test
    fun `listener ignores zero-total events`() {
        val service = mockService()
        val auto = automation(service = service)

        auto.onDownloadProgress(progress(gid = 1, state = 3, total = 0))

        verifyNoInteractions(service)
    }

    @Test
    fun `listener skips when processing master switch is off`() {
        val service = mockService()
        val serverConfig = defaultServerConfig(enabled = false)
        val auto = automation(service = service, serverConfig = serverConfig)

        auto.onDownloadProgress(progress(gid = 1, state = 3, total = 10))

        verifyNoInteractions(service)
    }

    @Test
    fun `listener skips when automation switch is off`() {
        val service = mockService()
        val serverConfig = defaultServerConfig(automationEnabled = false)
        val auto = automation(service = service, serverConfig = serverConfig)

        auto.onDownloadProgress(progress(gid = 1, state = 3, total = 10))

        verifyNoInteractions(service)
    }

    @Test
    fun `listener skips unmapped default type`() {
        val service = mockService()
        val mapper = defaultMapper()
        `when`(mapper.resolve(ProcessingType.DENOISE)).thenReturn(null) // D5：无映射
        val serverConfig = defaultServerConfig(defaultType = "DENOISE")
        val auto = automation(service = service, serverConfig = serverConfig, mapper = mapper)

        auto.onDownloadProgress(progress(gid = 1, state = 3, total = 10))

        verifyNoInteractions(service)
    }

    @Test
    fun `listener skips unknown default type name`() {
        val service = mockService()
        val serverConfig = defaultServerConfig(defaultType = "WARP_DRIVE")
        val auto = automation(service = service, serverConfig = serverConfig)

        auto.onDownloadProgress(progress(gid = 1, state = 3, total = 10))

        verifyNoInteractions(service)
    }

    @Test
    fun `listener skips gallery with active task`() {
        val service = mockService()
        `when`(service.getActiveTasks()).thenReturn(listOf(taskStatus(galleryId = 99)))
        val auto = automation(service = service)

        auto.onDownloadProgress(progress(gid = 99, state = 3, total = 10))

        verify(service, never()).submitGallery(anyLong(), any(), any(), any(), anyBoolean())
    }

    @Test
    fun `listener submits whole gallery with DOWNLOAD_AUTO trigger and serverConfig options`() {
        val service = mockService()
        `when`(service.getActiveTasks()).thenReturn(emptyList())
        `when`(service.submitGallery(anyLong(), any(), any(), any(), anyBoolean())).thenReturn("proc-auto0001")
        val auto = automation(service = service)

        auto.onDownloadProgress(progress(gid = 42, state = 3, total = 5))

        val pages = ArgumentCaptor.forClass(IntRange::class.java)
        verify(service).submitGallery(
            eq(42L),
            captureK<IntRange>(pages),
            eq(ProcessingOptions(ProcessingType.UPSCALE_2X, outputFormat = "png", quality = 90)),
            eq(ProcessingTrigger.DOWNLOAD_AUTO),
            eq(false),
        )
        assertEquals(0, pages.value.first)
        assertEquals(4, pages.value.last)
    }

    @Test
    fun `listener never throws when submit fails`() {
        val service = mockService()
        `when`(service.getActiveTasks()).thenReturn(emptyList())
        `when`(service.submitGallery(anyLong(), any(), any(), any(), anyBoolean()))
            .thenThrow(IllegalStateException("No pages to process for gallery 1: all 3 page(s) already processed"))
        val auto = automation(service = service)

        assertDoesNotThrow { auto.onDownloadProgress(progress(gid = 1, state = 3, total = 3)) }
    }

    // ------------------------------------------------------------------
    // 定期补跑 scheduler（D9 间隔 + 防雪崩）
    // ------------------------------------------------------------------

    @Test
    fun `scheduler skips when periodic switch is off`() {
        val service = mockService()
        val repo = mock(DownloadInfoRepository::class.java)
        val serverConfig = defaultServerConfig(periodicEnabled = false, lastScan = 0L)
        val auto = automation(service = service, repo = repo, serverConfig = serverConfig)

        auto.periodicScan()

        verifyNoInteractions(repo)
        verify(serverConfig, never()).set(anyString(), anyString())
    }

    @Test
    fun `scheduler skips when interval has not elapsed`() {
        val service = mockService()
        val repo = mock(DownloadInfoRepository::class.java)
        val serverConfig = defaultServerConfig(lastScan = System.currentTimeMillis() - 30_000)
        val auto = automation(service = service, repo = repo, serverConfig = serverConfig)

        auto.periodicScan()

        verifyNoInteractions(repo)
        verify(serverConfig, never()).set(anyString(), anyString())
    }

    @Test
    fun `scheduler runs when interval elapsed and records lastScan before submitting`() {
        val service = mockService()
        `when`(service.getActiveTasks()).thenReturn(emptyList())
        `when`(service.submitGallery(anyLong(), any(), any(), any(), anyBoolean())).thenReturn("proc-scan0001")
        val store = mock(ProcessingTaskStore::class.java)
        val repo = mock(DownloadInfoRepository::class.java)
        `when`(repo.findByStateAndDeletedFalse(ProcessingAutomation.DOWNLOAD_STATE_FINISHED))
            .thenReturn(listOf(finishedRow(gid = 7, pages = 10)))
        val serverConfig = defaultServerConfig(lastScan = 0L)
        val auto = automation(service = service, store = store, repo = repo, serverConfig = serverConfig)

        auto.periodicScan()

        // lastScan=now 先写（执行前），随后提交 SCHEDULED 任务
        verify(serverConfig).set(eq(ServerConfigService.KEY_AUTOMATION_LAST_SCAN), anyString())
        val pages = ArgumentCaptor.forClass(IntRange::class.java)
        verify(service).submitGallery(
            eq(7L),
            captureK<IntRange>(pages),
            eq(ProcessingOptions(ProcessingType.UPSCALE_2X, outputFormat = "png", quality = 90)),
            eq(ProcessingTrigger.SCHEDULED),
            eq(false),
        )
        assertEquals(0, pages.value.first)
        assertEquals(9, pages.value.last)
    }

    @Test
    fun `scheduler submits at most 3 galleries per scan`() {
        val service = mockService()
        `when`(service.getActiveTasks()).thenReturn(emptyList())
        `when`(service.submitGallery(anyLong(), any(), any(), any(), anyBoolean())).thenReturn("proc-scan0001")
        val repo = mock(DownloadInfoRepository::class.java)
        `when`(repo.findByStateAndDeletedFalse(ProcessingAutomation.DOWNLOAD_STATE_FINISHED))
            .thenReturn((1L..5L).map { finishedRow(gid = it, pages = 2) })
        val auto = automation(service = service, repo = repo)

        auto.periodicScan()

        val gids = listOf(1L, 2L, 3L)
        verify(service, times(ProcessingAutomation.MAX_SUBMITS_PER_SCAN))
            .submitGallery(anyLong(), any(), any(), any(), anyBoolean())
        gids.forEach { gid ->
            verify(service).submitGallery(eq(gid), any(), any(), any(), anyBoolean())
        }
        // 防雪崩：第 4、5 个 gallery 本轮不得提交
        verify(service, never()).submitGallery(eq(4L), any(), any(), any(), anyBoolean())
        verify(service, never()).submitGallery(eq(5L), any(), any(), any(), anyBoolean())
    }

    @Test
    fun `scheduler skips galleries with no missing pages`() {
        val service = mockService()
        val store = mock(ProcessingTaskStore::class.java)
        `when`(store.findCompletedPages(eq(7L), eq(ProcessingType.UPSCALE_2X))).thenReturn(setOf(0, 1))
        val repo = mock(DownloadInfoRepository::class.java)
        `when`(repo.findByStateAndDeletedFalse(ProcessingAutomation.DOWNLOAD_STATE_FINISHED))
            .thenReturn(listOf(finishedRow(gid = 7, pages = 2)))
        val auto = automation(service = service, store = store, repo = repo)

        auto.periodicScan()

        verify(service, never()).submitGallery(anyLong(), any(), any(), any(), anyBoolean())
    }

    @Test
    fun `scheduler skips gallery that already has an active task`() {
        val service = mockService()
        `when`(service.getActiveTasks()).thenReturn(listOf(taskStatus(galleryId = 7)))
        val repo = mock(DownloadInfoRepository::class.java)
        `when`(repo.findByStateAndDeletedFalse(ProcessingAutomation.DOWNLOAD_STATE_FINISHED))
            .thenReturn(listOf(finishedRow(gid = 7, pages = 2)))
        val auto = automation(service = service, repo = repo)

        auto.periodicScan()

        verify(service, never()).submitGallery(anyLong(), any(), any(), any(), anyBoolean())
    }

    @Test
    fun `scheduler enhanced-artifact presence counts as processed`() {
        val service = mockService()
        val store = mock(ProcessingTaskStore::class.java)
        `when`(store.findCompletedPages(eq(7L), eq(ProcessingType.UPSCALE_2X))).thenReturn(emptySet())
        val repo = mock(DownloadInfoRepository::class.java)
        `when`(repo.findByStateAndDeletedFalse(ProcessingAutomation.DOWNLOAD_STATE_FINISHED))
            .thenReturn(listOf(finishedRow(gid = 7, pages = 2)))
        val config = SiteCoreConfigProperties().apply { download.cachePath = tempDir.toString() }
        // enhanced 产物已在磁盘 → 两页都视为已处理
        val enhancedDir = Files.createDirectories(tempDir.resolve("enhanced").resolve("7"))
        Files.writeString(enhancedDir.resolve("0.png"), "e0")
        Files.writeString(enhancedDir.resolve("1.png"), "e1")
        val auto = automation(service = service, store = store, repo = repo, config = config)

        auto.periodicScan()

        verify(service, never()).submitGallery(anyLong(), any(), any(), any(), anyBoolean())
    }

    @Test
    fun `scheduler never throws when a gallery submit fails`() {
        val service = mockService()
        `when`(service.getActiveTasks()).thenReturn(emptyList())
        `when`(service.submitGallery(anyLong(), any(), any(), any(), anyBoolean()))
            .thenThrow(IllegalStateException("No pages to process for gallery 7"))
        val repo = mock(DownloadInfoRepository::class.java)
        `when`(repo.findByStateAndDeletedFalse(ProcessingAutomation.DOWNLOAD_STATE_FINISHED))
            .thenReturn(listOf(finishedRow(gid = 7, pages = 2)))
        val auto = automation(service = service, repo = repo)

        assertDoesNotThrow { auto.periodicScan() }
    }

    @Test
    fun `scheduler estimates page count from download dir index when available`() {
        val service = mockService()
        `when`(service.getActiveTasks()).thenReturn(emptyList())
        `when`(service.submitGallery(anyLong(), any(), any(), any(), anyBoolean())).thenReturn("proc-scan0002")
        val store = mock(ProcessingTaskStore::class.java)
        `when`(store.findCompletedPages(eq(9L), eq(ProcessingType.UPSCALE_2X))).thenReturn(emptySet())
        val repo = mock(DownloadInfoRepository::class.java)
        `when`(repo.findByStateAndDeletedFalse(ProcessingAutomation.DOWNLOAD_STATE_FINISHED))
            .thenReturn(listOf(finishedRow(gid = 9, pages = 99)))
        val dirIndex = mock(DownloadDirIndex::class.java)
        `when`(dirIndex.pageCount(9L)).thenReturn(4)
        val auto = automation(service = service, store = store, repo = repo, dirIndex = dirIndex)

        auto.periodicScan()

        // 磁盘索引页数（4）优先于下载行快照（99）
        val pages = ArgumentCaptor.forClass(IntRange::class.java)
        verify(service).submitGallery(eq(9L), captureK<IntRange>(pages), any(), any(), anyBoolean())
        assertEquals(0, pages.value.first)
        assertEquals(3, pages.value.last)
    }

    // ------------------------------------------------------------------
    // 启动对账（手册 §5.4）
    // ------------------------------------------------------------------

    @Test
    fun `startup reconciliation marks interrupted rows once`() {
        val store = mock(ProcessingTaskStore::class.java)
        val auto = automation(store = store)

        auto.reconcileAtStartup()

        verify(store).markInterruptedAtStartup()
    }

    @Test
    fun `startup reconciliation failure does not throw`() {
        val store = mock(ProcessingTaskStore::class.java)
        doThrow(RuntimeException("db locked")).`when`(store).markInterruptedAtStartup()
        val auto = automation(store = store)

        assertDoesNotThrow { auto.reconcileAtStartup() }
    }

    // ------------------------------------------------------------------
    // 桩与夹具
    // ------------------------------------------------------------------

    private fun automation(
        service: ImageProcessingService = mockService(),
        store: ProcessingTaskStore = mock(ProcessingTaskStore::class.java),
        serverConfig: ServerConfigService = defaultServerConfig(),
        repo: DownloadInfoRepository = mock(DownloadInfoRepository::class.java),
        config: SiteCoreConfigProperties = SiteCoreConfigProperties()
            .apply { download.cachePath = tempDir.toString() },
        mapper: CapabilityMapper = defaultMapper(),
        dirIndex: DownloadDirIndex? = null,
    ): ProcessingAutomation =
        ProcessingAutomation(service, store, serverConfig, repo, config, mapper, dirIndex)

    private fun mockService(): ImageProcessingService = mock(ImageProcessingService::class.java)

    /** 常规放行配置；个别开关可用参覆盖。 */
    private fun defaultServerConfig(
        enabled: Boolean = true,
        automationEnabled: Boolean = true,
        periodicEnabled: Boolean = true,
        defaultType: String = ProcessingAutomation.DEFAULT_TYPE,
        lastScan: Long = 0L,
    ): ServerConfigService = mock(ServerConfigService::class.java).also { sc ->
        `when`(sc.getBoolean(ProcessingAutomation.PROCESSING_ENABLED_KEY, false)).thenReturn(enabled)
        `when`(sc.getBoolean(ServerConfigService.KEY_AUTOMATION_ENABLED, false)).thenReturn(automationEnabled)
        `when`(sc.getBoolean(ServerConfigService.KEY_PERIODIC_ENABLED, false)).thenReturn(periodicEnabled)
        `when`(sc.get(ProcessingAutomation.KEY_DEFAULT_TYPE, ProcessingAutomation.DEFAULT_TYPE)).thenReturn(defaultType)
        `when`(sc.get(ProcessingAutomation.KEY_OUTPUT_FORMAT, ProcessingAutomation.DEFAULT_OUTPUT_FORMAT))
            .thenReturn("png")
        `when`(sc.get(ProcessingAutomation.KEY_OUTPUT_QUALITY, ProcessingAutomation.DEFAULT_OUTPUT_QUALITY))
            .thenReturn("90")
        `when`(sc.getLong(ServerConfigService.KEY_PERIODIC_INTERVAL, ProcessingAutomation.DEFAULT_INTERVAL_MINUTES))
            .thenReturn(60L)
        `when`(sc.getLong(ServerConfigService.KEY_AUTOMATION_LAST_SCAN, 0L)).thenReturn(lastScan)
    }

    /** UPSCALE_2X 已映射（D5 默认表）；其余类型缺省 null。 */
    private fun defaultMapper(): CapabilityMapper = mock(CapabilityMapper::class.java).also { mapper ->
        `when`(mapper.resolve(ProcessingType.UPSCALE_2X))
            .thenReturn(CapabilityMapper.Mapping("realesr", "upscale", mapOf("scale_factor" to 2)))
    }

    private fun progress(gid: Long, state: Int, total: Int) = DownloadProgress(
        gid = gid, state = state, downloaded = total, total = total, speed = 0, label = 0,
    )

    private fun finishedRow(gid: Long, pages: Int): DownloadInfoEntity = DownloadInfoEntity().apply {
        this.gid = gid
        this.state = ProcessingAutomation.DOWNLOAD_STATE_FINISHED
        this.pages = pages
        this.total = pages
        this.done = pages
        this.deleted = false
        this.title = "gallery $gid"
    }

    private fun taskStatus(galleryId: Long) = ProcessingTaskStatus(
        taskId = "proc-active01",
        galleryId = galleryId,
        state = TaskState.PROCESSING,
        totalPages = 1,
        processedPages = 0,
        failedPages = 0,
        currentPage = 0,
        startedAt = java.time.Instant.now(),
        completedAt = null,
        error = null,
    )
}
