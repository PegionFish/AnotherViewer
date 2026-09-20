package com.hippo.anotherviewer.web.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.dto.CacheStatsResponse
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/** 全量清缓存结果：removed = 实际删除文件数，total = 清前磁盘文件数。 */
data class CacheClearOutcome(
    val removed: Long,
    val total: Long,
)

/**
 * Two-tier image cache: Caffeine (hot memory) + disk (persistent).
 *
 * Lookup flow:  memory → disk → miss (null)
 * Write flow:   write-through to both tiers
 *
 * Disk layout:
 *   {cachePath}/{galleryId}/{page}.{ext}   — gallery/page entries
 *   {cachePath}/_url/{sha256}              — legacy URL-keyed entries
 */
@Service
class ImageCacheService(
    private val config: SiteCoreConfigProperties,
) {
    private val logger = LoggerFactory.getLogger(ImageCacheService::class.java)

    companion object {
        private const val DEFAULT_MAX_MEMORY_ENTRIES = 200L
        /** 默认内存层字节上限（64MB）；可经 anotherviewer.download.cache-memory-mb 调整。 */
        private const val DEFAULT_MAX_MEMORY_MB = 64L
        private const val URL_DIR = "_url"

        /**
         * P-S9：写入序候选队列软上限。长期不触顶时队列只增不减（条目仅在驱逐
         * 弹出或全量重建时移除），5 万条 File 引用 ≈ 数 MB 量级封顶；溢出时丢弃
         * 最老候选，失去队列资格的文件交由漂移对账兜底（见 [reconcileDiskState]）。
         */
        private const val MAX_WRITE_QUEUE_ENTRIES = 50_000L

        /** P-S9：全量对账频控默认间隔（60s 内最多一次）。 */
        private const val DEFAULT_RECONCILE_MIN_INTERVAL_MS = 60_000L
    }

    private val maxMemoryEntries: Long get() = DEFAULT_MAX_MEMORY_ENTRIES

    /** MASTER-2026-08-22 P4：内存层字节上限（真实配置值，Metrics 与淘汰共用）。 */
    val maxMemoryBytes: Long get() = config.download.cacheMemoryMb * 1024L * 1024L
    private val maxDiskBytes: Long get() = config.download.cacheSizeMb * 1024L * 1024L
    private val cacheDir: File get() = File(config.download.cachePath)

    /** Hot memory tier — Caffeine with stats recording for hit/miss tracking.
     *
     * MASTER-2026-08-22 P4：按字节加权淘汰（此前仅按条目数 200 淘汰，单张巨图
     * 常驻堆、memorySizeBytes 只统计不设限）。注意 Caffeine 不允许 maximumSize
     * 与 maximumWeight 并存，字节上限即唯一硬界。 */
    private val memoryCache: Cache<String, ByteArray> = Caffeine.newBuilder()
        .maximumWeight(maxMemoryBytes)
        .weigher { _: String, value: ByteArray -> value.size.coerceAtLeast(1) }
        .recordStats()
        .removalListener<String, ByteArray> { _, value: ByteArray?, _: RemovalCause ->
            if (value != null) memorySizeBytes.addAndGet(-value.size.toLong())
        }
        .build()

    /** Running total of bytes on disk, seeded at startup by [init]. */
    private val diskSizeBytes = AtomicLong(0)

    /**
     * Running count of files on disk, seeded at startup by [init] and
     * maintained by every disk write/delete path — [getDiskEntryCount] reads
     * this counter instead of scanning the whole tree (G4: metrics from
     * 150-200ms back to ms-level).
     */
    private val diskEntryCount = AtomicLong(0)

    /** Running total of byte sizes of values currently held in the memory cache. */
    private val memorySizeBytes = AtomicLong(0)

    // ── P-S9 逐出计数化：写入序候选队列 + 漂移对账 ──────────────

    /**
     * 磁盘写入序候选队列：每次落盘入队尾，[evictDiskIfNeeded] 超限时从队头
     * 弹出驱逐——以写入序近似 LRU。条目对应文件已不存在（evictPage / 覆写
     * 残留旧位置 / 外部删除）时跳过并丢弃。内存受 [MAX_WRITE_QUEUE_ENTRIES] 软上限。
     */
    private val writeOrderQueue = ConcurrentLinkedDeque<File>()

    /** 队列近似长度：ConcurrentLinkedDeque.size 为 O(n)，用计数器做软上限判断。 */
    private val writeQueueApproxSize = AtomicLong(0)

    /** 上次全量对账时间戳（频控 + CAS 单飞；0 = 从未对账，首次允许立即对账）。 */
    private val lastReconcileAtMs = AtomicLong(0L)

    /** 对账最小间隔；生产保持默认 60s，包内测试可调小验证窗口、调大禁用对账。 */
    @Volatile
    internal var reconcileMinIntervalMs: Long = DEFAULT_RECONCILE_MIN_INTERVAL_MS

    // ── P-S9 测试 seam（internal 仅供包内验收断言） ─────────────

    /** collectFiles 全树扫描次数：验收"常规超限驱逐零全树扫描"的可观测证据。 */
    internal val fullScanCount = AtomicLong(0)

    /** 全量对账执行次数：验收"漂移对账触发且频控窗口内仅一次"。 */
    internal val reconcileCount = AtomicLong(0)

    /** P-S9 测试 seam：人为抬高磁盘字节计数制造漂移，验证对账路径。 */
    internal fun debugInflateDiskSizeBytes(delta: Long) {
        diskSizeBytes.addAndGet(delta)
    }

    // ── lifecycle ──────────────────────────────────────────────

    @PostConstruct
    fun init() {
        cacheDir.mkdirs()
        val files = collectFiles(cacheDir)
        diskSizeBytes.set(files.sumOf { it.length() })
        diskEntryCount.set(files.size.toLong())
        // P-S9：启动全量清点时按 lastModified 升序重建写入序候选队列。
        rebuildWriteOrderQueue(files)
        logger.info(
            "ImageCacheService initialised: cacheDir={}, files={}, diskSize={}MB, maxDisk={}MB, maxMemEntries={}",
            cacheDir.absolutePath,
            diskEntryCount.get(),
            diskSizeBytes.get() / (1024 * 1024),
            maxDiskBytes / (1024 * 1024),
            maxMemoryEntries,
        )
    }

    // ── URL-keyed API (backward-compatible) ────────────────────

    fun getCachedImage(url: String): ByteArray? {
        val key = urlKey(url)
        return getFromMemory(key) ?: getFromDisk(urlDiskPath(key))?.also { promoteToMemory(key, it) }
    }

    fun cacheImage(url: String, data: ByteArray) {
        val key = urlKey(url)
        putToMemory(key, data)
        putToDisk(urlDiskPath(key), data)
    }

    fun getOrFetch(url: String): ByteArray? = getCachedImage(url)

    // ── gallery/page-keyed API ─────────────────────────────────

    fun getCachedImageByKey(galleryId: Long, page: Int): ByteArray? {
        val key = pageKey(galleryId, page)
        getFromMemory(key)?.let { return it }

        val file = findPageFile(galleryId, page) ?: return null
        val data = file.readBytes()
        promoteToMemory(key, data)
        return data
    }

    fun cacheImageByKey(galleryId: Long, page: Int, data: ByteArray, extension: String) {
        val key = pageKey(galleryId, page)
        putToMemory(key, data)
        val ext = extension.removePrefix(".").ifEmpty { "jpg" }
        putToDisk(pageDiskPath(galleryId, page, ext), data)
    }

    /**
     * Locate the cached page file on disk (any extension) so callers can serve
     * it with the correct Content-Type. Returns null when not cached on disk.
     */
    fun findCachedPageFile(galleryId: Long, page: Int): File? = findPageFile(galleryId, page)

    /**
     * Locate an AI-enhanced page file under `{cachePath}/enhanced/{galleryId}/{page}.*`
     * produced by the processing pipeline. Returns null when no enhanced
     * version exists for the page.
     */
    fun getEnhancedImage(galleryId: Long, page: Int): File? {
        val dir = File(cacheDir, "enhanced/$galleryId")
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.firstOrNull { it.isFile && it.nameWithoutExtension == page.toString() }
    }

    // ── gallery management ─────────────────────────────────────

    /**
     * Delete all cached images (memory + disk) for [galleryId].
     * @return true if any data was removed.
     */
    fun clearGalleryCache(galleryId: Long): Boolean {
        var removed = false

        // Remove memory entries whose key starts with "{galleryId}:"
        val prefix = "$galleryId:"
        val keysToRemove = memoryCache.asMap().keys.filter { it.startsWith(prefix) }
        if (keysToRemove.isNotEmpty()) {
            memoryCache.invalidateAll(keysToRemove)
            removed = true
        }

        // Remove disk directory
        val dir = File(cacheDir, galleryId.toString())
        if (dir.isDirectory) {
            val files = collectFiles(dir)
            diskSizeBytes.addAndGet(-files.sumOf { it.length() })
            diskEntryCount.addAndGet(-files.size.toLong())
            dir.deleteRecursively()
            removed = true
        }

        return removed
    }

    // ── whole-cache management (backward-compatible) ───────────

    /**
     * 文件完整性 Wave 3（S7）：修复覆写后清掉**单页**的各层缓存，让阅读/下载
     * 路径立即看到 healed 后的池文件而非旧的损坏副本：
     *
     * - page-keyed 内存条目（`"{gid}:{apiPage}"`）；
     * - page-keyed 磁盘文件 `{cachePath}/{gid}/{apiPage}.*`；
     * - enhanced 派生文件 `{cachePath}/enhanced/{gid}/{apiPage}.*`；
     * - sourceUrls 对应的 URL-keyed 条目（内存 + `{cachePath}/_url/{sha256}`，
     *   下载管线 `cacheImage(url, bytes)` 留下的旧字节）。
     *
     * 边删边修正磁盘计数（与 clearGalleryCache 同款）。调用方通常在覆写后紧接
     * `cacheImage(newUrl, freshBytes)` 重灌 URL 条目。
     *
     * @param apiPage 阅读端点口径的 0-based 页号（pageKey 同口径）。
     * @return true if any data was removed.
     */
    fun evictPage(galleryId: Long, apiPage: Int, sourceUrls: List<String> = emptyList()): Boolean {
        var removed = false

        val key = pageKey(galleryId, apiPage)
        if (memoryCache.getIfPresent(key) != null) {
            memoryCache.invalidate(key)
            removed = true
        }

        findPageFile(galleryId, apiPage)?.let { file ->
            diskSizeBytes.addAndGet(-file.length())
            diskEntryCount.decrementAndGet()
            removed = file.delete() || removed
        }

        getEnhancedImage(galleryId, apiPage)?.let { file ->
            // P2-3：enhanced 派生文件同样在磁盘计数口径内（init 全树播种、
            // clearCache/evictDiskIfNeeded 全树清点），删除时必须同步递减，
            // 否则驱逐后计数与实际磁盘状态脱节。
            diskSizeBytes.addAndGet(-file.length())
            diskEntryCount.decrementAndGet()
            removed = file.delete() || removed
        }

        sourceUrls.forEach { url ->
            removed = evictUrlEntry(urlKey(url)) || removed
        }

        return removed
    }

    /** URL-keyed 单条清除（内存 + `_url/` 磁盘文件），边删边计数。 */
    private fun evictUrlEntry(key: String): Boolean {
        var removed = false
        if (memoryCache.getIfPresent(key) != null) {
            memoryCache.invalidate(key)
            removed = true
        }
        val disk = urlDiskPath(key)
        if (disk.isFile) {
            diskSizeBytes.addAndGet(-disk.length())
            diskEntryCount.decrementAndGet()
            removed = disk.delete() || removed
        }
        return removed
    }


    /**
     * 清空内存 + 磁盘两级缓存，边删边计数。
     *
     * @param handle 异步 Job 进度句柄（可选）：先 collectFiles 得 total，
     *   逐文件删除 processed++ 并上报 `progress("删除缓存文件", ...)`；
     *   同步调用（无 handle）保持原行为，仅返回计数。
     */
    fun clearCache(handle: JobService.JobHandle? = null): CacheClearOutcome {
        memoryCache.invalidateAll()
        var removed = 0L
        val files = if (cacheDir.isDirectory) collectFiles(cacheDir) else emptyList()
        val total = files.size.toLong()
        for (file in files) {
            if (file.delete()) removed++
            handle?.progress("删除缓存文件", removed, total)
        }
        // 清理删空后的目录（与旧 deleteRecursively 行为对齐）。
        cacheDir.listFiles()
            ?.filter { it.isDirectory && (it.listFiles()?.isEmpty() != false) }
            ?.forEach { it.delete() }
        // 重新归一两个运行值（删除失败的文件仍留在磁盘上）。
        val remaining = collectFiles(cacheDir)
        diskSizeBytes.set(remaining.sumOf { it.length() })
        diskEntryCount.set(remaining.size.toLong())
        // P-S9：清点后同步重建写入序队列，避免指向已删文件的陈旧条目滞留。
        rebuildWriteOrderQueue(remaining)
        return CacheClearOutcome(removed, total)
    }

    fun getCacheSize(): Int = memoryCache.asMap().size

    /** Number of files currently stored on disk (running counter, O(1)). */
    fun getDiskEntryCount(): Long = diskEntryCount.get()

    /** Total byte size of values currently held in the memory cache. */
    fun getMemorySizeBytes(): Long = memorySizeBytes.get()

    // ── stats ──────────────────────────────────────────────────

    fun getCacheStats(): CacheStatsResponse {
        val stats = memoryCache.stats()
        return CacheStatsResponse(
            diskCacheSizeBytes = diskSizeBytes.get(),
            diskCacheMaxBytes = maxDiskBytes,
            memoryCacheEntries = memoryCache.asMap().size,
            memoryCacheMaxEntries = maxMemoryEntries.toInt(),
            hitCount = stats.hitCount(),
            missCount = stats.missCount(),
            hitRate = stats.hitRate(),
        )
    }

    // ── memory helpers ─────────────────────────────────────────

    private fun getFromMemory(key: String): ByteArray? = memoryCache.getIfPresent(key)

    /**
     * MASTER-2026-08-22 P4：同步排空 Caffeine 待处理维护队列（按权重淘汰、
     * removal listener 回调均为惰性执行）。测试与需要精确 memorySizeBytes 的
     * 路径在读取前调用。
     */
    internal fun drainMaintenance() {
        memoryCache.cleanUp()
    }

    private fun promoteToMemory(key: String, data: ByteArray) {
        putToMemory(key, data)
    }

    private fun putToMemory(key: String, data: ByteArray) {
        memoryCache.put(key, data)
        memorySizeBytes.addAndGet(data.size.toLong())
    }

    // ── disk helpers ───────────────────────────────────────────

    private fun getFromDisk(file: File): ByteArray? {
        if (!file.isFile) return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            logger.warn("Failed to read disk cache file: {}", file, e)
            null
        }
    }

    private fun putToDisk(file: File, data: ByteArray) {
        try {
            file.parentFile?.mkdirs()
            // If overwriting, subtract old size first (entry count unchanged).
            val existed = file.isFile
            if (existed) {
                diskSizeBytes.addAndGet(-file.length())
            }
            file.writeBytes(data)
            diskSizeBytes.addAndGet(data.size.toLong())
            if (!existed) {
                diskEntryCount.incrementAndGet()
            }
            // P-S9：落盘成功即入写入序队列（覆写亦重新入队到队尾；旧位置条目
            // 留待弹出时按"文件已不存在"跳过，不影响计数正确性）。
            enqueueWriteCandidate(file)
            evictDiskIfNeeded()
        } catch (e: Exception) {
            logger.warn("Failed to write disk cache file: {}", file, e)
        }
    }

    /**
     * P-S9 逐出计数化：超限时从写入序候选队列队头弹出驱逐，不再全树扫描。
     *
     * 语义说明（与真实 lastModified LRU 的偏差）：
     * - 队列记录的是**写入序**（每次落盘入队尾）：以"最近写入"为新，读取命中
     *   不刷新新近度——与旧实现按 lastModified 排序的口径一致；差别在于覆写会
     *   重新入队到队尾（旧位置条目残留，弹出时按"文件已不存在"跳过），因此
     *   覆写后该文件的驱逐新近度与 lastModified 语义基本对齐。
     * - enhanced 派生文件由处理管线直写、不入队，仅在 init/对账全量扫描时进队。
     * - 队列耗尽仍超限 → 判定计数漂移（外部删除/直写/历史漏减等），触发频控
     *   全量对账 [reconcileDiskState]；对账被频控挡下时本轮直接放弃，留待下次
     *   落盘再试。
     *
     * 并发约定：pollFirst 原子弹出（多个驱逐线程不会处理同一候选）；仅当
     * delete() 返回 true 才递减计数（重复路径条目 / 竞态下不双扣）。与旧实现
     * 一样按 best-effort 处理，微小竞态导致的字节口径漂移由对账收敛。
     */
    private fun evictDiskIfNeeded() {
        if (diskSizeBytes.get() <= maxDiskBytes) return

        while (diskSizeBytes.get() > maxDiskBytes) {
            val candidate = pollWriteCandidate() ?: run {
                // 队列耗尽仍超限：计数漂移 → 频控全量对账（重置计数器 + 清空
                // 目录 + 空目录清理 + 重建队列）。
                reconcileDiskState()
                return
            }
            // 条目对应的文件已被 evictPage/覆写/外部删除 → 跳过并丢弃该条目。
            if (!candidate.isFile) continue
            val size = candidate.length()
            if (candidate.delete()) {
                diskSizeBytes.addAndGet(-size)
                diskEntryCount.decrementAndGet()
            }
        }
    }

    /**
     * P-S9 漂移对账：全量扫描 → 清空目录 → 重置计数器 → 按 lastModified 重建
     * 队列（与 init 同口径）。频控：[reconcileMinIntervalMs] 内最多一次，CAS 单飞
     * 保证多线程同时触发时只有一家执行。空目录清理随对账走（常规队列驱逐不再
     * 清理空目录）。并发写入增量的归一口径与 [clearCache] 一致：以扫描时的磁盘
     * 实际为准，由此引入的微小漂移由下次对账收敛。
     */
    private fun reconcileDiskState() {
        val now = System.currentTimeMillis()
        while (true) {
            val last = lastReconcileAtMs.get()
            if (now - last < reconcileMinIntervalMs) return
            if (lastReconcileAtMs.compareAndSet(last, now)) break
        }
        reconcileCount.incrementAndGet()
        logger.warn("Disk cache counter drift detected, running full reconciliation on {}", cacheDir.absolutePath)
        val files = if (cacheDir.isDirectory) collectFiles(cacheDir) else emptyList()
        for (file in files) {
            file.delete()
        }
        // 清理删空后的目录（与 clearCache 同款）。
        cacheDir.listFiles()
            ?.filter { it.isDirectory && (it.listFiles()?.isEmpty() != false) }
            ?.forEach { it.delete() }
        // 重新归一两个运行值（删除失败的文件仍留在磁盘上）。
        val remaining = if (cacheDir.isDirectory) collectFiles(cacheDir) else emptyList()
        diskSizeBytes.set(remaining.sumOf { it.length() })
        diskEntryCount.set(remaining.size.toLong())
        rebuildWriteOrderQueue(remaining)
    }

    /** 落盘成功后入写入序队列尾；超过软上限时丢弃最老候选（内存封顶）。 */
    private fun enqueueWriteCandidate(file: File) {
        writeOrderQueue.addLast(file)
        if (writeQueueApproxSize.incrementAndGet() > MAX_WRITE_QUEUE_ENTRIES) {
            pollWriteCandidate()
        }
    }

    /** 原子弹出队头候选（pollFirst 原子性保证多驱逐线程不重复处理同一条目）。 */
    private fun pollWriteCandidate(): File? =
        writeOrderQueue.pollFirst()?.also { writeQueueApproxSize.decrementAndGet() }

    /** 清空并按 lastModified 升序重建写入序候选队列（init / 对账 / clearCache 共用）。 */
    private fun rebuildWriteOrderQueue(files: List<File>) {
        writeOrderQueue.clear()
        writeQueueApproxSize.set(0)
        files.sortedBy { it.lastModified() }.forEach { file ->
            writeOrderQueue.addLast(file)
            writeQueueApproxSize.incrementAndGet()
        }
    }

    // ── path / key helpers ─────────────────────────────────────

    private fun urlKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun pageKey(galleryId: Long, page: Int): String = "$galleryId:$page"

    private fun urlDiskPath(hash: String): File = File(cacheDir, "$URL_DIR/$hash")

    private fun pageDiskPath(galleryId: Long, page: Int, ext: String): File =
        File(cacheDir, "$galleryId/$page.$ext")

    /**
     * Find a page file on disk regardless of extension: `{cachePath}/{galleryId}/{page}.*`
     */
    private fun findPageFile(galleryId: Long, page: Int): File? {
        val dir = File(cacheDir, galleryId.toString())
        if (!dir.isDirectory) return null
        return dir.listFiles()?.firstOrNull { it.nameWithoutExtension == page.toString() && it.isFile }
    }

    private fun scanDiskSize(dir: File): Long {
        if (!dir.exists()) return 0
        return collectFiles(dir).sumOf { it.length() }
    }

    private fun collectFiles(dir: File): List<File> {
        // P-S9 测试 seam：全树扫描计数（驱逐路径应为 0 增量）。
        fullScanCount.incrementAndGet()
        val result = mutableListOf<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                } else {
                    result.add(child)
                }
            }
        }
        return result
    }
}
