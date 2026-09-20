package com.hippo.anotherviewer.web.service

import com.hippo.anotherviewer.web.any
import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.entity.DownloadInfoEntity
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.repository.DownloadLabelRepository
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicLong

/**
 * P-S8 进度批量在真实下载链路上的接线验证（mock 仓库 + MockWebServer 假图源，
 * 单页最小管线放大到 10 页）：连续 10 页的进度只产生**一次**批量落库（终端
 * flush），外加三次生命周期写（state=1 开始、state=2 运行、state=3 完成）——
 * 对比旧实现的每页一次 findById+save。终态后待写清空，重复 flush 幂等。
 */
class DownloadProgressBatchServiceTest {

    @TempDir
    lateinit var tempDir: java.io.File

    private lateinit var downloadRepository: DownloadInfoRepository
    private lateinit var galleryLookup: GalleryLookupService
    private lateinit var sessionManager: SiteSessionManager
    private lateinit var availability: EhAvailabilityService
    private lateinit var persister: DownloadProgressPersister
    private lateinit var service: DownloadService
    private val clockMs = AtomicLong(1_000L)

    /** 已 shutdown 的调度器：直构单测禁用后台 tick，断言全确定性。 */
    private val noScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor().apply {
        shutdownNow()
    }

    @BeforeEach
    fun setUp() {
        downloadRepository = mock(DownloadInfoRepository::class.java)
        galleryLookup = mock(GalleryLookupService::class.java)
        sessionManager = mock(SiteSessionManager::class.java)
        availability = mock(EhAvailabilityService::class.java)
        persister = DownloadProgressPersister(
            downloadRepository,
            clock = { clockMs.get() },
            intervalMs = 1_000L,
            scheduler = noScheduler,
        )
        service = DownloadService(
            downloadRepository,
            mock(DownloadLabelRepository::class.java),
            SiteCoreConfigProperties().apply {
                download.path = java.io.File(tempDir, "downloads").absolutePath
            },
            mock(ApplicationEventPublisher::class.java),
            mock(ImageCacheService::class.java),
            sessionManager,
            galleryLookup,
            mock(ServerConfigService::class.java),
            availability,
            mock(DownloadDirIndex::class.java),
            mock(com.hippo.anotherviewer.web.repository.HistoryInfoRepository::class.java),
            stubProvider("test-user"),
            mock(PageFileHashRepository::class.java),
            null,
            persister,
        )
    }

    /** A7-1: 纯 Mockito 单测不碰 SecurityContext——注入固定用户的 Provider stub。 */
    private fun stubProvider(name: String): com.hippo.anotherviewer.web.config.CurrentUsernameProvider =
        mock(com.hippo.anotherviewer.web.config.CurrentUsernameProvider::class.java)
            .apply { `when`(currentUsername()).thenReturn(name) }

    private fun awaitUntil(timeoutMs: Long = 15_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun `ten page download persists progress once via the terminal batched flush`() {
        val pages = 10
        // 页字节必须过 V 门（一期完整性钩子）——用 Wave 0 classpath 的合法 JPEG。
        val validJpg = javaClass.getResourceAsStream("/integrity/valid.jpg")!!.readBytes()
        val server = MockWebServer()
        repeat(pages) { server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(validJpg))) }
        server.start()
        try {
            val row = DownloadInfoEntity().apply {
                id = 1L
                gid = 42L
                token = "tok"
                title = "T"
                state = 0
                downloadDir = java.io.File(java.io.File(tempDir, "downloads"), "42").absolutePath
            }
            `when`(downloadRepository.findById(1L)).thenReturn(Optional.of(row))
            `when`(downloadRepository.save(any(DownloadInfoEntity::class.java))).thenAnswer { it.getArgument(0) }
            `when`(availability.isBlocked()).thenReturn(false)
            `when`(galleryLookup.fetchPageCount(42L, "tok")).thenReturn(pages)
            `when`(
                galleryLookup.fetchImageUrl(
                    com.hippo.anotherviewer.web.eq(42L),
                    com.hippo.anotherviewer.web.eq("tok"),
                    org.mockito.ArgumentMatchers.anyInt(),
                )
            ).thenReturn(server.url("/p.jpg").toString())
            `when`(sessionManager.okHttpClient).thenReturn(OkHttpClient())

            assertTrue(service.startDownload(1L))
            awaitUntil { row.state == 3 }

            // 终态语义原样：10/10 完成。
            assertEquals(3, row.state)
            assertEquals(pages, row.done)

            // 全程落库次数（对同一行实例）：开始 state=1 + 运行 state=2 + 终端批量
            // flush（10 页进度合并成这一次）+ 完成 state=3 —— 恰好 4 次，而不是
            // 旧实现的每页一次（10 次进度写）。
            verify(downloadRepository, times(4)).save(row)

            // 终端 flush 后待写清空：重复 flush 幂等，绝无第 5 次 save。
            persister.flush(1L)
            verify(downloadRepository, times(4)).save(row)
        } finally {
            server.shutdown()
        }
    }
}
