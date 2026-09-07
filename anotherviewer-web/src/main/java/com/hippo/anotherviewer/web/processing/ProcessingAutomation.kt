package com.hippo.anotherviewer.web.processing

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.dto.DownloadProgress
import com.hippo.anotherviewer.web.entity.DownloadInfoEntity
import com.hippo.anotherviewer.web.processing.ep.CapabilityMapper
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.ServerConfigService
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * 处理自动化编排（W2 C4，手册 §2/§5/§11 D7/D9）：
 *
 * - **下载完成触发**：[onDownloadProgress] 监听 `DownloadProgress(state=3)`，
 *   总闸 = `processing.enabled` && `processing.automation_enabled`（D7）；默认类型
 *   取 `processing.default_type`，经 [CapabilityMapper.resolve] 无映射 → INFO 跳过；
 *   该 gid 已有活跃任务 → INFO 跳过；随后整页区间 submitGallery（DOWNLOAD_AUTO）。
 * - **定期补跑**：[periodicScan] 心跳（yml scan-interval-ms，默认 60s，D9），距
 *   serverConfig `processing.automation_last_scan` ≥ `periodic_interval_minutes`
 *   才执行，执行前先写 lastScan=now；扫下载库 state=3 存活行，逐 gallery 估算页数、
 *   算未处理页（store.findCompletedPages ∪ enhanced 文件存在），空缺才提交
 *   （SCHEDULED）；**每轮至多提交 3 个 gallery**（[MAX_SUBMITS_PER_SCAN]，防雪崩）。
 * - **启动对账**（手册 §5.4）：[reconcileAtStartup] 调 store.markInterruptedAtStartup()
 *   把遗留 PENDING/PROCESSING 行诚实标失败（EP_INTERRUPTED），补跑交给定时器。
 *
 * 线程上下文（A7/D17）：两个入口都跑在下载 worker / 调度线程——**不触碰
 * SecurityContext**；处理器整体吞异常（catch INFO/WARN），绝不毒化下载完成路径
 * 与调度线程。
 */
