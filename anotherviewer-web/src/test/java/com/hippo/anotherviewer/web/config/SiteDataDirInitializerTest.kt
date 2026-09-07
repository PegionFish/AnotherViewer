package com.hippo.anotherviewer.web.config

import com.hippo.anotherviewer.web.service.DownloadService
import com.hippo.anotherviewer.web.service.ServerConfigService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * F7：启动回喂——持久化的下载数值组合并进 SiteCoreConfigProperties；
 * 缺失/空白/非数字键跳过（保留 yml 默认），并把画廊并发对齐到 worker pool。
 */
class SiteDataDirInitializerTest {

    private lateinit var config: SiteCoreConfigProperties
    private lateinit var serverConfig: ServerConfigService
    private lateinit var downloadService: DownloadService
    private lateinit var initializer: SiteDataDirInitializer

    @BeforeEach
    fun setUp() {
        config = SiteCoreConfigProperties()
        serverConfig = mock(ServerConfigService::class.java)
        downloadService = mock(DownloadService::class.java)
        initializer = SiteDataDirInitializer(config, serverConfig, downloadService)
        // 未配置的键返回空串（ServerConfigService.get 的缺省行为）。
        // get 有默认参数，mock 看到的是双参调用——匹配器必须配满两个。
        `when`(serverConfig.get(anyString(), anyString())).thenReturn("")
    }

    @Test
    fun `persisted numeric group overrides yml defaults and realigns the pool`() {
        stub(ServerConfigService.KEY_DOWNLOAD_PATH, "/srv/downloads")
        stub(ServerConfigService.KEY_CACHE_PATH, "/srv/cache")
        stub(ServerConfigService.KEY_DOWNLOAD_DELAY, "300")
        stub(ServerConfigService.KEY_DOWNLOAD_TIMEOUT, "90000")
        stub(ServerConfigService.KEY_MAX_CONCURRENT_GALLERIES, "5")
        stub(ServerConfigService.KEY_MAX_CONCURRENT_IMAGES, "8")
        stub(ServerConfigService.KEY_CACHE_SIZE_MB, "4096")

        initializer.applyPersistedPaths()

        assertEquals("/srv/downloads", config.download.path)
        assertEquals("/srv/cache", config.download.cachePath)
        assertEquals(300, config.download.downloadDelay)
        assertEquals(90_000L, config.download.downloadTimeout)
        assertEquals(5, config.download.maxConcurrentGalleries)
        assertEquals(8, config.download.maxConcurrentImages)
        assertEquals(4_096L, config.download.cacheSizeMb)
        // 池容量在 DownloadService 构造期固化，回喂后必须对齐一次。
        verify(downloadService).applyGalleryConcurrency(5)
    }

    @Test
    fun `blank or garbage values are skipped keeping the yml defaults`() {
        stub(ServerConfigService.KEY_DOWNLOAD_DELAY, "  ")
        stub(ServerConfigService.KEY_DOWNLOAD_TIMEOUT, "not-a-number")
        stub(ServerConfigService.KEY_MAX_CONCURRENT_GALLERIES, "1.5")
        stub(ServerConfigService.KEY_MAX_CONCURRENT_IMAGES, "")
        stub(ServerConfigService.KEY_CACHE_SIZE_MB, "99999999999999999999999")

        initializer.applyPersistedPaths()

        assertEquals(0, config.download.downloadDelay)
        assertEquals(60_000L, config.download.downloadTimeout)
        assertEquals(3, config.download.maxConcurrentGalleries)
        assertEquals(3, config.download.maxConcurrentImages)
        assertEquals(10_240L, config.download.cacheSizeMb)
    }

    private fun stub(key: String, value: String) {
        // 显式传第二参（与初始化器经默认参数传入的 "" 一致），避免 matcher/raw 混用。
        `when`(serverConfig.get(key, "")).thenReturn(value)
    }
}
