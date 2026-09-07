package com.hippo.anotherviewer.web.config

import com.hippo.anotherviewer.web.service.DownloadService
import com.hippo.anotherviewer.web.service.ServerConfigService
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Merges persisted settings (download/cache paths + download numeric group)
 * from [ServerConfigService] into [SiteCoreConfigProperties] at startup, so
 * values set via the settings UI survive restarts. Persisted values win over
 * the defaults derived from `anotherviewer.data-dir`; 缺失/空白/解析失败的键
 * 跳过，保留 yml 默认值。
 */
@Component
class SiteDataDirInitializer(
    private val config: SiteCoreConfigProperties,
    private val serverConfig: ServerConfigService,
    private val downloadService: DownloadService,
) {

    @PostConstruct
    fun applyPersistedPaths() {
        serverConfig.get(ServerConfigService.KEY_DOWNLOAD_PATH)
            .takeIf { it.isNotBlank() }
            ?.let { config.download.path = it }
        serverConfig.get(ServerConfigService.KEY_CACHE_PATH)
            .takeIf { it.isNotBlank() }
            ?.let { config.download.cachePath = it }
        applyPersistedInt(ServerConfigService.KEY_DOWNLOAD_DELAY) { config.download.downloadDelay = it }
        applyPersistedLong(ServerConfigService.KEY_DOWNLOAD_TIMEOUT) { config.download.downloadTimeout = it }
        applyPersistedInt(ServerConfigService.KEY_MAX_CONCURRENT_GALLERIES) {
            config.download.maxConcurrentGalleries = it
        }
        applyPersistedInt(ServerConfigService.KEY_MAX_CONCURRENT_IMAGES) {
            config.download.maxConcurrentImages = it
        }
        applyPersistedLong(ServerConfigService.KEY_CACHE_SIZE_MB) { config.download.cacheSizeMb = it }
        // workerPool 容量在 DownloadService 构造期固化，而 Bean 初始化顺序不保证
        // 本类先行——回喂后显式对齐一次（越界持久化值在此被钳回 1..20）。
        downloadService.applyGalleryConcurrency(config.download.maxConcurrentGalleries)
    }

    /** 数值回喂防御：空白/非数字一律跳过（保留 yml 默认），绝不让启动失败。 */
    private fun applyPersistedInt(key: String, setter: (Int) -> Unit) {
        serverConfig.get(key).trim().toIntOrNull()?.let(setter)
    }

    private fun applyPersistedLong(key: String, setter: (Long) -> Unit) {
        serverConfig.get(key).trim().toLongOrNull()?.let(setter)
    }
}
