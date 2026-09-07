package com.hippo.anotherviewer.web.service

import com.hippo.anotherviewer.web.any
import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.dto.CacheSettingsUpdate
import com.hippo.anotherviewer.web.dto.DownloadSettingsUpdate
import com.hippo.anotherviewer.web.dto.SettingsUpdateRequest
import com.hippo.anotherviewer.web.eq
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/**
 * F7/F8：下载数值组经 serverConfig 落盘 + 画廊并发即时对齐 worker pool。
 * 路径双键（download.path / cache.path）既有行为不变，仅锁数值组新管线。
 */
class SettingsServiceTest {

    private lateinit var config: SiteCoreConfigProperties
    private lateinit var serverConfig: ServerConfigService
    private lateinit var downloadService: DownloadService
    private lateinit var service: SettingsService

    @BeforeEach
    fun setUp() {
        config = SiteCoreConfigProperties()
        serverConfig = mock(ServerConfigService::class.java)
        downloadService = mock(DownloadService::class.java)
        service = SettingsService(config, serverConfig, downloadService)
    }

    @Test
    fun `updateSettings persists the download numeric group and cache size`() {
        service.updateSettings(
            SettingsUpdateRequest(
                download = DownloadSettingsUpdate(
                    downloadDelay = 250,
                    downloadTimeout = 45_000L,
                    maxConcurrentGalleries = 6,
                    maxConcurrentImages = 4,
                ),
                cache = CacheSettingsUpdate(sizeMb = 20_480L),
            )
        )

        verify(serverConfig).set(ServerConfigService.KEY_DOWNLOAD_DELAY, "250")
        verify(serverConfig).set(ServerConfigService.KEY_DOWNLOAD_TIMEOUT, "45000")
        verify(serverConfig).set(ServerConfigService.KEY_MAX_CONCURRENT_GALLERIES, "6")
        verify(serverConfig).set(ServerConfigService.KEY_MAX_CONCURRENT_IMAGES, "4")
        verify(serverConfig).set(ServerConfigService.KEY_CACHE_SIZE_MB, "20480")
        // 内存同步生效，供 GET 与 worker 读取。
        assertEquals(250, config.download.downloadDelay)
        assertEquals(45_000L, config.download.downloadTimeout)
        assertEquals(6, config.download.maxConcurrentGalleries)
        assertEquals(4, config.download.maxConcurrentImages)
        assertEquals(20_480L, config.download.cacheSizeMb)
    }

    @Test
    fun `changing maxConcurrentGalleries realigns the worker pool after persisting`() {
        service.updateSettings(
            SettingsUpdateRequest(download = DownloadSettingsUpdate(maxConcurrentGalleries = 7))
        )

        verify(downloadService).applyGalleryConcurrency(7)
    }

    @Test
    fun `partial update without gallery concurrency does not touch the worker pool`() {
        service.updateSettings(
            SettingsUpdateRequest(download = DownloadSettingsUpdate(downloadDelay = 100))
        )

        // Int 是原始类型参数，匹配器用 Mockito 的 anyInt()（返回 0，无拆箱空指针）。
        verify(downloadService, never()).applyGalleryConcurrency(org.mockito.ArgumentMatchers.anyInt())
        verify(serverConfig, never()).set(
            eq(ServerConfigService.KEY_MAX_CONCURRENT_GALLERIES),
            any<String>(),
        )
    }
}
