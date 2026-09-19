package com.hippo.anotherviewer.web.service.integrity

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.entity.PageFileHashEntity
import com.hippo.anotherviewer.web.entity.PageFileHashId
import com.hippo.anotherviewer.web.entity.RepairLogEntity
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.repository.DownloadLabelRepository
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import com.hippo.anotherviewer.web.repository.RepairLogRepository
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.DownloadService
import com.hippo.anotherviewer.web.service.EhAvailabilityService
import com.hippo.anotherviewer.web.service.GalleryLookupService
import com.hippo.anotherviewer.web.service.ImageCacheService
import com.hippo.anotherviewer.web.service.ServerConfigService
import com.hippo.anotherviewer.web.service.SiteSessionManager
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.io.File
import java.io.IOException
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 文件完整性 Wave 3（S7）修复管线测试：forceRefetchPage（成功 + 两种归因 +
 * 源 404 / V 门拒收的 failed 路径）、reverifyGallery（好页跳过零网络、坏页修复、
 * 中断部分统计）、修复日志落行。上游用 OkHttp MockWebServer 模拟（S3
 * DownloadServiceTest 先例）；fixtures 取 Wave 0 classpath /integrity/。
 */
class DownloadServiceRepairTest {

    /** Wave 0 classpath fixture（src/test/resources/integrity/，口径见 MANIFEST.sha256）。 */
    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/integrity/$name")!!.readBytes()

    private val validJpgHash = "24ac74130806ae02d7e4ee72881b977601999c6c9f94ee1545ed4830459b737f"
    private val validPngHash = "248b07a3d0e1e0f67d43d18065be8f74434549c0fdde6b0bfc08a7835d41909f"

    @TempDir
    lateinit var tempDir: File

    private lateinit var downloadRepository: DownloadInfoRepository
    private lateinit var downloadDirIndex: DownloadDirIndex
    private lateinit var galleryLookup: GalleryLookupService
    private lateinit var sessionManager: SiteSessionManager
    private lateinit var imageCache: ImageCacheService
    private lateinit var hashStore: ConcurrentHashMap<PageFileHashId, PageFileHashEntity>
    private lateinit var pageFileHashRepository: PageFileHashRepository
    private lateinit var repairLogRows: MutableList<RepairLogEntity>
    private lateinit var config: SiteCoreConfigProperties
    private lateinit var poolDir: File
    private lateinit var serverConfigService: ServerConfigService
    private lateinit var service: DownloadService

    @BeforeEach
    fun setUp() {
        poolDir = File(tempDir, "downloads/7").apply { mkdirs() }
        config = SiteCoreConfigProperties().apply {
            download.path = File(tempDir, "downloads").absolutePath
            download.cachePath = File(tempDir, "cache").absolutePath
        }
        downloadRepository = mock(DownloadInfoRepository::class.java)
        downloadDirIndex = mock(DownloadDirIndex::class.java)
        galleryLookup = mock(GalleryLookupService::class.java)
        sessionManager = mock(SiteSessionManager::class.java)
        imageCache = ImageCacheService(config).apply { init() }
        serverConfigService = mock(ServerConfigService::class.java)
        // BackfillService 读 KV 断点：mock 缺省返回 null 会 NPE，钉成空断点
        // （doReturn 风格传真实参数值，避开 Kotlin 非空参数上的 matcher 陷阱）。
        doReturn("").`when`(serverConfigService).get(BackfillService.KEY_CHECKPOINT, "")

        hashStore = ConcurrentHashMap()
        pageFileHashRepository = mock(PageFileHashRepository::class.java).apply {
            `when`(findByGidAndPage(anyLong(), anyInt())).thenAnswer { inv ->
                hashStore[PageFileHashId(inv.getArgument<Long>(0), inv.getArgument<Int>(1))]
            }
            `when`(findByGid(anyLong())).thenAnswer { inv ->
                hashStore.values.filter { it.gid == inv.getArgument<Long>(0) }
            }
            `when`(save(any(PageFileHashEntity::class.java))).thenAnswer { inv ->
                val e = inv.getArgument<PageFileHashEntity>(0)
                hashStore[PageFileHashId(e.gid, e.page)] = e
                e
            }
        }

        repairLogRows = mutableListOf()
        val repairLogRepository = mock(RepairLogRepository::class.java).apply {
            `when`(save(any(RepairLogEntity::class.java))).thenAnswer { inv ->
                val e = inv.getArgument<RepairLogEntity>(0)
                repairLogRows.add(e)
                e
            }
        }

        service = DownloadService(
            downloadRepository,
            mock(DownloadLabelRepository::class.java),
            config,
            mock(ApplicationEventPublisher::class.java),
            imageCache,
            sessionManager,
            galleryLookup,
            serverConfigService,
            mock(EhAvailabilityService::class.java),
            downloadDirIndex,
            mock(com.hippo.anotherviewer.web.repository.HistoryInfoRepository::class.java),
            stubProvider("test-user"),
            pageFileHashRepository,
            RepairLogService(repairLogRepository),
        )
        `when`(sessionManager.okHttpClient).thenReturn(OkHttpClient())
        `when`(downloadDirIndex.dirFor(7L)).thenReturn(poolDir)
        `when`(downloadRepository.findAllByGid(7L)).thenReturn(listOf(row()))
    }