@Component
class ProcessingAutomation(
    private val service: ImageProcessingService,
    private val store: ProcessingTaskStore,
    private val serverConfig: ServerConfigService,
    private val downloadRepository: DownloadInfoRepository,
    private val config: SiteCoreConfigProperties,
    private val mapper: CapabilityMapper,
    /** App 推送下载目录索引（页数估算的最可靠来源）；测试可注入 mock/null。 */
    private val downloadDirIndex: DownloadDirIndex? = null,
) {
    private val logger = LoggerFactory.getLogger(ProcessingAutomation::class.java)

    /** 启动对账（手册 §5.4）：对账失败只 WARN，不得阻断应用启动。 */
    @PostConstruct
    fun reconcileAtStartup() {
        try {
            val interrupted = store.markInterruptedAtStartup()
            if (interrupted > 0) {
                logger.info(
                    "Processing startup reconciliation: {} interrupted task(s) marked FAILED/EP_INTERRUPTED",
                    interrupted,
                )
            }
        } catch (e: Exception) {
            logger.warn("Processing startup reconciliation failed (non-fatal): {}", e.message)
        }
    }

    /**
     * 下载完成（state==3）自动处理。DownloadService 的 worker 线程发布事件——
     * 本处理器整体吞异常（含 submitGallery 的 "No pages to process"），不毒化
     * 下载完成路径。
     */
    @EventListener
    fun onDownloadProgress(event: DownloadProgress) {
        try {
            if (event.state != DOWNLOAD_STATE_FINISHED) return
            if (event.total <= 0) return
            // D7 总闸：处理总开关 && 下载完成自动处理开关（并入一个键，不单设）。
            if (!serverConfig.getBoolean(PROCESSING_ENABLED_KEY, false)) return
            if (!serverConfig.getBoolean(ServerConfigService.KEY_AUTOMATION_ENABLED, false)) return
            val type = resolveDefaultType() ?: return
            if (service.getActiveTasks().any { it.galleryId == event.gid }) {
                logger.info("Processing automation: gallery {} already has an active task; skipping", event.gid)
                return
            }
            val options = defaultOptions(type)
            val taskId = service.submitGallery(
                galleryId = event.gid,
                pages = 0 until event.total,
                options = options,
                trigger = ProcessingTrigger.DOWNLOAD_AUTO,
                force = false,
            )
            logger.info(
                "Processing automation: gallery {} download finished → task {} ({}×{} pages)",
                event.gid, taskId, event.total, options.type,
            )
        } catch (e: Exception) {
            logger.info(
                "Processing automation: auto submit for gallery {} skipped ({}: {})",
                event.gid, e.javaClass.simpleName, e.message,
            )
        }
    }

    /**
     * 定期补跑心跳（D9）：fixedDelay 心跳便宜，真正触发频率由 serverConfig
     * last_scan + interval_minutes 控制。任何异常只 WARN，不毒化调度线程。
     */
    @Scheduled(fixedDelayString = "\${anotherviewer.processing.automation.scan-interval-ms:60000}")
    fun periodicScan() {
        try {
            if (!serverConfig.getBoolean(ServerConfigService.KEY_PERIODIC_ENABLED, false)) return
            val now = System.currentTimeMillis()
            // D9：间隔分钟数下限 15（手册 §4 settings 契约 min 15）。
            val intervalMinutes = serverConfig
                .getLong(ServerConfigService.KEY_PERIODIC_INTERVAL, DEFAULT_INTERVAL_MINUTES)
                .coerceAtLeast(15)
            val lastScan = serverConfig.getLong(ServerConfigService.KEY_AUTOMATION_LAST_SCAN, 0L)
            if (now - lastScan < intervalMinutes * 60_000L) return
            // 执行前先写 lastScan=now：即使本轮中途抛错，下轮也要等完整 interval
            // （防连续失败时疯狂重扫）。
            serverConfig.set(ServerConfigService.KEY_AUTOMATION_LAST_SCAN, now.toString())
            scanAndSubmit()
        } catch (e: Exception) {
            logger.warn("Processing periodic scan failed (non-fatal): {}", e.message)
        }
    }

    /** 一轮补跑扫描：逐 gallery 提交，至多 [MAX_SUBMITS_PER_SCAN] 个（防雪崩）。 */
    private fun scanAndSubmit() {
        val type = resolveDefaultType() ?: return
        val options = defaultOptions(type)
        val finished = downloadRepository.findByStateAndDeletedFalse(DOWNLOAD_STATE_FINISHED)
        var submitted = 0
        for (row in finished) {
            if (submitted >= MAX_SUBMITS_PER_SCAN) break
            try {
                val gid = row.gid
                if (service.getActiveTasks().any { it.galleryId == gid }) continue
                val total = estimatePageCount(row)
                if (total <= 0) continue
                // D8：历史 COMPLETED 页 ∪ enhanced 文件已存在，任一命中即已处理；
                // 全部已处理 → 零提交（不产生空任务行）。
                val done = runCatching { store.findCompletedPages(gid, type) }
                    .onFailure {
                        logger.warn("Dedup lookup failed for gallery {} (fail-open): {}", gid, it.message)
                    }
                    .getOrDefault(emptySet())
                val hasGap = (0 until total).any { page ->
                    page !in done && !Files.exists(enhancedPageFile(gid, page, options.outputFormat))
                }
                if (!hasGap) continue
                val taskId = service.submitGallery(
                    galleryId = gid,
                    pages = 0 until total,
                    options = options,
                    trigger = ProcessingTrigger.SCHEDULED,
                    force = false,
                )
                submitted++
                logger.info(
                    "Processing periodic scan: gallery {} → task {} ({} pages, {} pending)",
                    gid, taskId, total, type,
                )
            } catch (e: Exception) {
                logger.info(
                    "Processing periodic scan: gallery {} skipped ({}: {})",
                    row.gid, e.javaClass.simpleName, e.message,
                )
            }
        }
    }

    /**
     * 默认处理类型（serverConfig `processing.default_type`）：枚举名未知或
     * [CapabilityMapper.resolve] 无映射 → INFO 跳过（D7，null = 不触发）。
     */
    private fun resolveDefaultType(): ProcessingType? {
        val name = serverConfig.get(KEY_DEFAULT_TYPE, DEFAULT_TYPE).trim()
        val type = runCatching { ProcessingType.valueOf(name) }.getOrNull()
        if (type == null) {
            logger.info("Processing automation: unknown default_type '{}'; skipping", name)
            return null
        }
        if (mapper.resolve(type) == null) {
            logger.info("Processing automation: default type {} has no EntryPoint mapping; skipping", type)
            return null
        }
        return type
    }

    /** 提交选项：类型 + serverConfig 的 output_format / output_quality。 */
    private fun defaultOptions(type: ProcessingType): ProcessingOptions = ProcessingOptions(
        type = type,
        outputFormat = serverConfig.get(KEY_OUTPUT_FORMAT, DEFAULT_OUTPUT_FORMAT).trim()
            .ifEmpty { DEFAULT_OUTPUT_FORMAT },
        quality = serverConfig.get(KEY_OUTPUT_QUALITY, DEFAULT_OUTPUT_QUALITY).trim()
            .toIntOrNull() ?: DEFAULT_QUALITY,
    )

    /**
     * 页数估算（手册 §5.2）：最可靠来源是磁盘上实际存在的推送页文件数
     * （[DownloadDirIndex.pageCount]——处理输入正是这些文件）；索引未命中回落
     * 下载行快照（GalleryInfoBase.pages 画廊页数，其次 total 下载进度总数）。
     */
    private fun estimatePageCount(row: DownloadInfoEntity): Int {
        val indexed = downloadDirIndex?.pageCount(row.gid) ?: 0
        return when {
            indexed > 0 -> indexed
            row.pages > 0 -> row.pages
            else -> row.total
        }
    }

    /** D18 产物路径：`{cachePath}/enhanced/{gid}/{page}.{format}`。 */
    private fun enhancedPageFile(gid: Long, page: Int, format: String): Path =
        Path.of(config.download.cachePath, "enhanced", gid.toString(), "$page.$format")

    companion object {
        /** DownloadProgress.state 的「下载完成」值（DownloadService 状态机）。 */
        const val DOWNLOAD_STATE_FINISHED = 3

        /**
         * 每轮定期扫描最多提交的 gallery 数——**写死防雪崩**（手册 §5.2/R4：
         * EntryPoint 模块内单 worker 串行，提交风暴只会堆队列）。
         */
        const val MAX_SUBMITS_PER_SCAN = 3

        const val PROCESSING_ENABLED_KEY = "processing.enabled"
        const val KEY_DEFAULT_TYPE = "processing.default_type"
        const val KEY_OUTPUT_FORMAT = "processing.output_format"
        const val KEY_OUTPUT_QUALITY = "processing.output_quality"

        const val DEFAULT_TYPE = "UPSCALE_2X"
        const val DEFAULT_OUTPUT_FORMAT = "png"
        const val DEFAULT_OUTPUT_QUALITY = "90"
        const val DEFAULT_QUALITY = 90

        /** D9：periodic_interval_minutes 默认值（分钟）。 */
        const val DEFAULT_INTERVAL_MINUTES = 60L
    }
}
