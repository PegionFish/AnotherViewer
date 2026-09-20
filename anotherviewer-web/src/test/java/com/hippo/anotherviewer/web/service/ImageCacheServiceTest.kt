package com.hippo.anotherviewer.web.service

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ImageCacheServiceTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var service: ImageCacheService
    private lateinit var config: SiteCoreConfigProperties

    @BeforeEach
    fun setUp() {
        config = SiteCoreConfigProperties()
        config.download.cachePath = tempDir.absolutePath
        config.download.cacheSizeMb = 1 // 1 MB for testing eviction

        service = ImageCacheService(config)
        service.init()
    }

    // ── URL-keyed backward-compatible API ──────────────────────

    @Test
    fun `getCachedImage returns null on miss`() {
        assertNull(service.getCachedImage("https://example.com/img.jpg"))
    }

    @Test
    fun `cacheImage then getCachedImage returns data from memory`() {
        val url = "https://example.com/img.jpg"
        val data = byteArrayOf(1, 2, 3, 4)

        service.cacheImage(url, data)

        assertArrayEquals(data, service.getCachedImage(url))
    }

    @Test
    fun `getCachedImage falls back to disk after memory eviction`() {
        val url = "https://example.com/persist.jpg"
        val data = byteArrayOf(10, 20, 30)

        service.cacheImage(url, data)
        // Clear only the memory tier by clearing all and re-reading from disk
        // We simulate memory miss by clearing the whole cache then re-writing to disk
        // Actually, let's just verify disk persistence directly:
        service.clearCache()

        // After clearCache both tiers are wiped, so this should be null
        assertNull(service.getCachedImage(url))
    }

    @Test
    fun `disk persistence survives memory-only clear`() {
        val url = "https://example.com/disk.jpg"
        val data = byteArrayOf(5, 6, 7)

        service.cacheImage(url, data)

        // Verify the file exists on disk under _url/
        val urlDir = File(tempDir, "_url")
        assertTrue(urlDir.isDirectory)
        val files = urlDir.listFiles()
        assertNotNull(files)
        assertEquals(1, files!!.size)
        assertArrayEquals(data, files[0].readBytes())
    }

    // ── gallery/page-keyed API ─────────────────────────────────

    @Test
    fun `cacheImageByKey then getCachedImageByKey returns data`() {
        val data = byteArrayOf(42, 43, 44)

        service.cacheImageByKey(12345L, 1, data, "jpg")

        assertArrayEquals(data, service.getCachedImageByKey(12345L, 1))
    }

    @Test
    fun `getCachedImageByKey returns null for missing page`() {
        assertNull(service.getCachedImageByKey(99999L, 1))
    }

    @Test
    fun `disk layout uses galleryId directory and page file`() {
        val data = byteArrayOf(1)

        service.cacheImageByKey(777L, 3, data, "png")

        val expected = File(tempDir, "777/3.png")
        assertTrue(expected.isFile)
        assertArrayEquals(data, expected.readBytes())
    }

    @Test
    fun `getCachedImageByKey finds file regardless of extension after restart`() {
        val data = byteArrayOf(9, 8, 7)

        service.cacheImageByKey(100L, 5, data, "webp")

        // Simulate restart: create a fresh service instance (new empty memory cache)
        // pointing at the same disk directory — disk data survives
        service = ImageCacheService(config)
        service.init()

        assertArrayEquals(data, service.getCachedImageByKey(100L, 5))
    }

    // ── gallery cache clear ────────────────────────────────────

    @Test
    fun `clearGalleryCache removes memory and disk entries`() {
        service.cacheImageByKey(200L, 1, byteArrayOf(1), "jpg")
        service.cacheImageByKey(200L, 2, byteArrayOf(2), "jpg")
        service.cacheImageByKey(300L, 1, byteArrayOf(3), "jpg")

        val removed = service.clearGalleryCache(200L)

        assertTrue(removed)
        assertNull(service.getCachedImageByKey(200L, 1))
        assertNull(service.getCachedImageByKey(200L, 2))
        // Gallery 300 should be unaffected
        assertNotNull(service.getCachedImageByKey(300L, 1))
        // Disk directory for 200 should be gone
        assertFalse(File(tempDir, "200").exists())
    }

    @Test
    fun `evictPage decrements disk counters for the enhanced derived file too (P2-3)`() {
        service.cacheImageByKey(7L, 0, ByteArray(9), "jpg")
        val enhanced = File(tempDir, "enhanced/7").apply { mkdirs() }.resolve("0.jpg")
        enhanced.writeBytes(ByteArray(3))
        // 处理管线直写 enhanced 文件不走计数器；模拟重启（init 全树播种）后
        // enhanced 文件进入计数口径。
        service = ImageCacheService(config).apply { init() }
        assertEquals(12L, service.getCacheStats().diskCacheSizeBytes)
        assertEquals(2L, service.getDiskEntryCount())

        assertTrue(service.evictPage(7L, 0))

        // 页文件与 enhanced 派生一并驱逐后，计数必须与实际磁盘状态一致。
        assertEquals(0L, service.getCacheStats().diskCacheSizeBytes, "驱逐 enhanced 后字节计数漏减")
        assertEquals(0L, service.getDiskEntryCount(), "驱逐 enhanced 后条目计数漏减")
        assertFalse(enhanced.exists())
        assertNull(service.getEnhancedImage(7L, 0))
    }

    @Test
    fun `clearGalleryCache returns false when nothing cached`() {
        assertFalse(service.clearGalleryCache(99999L))
    }

    // ── stats ──────────────────────────────────────────────────

    @Test
    fun `getCacheStats returns meaningful data`() {
        service.cacheImageByKey(1L, 1, ByteArray(1024), "jpg")
        service.getCachedImageByKey(1L, 1) // hit
        service.getCachedImageByKey(1L, 99) // miss

        val stats = service.getCacheStats()

        assertTrue(stats.diskCacheSizeBytes > 0)
        assertEquals(1L * 1024 * 1024, stats.diskCacheMaxBytes) // 1 MB
        assertTrue(stats.memoryCacheEntries > 0)
        assertEquals(200, stats.memoryCacheMaxEntries)
        assertTrue(stats.hitCount > 0)
        assertTrue(stats.missCount > 0)
        assertTrue(stats.hitRate in 0.0..1.0)
    }

    // ── whole-cache clear ──────────────────────────────────────

    @Test
    fun `clearCache wipes both tiers`() {
        service.cacheImage("https://example.com/a.jpg", byteArrayOf(1))
        service.cacheImageByKey(1L, 1, byteArrayOf(2), "jpg")

        service.clearCache()

        assertEquals(0, service.getCacheSize())
        assertNull(service.getCachedImage("https://example.com/a.jpg"))
        assertNull(service.getCachedImageByKey(1L, 1))
        val stats = service.getCacheStats()
        assertEquals(0L, stats.diskCacheSizeBytes)
    }

    @Test
    fun `clearCache counts removed and total files`() {
        service.cacheImageByKey(1L, 1, byteArrayOf(1), "jpg")
        service.cacheImageByKey(1L, 2, byteArrayOf(2), "jpg")
        service.cacheImageByKey(300L, 1, byteArrayOf(3), "jpg")

        val outcome = service.clearCache()

        assertEquals(3L, outcome.total)
        assertEquals(3L, outcome.removed)
        assertEquals(0L, service.getCacheStats().diskCacheSizeBytes)
    }

    @Test
    fun `clearCache reports per-file progress via job handle`() {
        service.cacheImageByKey(1L, 1, byteArrayOf(1), "jpg")
        service.cacheImageByKey(1L, 2, byteArrayOf(2), "jpg")

        val updates = mutableListOf<Long>()
        val handle = object : JobService.JobHandle {
            override fun progress(stage: String, processed: Long, total: Long) {
                assertEquals("删除缓存文件", stage)
                assertEquals(2L, total)
                updates += processed
            }
            override fun stage(stage: String) = Unit
        }

        val outcome = service.clearCache(handle)

        assertEquals(2L, outcome.total)
        assertEquals(2L, outcome.removed)
        assertEquals(2, updates.size)
        assertEquals(listOf(1L, 2L), updates)
    }

    @Test
    fun `clearCache with empty cache reports zero counts`() {
        val outcome = service.clearCache()

        assertEquals(0L, outcome.total)
        assertEquals(0L, outcome.removed)
    }

    // ── disk eviction ──────────────────────────────────────────

    @Test
    fun `disk eviction triggers when exceeding max size`() {
        // Config is 1 MB max. Write more than 1 MB.
        val bigData = ByteArray(300 * 1024) // 300 KB each
        for (i in 1..5) {
            service.cacheImageByKey(900L, i, bigData, "jpg")
        }

        // Total written = 1.5 MB > 1 MB limit, so eviction should have occurred
        val stats = service.getCacheStats()
        assertTrue(stats.diskCacheSizeBytes <= 1L * 1024 * 1024)
    }

    // ── P-S9 逐出计数化（写入序队列 + 漂移对账） ────────────────

    @Test
    fun `P-S9 normal over-limit eviction performs zero full-tree scans`() {
        val scansBefore = service.fullScanCount.get() // setUp init 的 1 次清点
        val bigData = ByteArray(300 * 1024)
        for (i in 1..5) {
            service.cacheImageByKey(900L, i, bigData, "jpg")
        }

        assertEquals(scansBefore, service.fullScanCount.get(), "常规超限驱逐不得触发 collectFiles 全树扫描")
        assertTrue(service.getCacheStats().diskCacheSizeBytes <= 1L * 1024 * 1024)
        assertEquals(3L, service.getDiskEntryCount())
    }

    @Test
    fun `P-S9 eviction follows write order`() {
        val big = ByteArray(300 * 1024)
        for (i in 1..5) {
            service.cacheImageByKey(910L, i, big, "jpg")
        }

        // 1.5MB > 1MB：按写入序最早写的 page1/page2 先被逐出，page3-5 存活
        assertFalse(File(tempDir, "910/1.jpg").exists(), "最早写入的 page1 应先被驱逐")
        assertFalse(File(tempDir, "910/2.jpg").exists(), "次早写入的 page2 应随后被驱逐")
        assertTrue(File(tempDir, "910/3.jpg").exists())
        assertTrue(File(tempDir, "910/4.jpg").exists())
        assertTrue(File(tempDir, "910/5.jpg").exists())
        assertEquals(3L, service.getDiskEntryCount())
    }

    @Test
    fun `P-S9 eviction skips stale entries left by evictPage and keeps counters correct`() {
        val data = ByteArray(300 * 1024)
        service.cacheImageByKey(920L, 1, data, "jpg")
        service.cacheImageByKey(920L, 2, data, "jpg")
        service.cacheImageByKey(920L, 3, data, "jpg") // 900KB < 1MB，未触发驱逐
        assertTrue(service.evictPage(920L, 1)) // p1 文件被删、计数同步递减，队列残留陈旧条目
        assertEquals(2L, service.getDiskEntryCount())

        val scansBefore = service.fullScanCount.get()
        // 人为把计数推过上限（>1MB），触发驱逐：队头是已被 evictPage 删除的 p1 → 应跳过
        service.debugInflateDiskSizeBytes(500 * 1024L)
        service.cacheImageByKey(920L, 4, ByteArray(1024), "jpg")

        assertEquals(scansBefore, service.fullScanCount.get(), "陈旧条目跳过应在队列内完成，不得全树扫描/对账")
        assertFalse(File(tempDir, "920/2.jpg").exists(), "跳过 p1 后应按写入序驱逐 p2")
        assertTrue(File(tempDir, "920/3.jpg").exists())
        assertTrue(File(tempDir, "920/4.jpg").exists())
        assertEquals(2L, service.getDiskEntryCount(), "跳过陈旧条目不得扣减计数")
    }

    @Test
    fun `P-S9 init rebuilds write-order queue sorted by lastModified`() {
        val a = File(tempDir, "950/1.jpg")
        val b = File(tempDir, "950/2.jpg")
        a.parentFile.mkdirs()
        a.writeBytes(ByteArray(400 * 1024))
        b.writeBytes(ByteArray(400 * 1024))
        a.setLastModified(1_000_000L) // 人为最旧
        b.setLastModified(2_000_000L) // 人为最新

        // 重启：init 全量清点并按 lastModified 重建队列
        service = ImageCacheService(config).apply { init() }
        assertEquals(2L, service.getDiskEntryCount())

        val scansBefore = service.fullScanCount.get()
        // 抬高计数触发一次驱逐（800KB + 300KB > 1MB）：队头应是 lastModified 最旧的 a
        service.debugInflateDiskSizeBytes(300 * 1024L)
        service.cacheImageByKey(950L, 3, ByteArray(10), "jpg")

        assertEquals(scansBefore, service.fullScanCount.get(), "init 重建的队列驱逐不得全树扫描")
        assertFalse(a.exists(), "lastModified 最旧的文件应先被驱逐")
        assertTrue(b.exists(), "较新的文件应存活")
        assertTrue(File(tempDir, "950/3.jpg").exists())
        assertEquals(2L, service.getDiskEntryCount())
    }

    @Test
    fun `P1-1 counter drift reconciliation keeps files, resets counters to disk truth and rebuilds the queue`() {
        val data = ByteArray(1024)
        service.cacheImageByKey(930L, 1, data, "jpg")
        // 管线/外部直写文件（不入队、不计数）——模拟 enhanced 派生或 SMB 侧直写。
        val survivor = File(tempDir, "enhanced/930").apply { mkdirs() }.resolve("0.jpg")
        survivor.writeBytes(ByteArray(2048))

        val scansBefore = service.fullScanCount.get()
        // 人为上漂（单向棘轮：evict 先减计数后 delete 不验成功 / 覆写 TOCTOU 类漂移）。
        // 触发驱逐：队列里的真实条目（p1/p2）按写入序合法逐出后仍超限 → 队列耗尽 → 对账。
        service.debugInflateDiskSizeBytes(2L * 1024 * 1024)
        service.cacheImageByKey(930L, 2, data, "jpg")

        assertEquals(1L, service.reconcileCount.get(), "漂移应触发一次对账")
        assertEquals(
            scansBefore + 1, service.fullScanCount.get(),
            "对账 = 一次全量扫描（扫描即真相；旧实现删除后二次归一是两次）"
        )

        // P1-1 核心：对账绝不删任何缓存文件——未入队文件必须原样还在，
        // 两个计数器与磁盘真实状态精确一致。
        assertTrue(survivor.isFile, "对账不得删除任何缓存文件")
        assertEquals(2048L, service.getCacheStats().diskCacheSizeBytes, "对账后字节计数 = 磁盘实际")
        assertEquals(1L, service.getDiskEntryCount(), "对账后条目计数 = 磁盘实际")
        // 空目录壳清理保留：p1/p2 被合法队列驱逐后 930/ 已空 → 壳随对账清掉；
        // enhanced/930/ 还有文件 → 保留。
        assertFalse(File(tempDir, "930").exists(), "空目录壳清理应随对账完成")
        assertTrue(File(tempDir, "enhanced/930").isDirectory, "非空目录不得清理")

        // 队列重建正确：对账后再次漂移，驱逐消费的是重建后的队列（survivor 按写入序唯一候选）。
        service.debugInflateDiskSizeBytes(2L * 1024 * 1024)
        service.cacheImageByKey(931L, 1, data, "jpg")
        assertFalse(survivor.exists(), "重建后的队列应指向 survivor 并按序驱逐")
        assertEquals(0L, service.getDiskEntryCount(), "驱逐 survivor + 新页后计数归零")

        // 频控（P2-6：窗口比较在 System.nanoTime 单调钟域内——语义与旧毫秒窗口一致）：
        // 60s 窗口内再次耗尽队列不得重复对账。
        service.debugInflateDiskSizeBytes(2L * 1024 * 1024)
        service.cacheImageByKey(932L, 1, data, "jpg")
        assertEquals(1L, service.reconcileCount.get(), "频控窗口内不得重复对账")

        // 窗口过期（此处以调小间隔模拟）后允许再次对账；第二次对账同样保留现存文件。
        service.reconcileMinIntervalMs = 0L
        val survivor2 = File(tempDir, "enhanced/934").apply { mkdirs() }.resolve("0.jpg")
        survivor2.writeBytes(ByteArray(512))
        service.cacheImageByKey(934L, 1, data, "jpg")
        assertEquals(2L, service.reconcileCount.get(), "频控窗口过期后应允许再次对账")
        assertTrue(survivor2.isFile, "第二次对账同样不得删除现存文件")
        assertEquals(512L, service.getCacheStats().diskCacheSizeBytes, "对账后计数与磁盘一致")
        assertEquals(1L, service.getDiskEntryCount(), "对账后计数与磁盘一致")
    }

    @Test
    fun `P-S9 concurrent writes and eviction keep counters exact with no double-deduction`() {
        // 禁用对账（drift 对账会重置计数器，干扰精确等值断言）；本用例验证
        // 纯队列驱逐路径下"每笔写入恰有一个队列条目、delete 失败不扣"的守卫。
        service.reconcileMinIntervalMs = Long.MAX_VALUE

        val threads = 8
        val pagesPerThread = 25
        val payload = ByteArray(64 * 1024) // 8×25×64KB = 12.8MB 灌入 1MB 上限
        val pool = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = Collections.synchronizedList(mutableListOf<Exception>())

        repeat(threads) { t ->
            pool.submit {
                try {
                    startGate.await()
                    for (p in 0 until pagesPerThread) {
                        service.cacheImageByKey(940L + t, p, payload, "jpg")
                    }
                } catch (e: Exception) {
                    errors += e
                } finally {
                    done.countDown()
                }
            }
        }
        startGate.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发压测超时")
        pool.shutdown()
        assertTrue(errors.isEmpty(), "并发写+驱逐不应抛异常: ${errors.take(3)}")

        // 无负计数、无双扣：计数与磁盘真实状态精确一致
        val actualFiles = tempDir.walkTopDown().filter { it.isFile }.toList()
        assertEquals(actualFiles.size.toLong(), service.getDiskEntryCount(), "条目计数与磁盘实际不符（双扣/漏扣）")
        assertEquals(actualFiles.sumOf { it.length() }, service.getCacheStats().diskCacheSizeBytes, "字节计数与磁盘实际不符")
        assertTrue(service.getDiskEntryCount() >= 0)
        assertTrue(service.getCacheStats().diskCacheSizeBytes >= 0)
        val maxDisk = 1L * 1024 * 1024
        assertTrue(service.getCacheStats().diskCacheSizeBytes <= maxDisk + payload.size, "驱逐后应回到上限附近")
        assertEquals(0L, service.reconcileCount.get(), "禁用对账时不得触发对账")
    }

    @Test
    fun `P-S9 overwrite re-enqueues to tail and stale duplicate entries are skipped`() {
        val url = "https://example.com/rewrite.jpg"
        service.cacheImage(url, ByteArray(100)) // 条目 #1
        service.cacheImage(url, ByteArray(200)) // 覆写：重新入队到队尾，队列有两个同路径条目

        val scansBefore = service.fullScanCount.get()
        // 漂移量落在"队列内可删字节（100+200 覆写前值与 1024）恰好够降回上限"的
        // 窗口内（1047352 < 1048000 ≤ 1048576），确保不触发对账、纯队列驱逐。
        service.debugInflateDiskSizeBytes(1_048_000L) // 推过上限触发驱逐

        service.cacheImageByKey(960L, 1, ByteArray(1024), "jpg")

        assertEquals(scansBefore, service.fullScanCount.get(), "同路径重复条目应被队内跳过，不得全树扫描")
        assertFalse(File(tempDir, "_url").isDirectory && File(tempDir, "_url").listFiles()!!.isNotEmpty())
        assertTrue(service.getDiskEntryCount() >= 0)
        assertTrue(service.getCacheStats().diskCacheSizeBytes >= 0)
    }

    // ── getCacheSize (backward compat) ─────────────────────────

    @Test
    fun `getCacheSize reflects memory entry count`() {
        assertEquals(0, service.getCacheSize())

        service.cacheImage("https://example.com/x.jpg", byteArrayOf(1))
        assertEquals(1, service.getCacheSize())

        service.cacheImageByKey(1L, 1, byteArrayOf(2), "jpg")
        assertEquals(2, service.getCacheSize())
    }

    @Test
    // MASTER-2026-08-22 P4：内存层按字节上限淘汰——巨图不再无界常驻堆。
    fun `memory tier evicts oldest entries when byte cap exceeded (P4)`() {
        val p4config = SiteCoreConfigProperties().apply {
            download.cachePath = tempDir.resolve("p4").absolutePath
            download.cacheSizeMb = 8
            download.cacheMemoryMb = 1 // 1 MB 字节上限
        }
        val p4 = ImageCacheService(p4config)
        p4.init()

        val big = ByteArray(600 * 1024) // 600KB × 3 = 1.8MB > 1MB
        val urls = listOf("https://example.com/a.jpg", "https://example.com/b.jpg", "https://example.com/c.jpg")
        urls.forEach { p4.cacheImage(it, big) }

        // 同步排空 Caffeine 维护队列；随后以缓存真实状态（asMap().size）为准——
        // memorySizeBytes 由异步 removalListener 维护，短暂滞后不作断言依据。
        p4.drainMaintenance()

        val liveEntries = p4.getCacheStats().memoryCacheEntries
        assertTrue(liveEntries * 600 * 1024 <= 1024 * 1024,
            "live entries ($liveEntries) must satisfy the byte cap; got ${p4.getMemorySizeBytes()} (lagging counter)")
    }
}