    private fun stubProvider(name: String): com.hippo.anotherviewer.web.config.CurrentUsernameProvider =
        mock(com.hippo.anotherviewer.web.config.CurrentUsernameProvider::class.java)
            .apply { `when`(currentUsername()).thenReturn(name) }

    private fun row(): com.hippo.anotherviewer.web.entity.DownloadInfoEntity =
        com.hippo.anotherviewer.web.entity.DownloadInfoEntity().apply {
            id = 1L
            gid = 7L
            token = "tok"
            title = "T"
            state = 3
            total = 3
            done = 3
            downloadDir = poolDir.absolutePath
        }

    /** 基线行（0-based 页号）。 */
    private fun baseline(page0: Int, hash: String, verdict: String? = "ok") {
        hashStore[PageFileHashId(7L, page0)] = PageFileHashEntity().apply {
            gid = 7L
            page = page0
            ext = "jpg"
            size = 10
            this.hash = hash
            origin = "import"
            createdAt = 1L
            lastVerifiedAt = 99L
            this.verdict = verdict
        }
    }

    private fun startPageServer(vararg bodies: ByteArray): MockWebServer =
        MockWebServer().apply {
            start()
            bodies.forEach { bytes ->
                enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))
            }
        }

    // ── forceRefetchPage：healed + 归因 ─────────────────────────────────────

    @Test
    fun `forceRefetchPage heals a corrupted page and attributes local_corrupt`() {
        val valid = fixture("valid.jpg")
        val corrupt = fixture("truncated.jpg")
        val poolFile = File(poolDir, "00000001.jpg").apply { writeBytes(corrupt) }
        baseline(0, validJpgHash) // 基线 = valid.jpg：覆写字节与基线同 → 本地劣化
        val server = startPageServer(valid)
        try {
            val url = server.url("/1.jpg").toString()
            `when`(galleryLookup.fetchImageUrl(7L, "tok", 1)).thenReturn(url)
            // 预置待清的旧缓存：page-keyed 磁盘/内存条目 + enhanced 派生。
            imageCache.cacheImageByKey(7L, 0, ByteArray(9), "jpg")
            File(config.download.cachePath, "enhanced/7").apply { mkdirs() }
                .resolve("0.jpg").writeBytes(ByteArray(3))

            val result = service.forceRefetchPage(7L, 1)

            assertEquals("healed", result.status)
            assertEquals("local_corrupt", result.attribution)
            assertNull(result.message)
            // 池文件被覆写为源的新鲜字节（保留既有文件名）。
            assertTrue(poolFile.readBytes().contentEquals(valid))
            // 基线刷新：origin=heal、巡检结论复位（verdict/last_verified_at 全清）。
            val row = hashStore.getValue(PageFileHashId(7L, 0))
            assertEquals(validJpgHash, row.hash)
            assertEquals("heal", row.origin)
            assertEquals(valid.size.toLong(), row.size)
            assertNull(row.verdict)
            assertNull(row.lastVerifiedAt)
            assertTrue(row.createdAt > 1L)
            // 缓存清理：page-keyed 与 enhanced 派生被清，URL 条目重灌为新鲜字节。
            assertNull(imageCache.findCachedPageFile(7L, 0))
            assertNull(imageCache.getEnhancedImage(7L, 0))
            assertTrue(imageCache.getCachedImage(url)!!.contentEquals(valid))
            // 索引失效强制下次重扫。
            verify(downloadDirIndex).invalidate(7L)
            // 修复日志落行（source=refresh）。
            assertEquals(1, repairLogRows.size)
            val logRow = repairLogRows.single()
            assertEquals(7L, logRow.gid)
            assertEquals(0, logRow.page)
            assertEquals("local_corrupt", logRow.attribution)
            assertEquals(validJpgHash, logRow.oldHash)
            assertEquals(validJpgHash, logRow.newHash)
            assertEquals("refresh", logRow.source)
            assertTrue(logRow.repairedAt > 0)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `forceRefetchPage attributes source_changed when refetched bytes differ from baseline`() {
        val valid = fixture("valid.jpg")
        File(poolDir, "00000001.jpg").writeBytes(fixture("truncated.jpg"))
        baseline(0, validPngHash) // 基线 ≠ 源新字节 → 源已变
        val server = startPageServer(valid)
        try {
            `when`(galleryLookup.fetchImageUrl(7L, "tok", 1)).thenReturn(server.url("/1.jpg").toString())

            val result = service.forceRefetchPage(7L, 1)

            assertEquals("healed", result.status)
            assertEquals("source_changed", result.attribution)
            val logRow = repairLogRows.single()
            assertEquals("source_changed", logRow.attribution)
            assertEquals(validPngHash, logRow.oldHash)
            assertEquals(validJpgHash, logRow.newHash)
        } finally {
            server.shutdown()
        }
    }

    // ── forceRefetchPage：failed 路径（本地文件与基线不动） ──────────────────

    @Test
    fun `forceRefetchPage fails without touching the local file when the source 404s`() {
        val corrupt = fixture("truncated.jpg")
        val poolFile = File(poolDir, "00000001.jpg").apply { writeBytes(corrupt) }
        baseline(0, validJpgHash)
        val server = MockWebServer().apply {
            start()
            enqueue(MockResponse().setResponseCode(404))
        }
        try {
            `when`(galleryLookup.fetchImageUrl(7L, "tok", 1)).thenReturn(server.url("/1.jpg").toString())

            val result = service.forceRefetchPage(7L, 1)

            assertEquals("failed", result.status)
            assertNull(result.attribution)
            assertTrue(result.message!!.contains("404"))
            // 本地文件与基线原样未动。
            assertTrue(poolFile.readBytes().contentEquals(corrupt))
            val baselineRow = hashStore.getValue(PageFileHashId(7L, 0))
            assertEquals(validJpgHash, baselineRow.hash)
            assertEquals("import", baselineRow.origin)
            assertEquals("ok", baselineRow.verdict)
            // 失败也落遥测行（attribution/new_hash 空）。
            val logRow = repairLogRows.single()
            assertNull(logRow.attribution)
            assertEquals(validJpgHash, logRow.oldHash)
            assertNull(logRow.newHash)
            assertEquals("refresh", logRow.source)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `forceRefetchPage fails and keeps the local file when V gate rejects the refetch`() {
        val corrupt = fixture("truncated.jpg")
        val poolFile = File(poolDir, "00000001.jpg").apply { writeBytes(corrupt) }
        baseline(0, validJpgHash)
        val server = startPageServer(fixture("truncated.jpg"))
        try {
            `when`(galleryLookup.fetchImageUrl(7L, "tok", 1)).thenReturn(server.url("/1.jpg").toString())

            val result = service.forceRefetchPage(7L, 1)

            assertEquals("failed", result.status)
            assertNull(result.attribution)
            assertTrue(result.message!!.contains("V gate"))
            assertTrue(result.message!!.contains("V2_TAIL"))
            assertTrue(poolFile.readBytes().contentEquals(corrupt))
            assertEquals(1, repairLogRows.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `forceRefetchPage fails fast when the gallery is unknown to this server`() {
        `when`(downloadRepository.findAllByGid(999L)).thenReturn(emptyList())
        `when`(galleryLookup.findToken(999L)).thenReturn(null)

        val result = service.forceRefetchPage(999L, 1)

        assertEquals("failed", result.status)
        assertTrue(result.message!!.contains("token"))
        // 失败尝试也落遥测行（attribution/new_hash 空）。
        assertEquals(1, repairLogRows.size)
        assertNull(repairLogRows.single().attribution)
    }

    // ── reverifyGallery：好页零网络跳过、坏页修复、中断 ─────────────────────

    @Test
    fun `reverifyGallery repairs only bad pages and skips good pages without source pulls`() {
        val valid = fixture("valid.jpg")
        // 页1 好（哈希==基线）、页2 坏（哈希不符）、页3 缺失（读不出）。
        File(poolDir, "00000001.jpg").writeBytes(valid)
        File(poolDir, "00000002.jpg").writeBytes(fixture("truncated.jpg"))
        baseline(0, validJpgHash)
        baseline(1, validJpgHash)
        baseline(2, validJpgHash)
        val server = startPageServer(valid, valid) // 页2、页3 各一次源拉取
        try {
            val url = server.url("/p.jpg").toString()
            `when`(galleryLookup.fetchImageUrl(7L, "tok", 2)).thenReturn(url)
            `when`(galleryLookup.fetchImageUrl(7L, "tok", 3)).thenReturn(url)

            val stats = service.reverifyGallery(7L)

            assertEquals(3, stats.total)
            assertEquals(1, stats.ok)
            assertEquals(2, stats.bad)
            assertEquals(2, stats.refreshed)
            assertFalse(stats.interrupted)
            // 坏页两处都被覆写为源字节（页2 保名、页3 新建 8 位名）。
            assertTrue(File(poolDir, "00000002.jpg").readBytes().contentEquals(valid))
            assertTrue(File(poolDir, "00000003.jpg").readBytes().contentEquals(valid))
            // 修复日志：两次 reverify 来源的行（页号 0-based）。
            assertEquals(2, repairLogRows.size)
            assertTrue(repairLogRows.all { it.source == "reverify" })
            assertEquals(setOf(1, 2), repairLogRows.map { it.page }.toSet())
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `reverifyGallery treats a baseline-less structurally valid page as ok with zero source pulls`() {
        File(poolDir, "00000001.jpg").writeBytes(fixture("valid.jpg")) // 无基线，V 门过
        // total=0：逐页范围只取磁盘页（不把缺失的 2..3 当坏页拉源）。
        `when`(downloadRepository.findAllByGid(7L)).thenReturn(listOf(row().apply { total = 0 }))
        `when`(galleryLookup.fetchImageUrl(anyLong(), org.mockito.ArgumentMatchers.anyString(), anyInt()))
            .thenThrow(IllegalStateException("must not pull source"))

        val stats = service.reverifyGallery(7L)

        assertEquals(1, stats.total)
        assertEquals(1, stats.ok)
        assertEquals(0, stats.bad)
        assertEquals(0, stats.refreshed)
        assertFalse(stats.interrupted)
        assertTrue(repairLogRows.isEmpty())
    }

    @Test
    fun `reverifyGallery reports interrupted partial stats when cancelled mid-gallery`() {
        val valid = fixture("valid.jpg")
        File(poolDir, "00000001.jpg").writeBytes(valid) // 页1 好
        File(poolDir, "00000002.jpg").writeBytes(fixture("truncated.jpg")) // 页2 坏（取消后不再处理）
        baseline(0, validJpgHash)
        baseline(1, validJpgHash)
        `when`(galleryLookup.fetchImageUrl(anyLong(), org.mockito.ArgumentMatchers.anyString(), anyInt()))
            .thenThrow(IllegalStateException("must not be reached after cancel"))

        var calls = 0
        val stats = service.reverifyGallery(7L) { ++calls > 1 }

        assertEquals(1, stats.total) // 仅页1 已检查
        assertEquals(1, stats.ok)
        assertEquals(0, stats.bad)
        assertEquals(0, stats.refreshed)
        assertTrue(stats.interrupted)
        assertTrue(repairLogRows.isEmpty())
    }

    // ── 共享基线上取（PageHashWriter） origin 语义 ──────────────────────────

    @Test
    fun `writePageWithBaseline keeps downloader origin while repair uses heal`() {
        val target = File(poolDir, "00000009.jpg")
        val bytes = fixture("valid.png")

        assertTrue(service.writePageWithBaseline(7L, 9, target, bytes))
        assertEquals("downloader", hashStore.getValue(PageFileHashId(7L, 8)).origin)

        assertTrue(service.writePageWithBaseline(7L, 9, target, bytes, "heal"))
        assertEquals("heal", hashStore.getValue(PageFileHashId(7L, 8)).origin)
        assertEquals(validPngHash, hashStore.getValue(PageFileHashId(7L, 8)).hash)
    }

    // ── P1-1 原子写：半截失败不冒充完成页、无 temp 残留 ─────────────────────

    @Test
    fun `atomic write keeps the old target intact and leaves no temp residue when the stream fails midway`() {
        val valid = fixture("valid.jpg")
        val target = File(poolDir, "00000001.jpg").apply { writeBytes(valid) }

        // 注入失败流：半截字节先写进 temp，然后中途抛 IO 异常。
        val failed = service.atomicWrite(target) { out ->
            out.write(byteArrayOf(1, 2, 3))
            throw IOException("simulated mid-write failure")
        }

        assertFalse(failed, "写入中途失败必须返回 false")
        assertTrue(target.readBytes().contentEquals(valid), "目标文件不得被半截内容污染")
        // 无 temp 残留（temp 名带隐藏前缀，即使意外残留也绝不命中页文件名模式）。
        assertEquals(
            listOf("00000001.jpg"),
            poolDir.listFiles()!!.map { it.name }.sorted(),
            "失败路径不得留 temp 残留",
        )
    }

    @Test
    fun `writePageWithBaseline reports failure without touching file or baseline when the atomic move fails`() {
        // 目标路径被非空目录占位：temp 写得进、原子换名必然失败。
        val occupied = File(poolDir, "00000001.jpg").apply { mkdirs() }
        File(occupied, "occupied").writeBytes(byteArrayOf(9))
        baseline(0, validJpgHash)

        val bytes = fixture("valid.png")
        assertFalse(service.writePageWithBaseline(7L, 1, occupied, bytes), "落盘失败必须返回 false")
        assertEquals(
            setOf("00000001.jpg"),
            poolDir.listFiles()!!.map { it.name }.toSet(),
            "失败路径不得留 temp 残留",
        )
        assertTrue(occupied.isDirectory, "失败路径不动目标")
        // 基线不动（返回 false，upsert 不得执行）。
        val row = hashStore.getValue(PageFileHashId(7L, 0))
        assertEquals(validJpgHash, row.hash)
        assertEquals("import", row.origin)
    }

    // ── P1-2 部分下载画廊：reverify 只检查磁盘 ∪ 基线，零拉源 ───────────────

    @Test
    fun `reverify ignores pages beyond disk and baseline in a partially downloaded gallery`() {
        val valid = fixture("valid.jpg")
        // 行元数据 total=5，磁盘只有 2 页、基线也只有这 2 页——缺失的 3..5 页
        // 是「尚未下载」而非「坏页」，不得并入逐页范围（P1-2）。
        File(poolDir, "00000001.jpg").writeBytes(valid)
        File(poolDir, "00000002.jpg").writeBytes(valid)
        baseline(0, validJpgHash)
        baseline(1, validJpgHash)
        `when`(downloadRepository.findAllByGid(7L)).thenReturn(listOf(row().apply { total = 5 }))
        `when`(galleryLookup.fetchImageUrl(anyLong(), org.mockito.ArgumentMatchers.anyString(), anyInt()))
            .thenThrow(IllegalStateException("must not pull source for undownloaded pages"))

        val stats = service.reverifyGallery(7L)

        assertEquals(2, stats.total, "逐页范围 = 磁盘页 ∪ 基线行，缺页不算")
        assertEquals(2, stats.ok)
        assertEquals(0, stats.bad)
        assertEquals(0, stats.refreshed)
        assertFalse(stats.interrupted)
        assertTrue(repairLogRows.isEmpty(), "零修复即零日志行")
    }

    // ── P2-2 活跃下载期间拒绝修复/复验 ──────────────────────────────────────

    @Test
    fun `repair and reverify are refused while a download task is active for the gallery`() {
        val valid = fixture("valid.jpg")
        val server = startPageServer(valid)
        try {
            // 页数请求先阻塞：让下载任务停在「活跃」状态供断言。
            val pageGate = CountDownLatch(1)
            `when`(galleryLookup.fetchPageCount(7L, "tok")).thenAnswer {
                pageGate.await(10, TimeUnit.SECONDS)
                1
            }
            `when`(galleryLookup.fetchImageUrl(7L, "tok", 1)).thenReturn(server.url("/1.jpg").toString())
            `when`(sessionManager.okHttpClient).thenReturn(OkHttpClient())
            val startRow = row().apply { state = 0; total = 0; done = 0 }
            `when`(downloadRepository.findById(1L)).thenReturn(Optional.of(startRow))
            `when`(downloadRepository.save(any(com.hippo.anotherviewer.web.entity.DownloadInfoEntity::class.java)))
                .thenAnswer { it.getArgument(0) }

            assertTrue(service.startDownload(1L), "下载任务应成功入队")
            try {
                awaitUntil { service.getActiveDownloadCount() == 1 }

                // 单页修复：拒绝（message 提示画廊下载中），零源拉取。
                val repair = service.forceRefetchPage(7L, 1)
                assertEquals("failed", repair.status)
                assertTrue(repair.message!!.contains("download"), "message 应提示画廊下载中: ${repair.message}")
                // 整本复验：开始时拒绝，零页检查、零网络。
                val stats = service.reverifyGallery(7L)
                assertEquals(0, stats.total, "活跃下载期间整本拒绝：零页检查")
                assertEquals(0, ehServerRequestCount(server), "拒绝路径零拉源")
            } finally {
                pageGate.countDown()
            }
            awaitUntil { service.getActiveDownloadCount() == 0 }
            assertEquals(3, startRow.state, "前置下载应正常收场（复验拒绝不得卡死任务）")

            // 任务收场后不再拒绝：磁盘 1 页 + 下载建的基线 → ok，零网络。
            `when`(galleryLookup.fetchImageUrl(anyLong(), org.mockito.ArgumentMatchers.anyString(), anyInt()))
                .thenThrow(IllegalStateException("must not pull source after completion"))
            val stats = service.reverifyGallery(7L)
            assertEquals(1, stats.total)
            assertEquals(1, stats.ok)
            assertEquals(0, stats.bad)
        } finally {
            server.shutdown()
        }
    }

    // ── P2-5 4/8 位同名页：基线/比对/覆写三处选中同一物理文件 ───────────────

    @Test
    fun `duplicate 4 and 8 digit page files resolve to the same physical file across backfill reverify and repair`() {
        val truncated = fixture("truncated.jpg")
        // 同 (gid=7, page=1) 两个物理文件：legacy 4 位（损坏）与下载器 8 位（完好）。
        val legacy = File(poolDir, "0001.jpg").apply { writeBytes(truncated) }
        val standard = File(poolDir, "00000001.jpg").apply { writeBytes(fixture("valid.jpg")) }
        val server = startPageServer(fixture("valid.png"))
        try {
            // ① backfill 基线：只给统一胜者（8 位）建基线，4 位副本不建行。
            val backfill = BackfillService(
                config, downloadDirIndex, pageFileHashRepository, downloadRepository, serverConfigService,
            )
            val stats = backfill.backfillHashes(gid = 7L)
            assertEquals(1, stats.scanned, "同页两文件只处理选中的那个")
            assertEquals(1, stats.accepted)
            assertEquals(1, hashStore.size)
            assertEquals(validJpgHash, hashStore.getValue(PageFileHashId(7L, 0)).hash, "基线必须描述统一选中的 8 位文件")

            // ② reverify 比对：读的也是 8 位文件（哈希与基线一致 → ok，零拉源）；
            //    若选错 4 位文件会读出损坏字节 → 误报坏页拉源。
            `when`(galleryLookup.fetchImageUrl(anyLong(), org.mockito.ArgumentMatchers.anyString(), anyInt()))
                .thenThrow(IllegalStateException("must not pull source"))
            val reverify = service.reverifyGallery(7L)
            assertEquals(1, reverify.total)
            assertEquals(1, reverify.ok)
            assertEquals(0, reverify.bad)

            // ③ 修复覆写：把 8 位文件损坏后强制重取——覆写必须落在同一个 8 位文件上。
            standard.writeBytes(truncated)
            // doReturn 风格重打桩：上一步的 thenThrow 桩会在 when(mock.method(...))
            // 的调用期触发，doReturn/when 走不打桩行为的注册路径。
            doReturn(server.url("/1.jpg").toString())
                .`when`(galleryLookup).fetchImageUrl(7L, "tok", 1)
            val repair = service.forceRefetchPage(7L, 1)
            assertEquals("healed", repair.status)
            assertTrue(standard.readBytes().contentEquals(fixture("valid.png")), "覆写必须落在统一选中的 8 位文件上")
            assertTrue(legacy.readBytes().contentEquals(truncated), "未选中的 4 位副本不得被触碰")
            assertEquals(validPngHash, hashStore.getValue(PageFileHashId(7L, 0)).hash, "基线随覆写刷新")
        } finally {
            server.shutdown()
        }
    }

    /** 异步 worker 收场等待（下载任务跑在 workerPool/pageExecutor 上）。 */
    private fun awaitUntil(timeoutMs: Long = 15_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    private fun ehServerRequestCount(server: MockWebServer): Int = server.requestCount
}
