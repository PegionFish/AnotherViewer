package com.hippo.anotherviewer.web.integrity

import com.hippo.anotherviewer.web.dto.JobState
import com.hippo.anotherviewer.web.entity.PageFileHashEntity
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import com.hippo.anotherviewer.web.repository.PeerHashRepository
import com.hippo.anotherviewer.web.repository.RepairLogRepository
import com.hippo.anotherviewer.web.repository.ScrubRunRepository
import com.hippo.anotherviewer.web.service.DownloadUploadService
import com.hippo.anotherviewer.web.service.GalleryLookupService
import com.hippo.anotherviewer.web.service.integrity.StorageIntegrityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.lenient
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 下载文件完整性端到端集成验证（Wave 4）：**@SpringBootTest 全上下文** + 临时
 * SQLite 库 + 临时下载目录 + OkHttp MockWebServer 假 EH 源，走真实服务组合把
 * 「上传建基线 → 注入损坏 → 巡检发现 → 交叉审计 → 修复复原 → reverify 整本 →
 * peer 上送红线」整条链路串起来。
 *
 * 网络红线：一切在本地嵌入环境跑，**绝不连接真实 EH 服务器**。唯一的 mock 在
 * EH 源 HTTP 边界——[GalleryLookupService]（站点 URL 解析网关，其唯一职责就是
 * 请求 EH 上游）被 @MockitoBean 替换为「返回 MockWebServer URL」的桩；页字节
 * 本体经**真实 OkHttp 客户端**（全上下文真实 SiteSessionManager client，请求
 * host 是 127.0.0.1 不落 CurlSiteExecutor 的站点指纹拦截）从 MockWebServer 拉
 * 取——与 DownloadServiceRepairTest / DownloadServiceTest 的既有缝一致。
 *
 * 同步方式：
 * - 巡检（POST /integrity/scrub）入队后台线程，轮询 [StorageIntegrityService.isRunning]
 *   等终态（单飞旗标在 POST 返回前已置位，run 行由同一线程在收旗标前落库，
 *   isRunning()==false 即可安全断言）。
 * - reverify（POST /integrity/reverify/{gid}）走 Job 模式，轮询 GET /jobs/{jobId}
 *   至 COMPLETED 后断言 result=ReverifyStats。
 *
 * 幂等隔离：每测试唯一 gid、@BeforeEach 清库 + 清空下载根目录、临时目录随类启停。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegrityE2ETest {

    companion object {
        private val dbDir = Files.createTempDirectory("av-e2e-integrity-db")
        private val dataRoot = Files.createTempDirectory("av-e2e-integrity-data")
        private val downloadsDir = Files.createDirectories(dataRoot.resolve("downloads"))

        /** 三个测试各自独占的 gid（特殊测试值段，与真实 gid 空间隔绝）。 */
        private const val GID_FLOW = 920_261_001L // 场景 1-5：上传→损坏→巡检→审计→修复
        private const val GID_REVERIFY = 920_261_002L // 场景 6：reverify 整本
        private const val GID_PEER = 920_261_003L // 场景 7：peer 上送红线

        @JvmStatic
        @DynamicPropertySource
        fun e2eProperties(registry: DynamicPropertyRegistry) {
            // 临时数据目录 + 临时 SQLite（先例：BackfillServiceTest /
            // IntegrityControllerTest 的 @DynamicPropertySource 基建）。
            registry.add("anotherviewer.data-dir") { dataRoot.toAbsolutePath().toString() }
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${dbDir.resolve("e2e-integrity.db")}?journal_mode=WAL&busy_timeout=30000"
            }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.community.dialect.SQLiteDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
            // DownloadDirIndex 根指纹检查不节流（≤0 = 每次都检，文档标注的测试口径）。
            registry.add("anotherviewer.download.dir-index-refresh-ms") { "0" }
        }

        private val mapper = jacksonObjectMapper()

        private fun fixture(name: String): ByteArray =
            IntegrityE2ETest::class.java.getResourceAsStream("/integrity/$name")?.readBytes()
                ?: error("classpath 缺少 fixture /integrity/$name")

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var downloadRepository: DownloadInfoRepository

    @Autowired
    lateinit var pageFileHashRepository: PageFileHashRepository

    @Autowired
    lateinit var peerHashRepository: PeerHashRepository

    @Autowired
    lateinit var scrubRunRepository: ScrubRunRepository

    @Autowired
    lateinit var repairLogRepository: RepairLogRepository

    @Autowired
    lateinit var storageIntegrityService: StorageIntegrityService

    @Autowired
    lateinit var uploadService: DownloadUploadService

    /** EH 源 URL 解析边界（站点网关）的测试替身：只重定向 URL，其余链路全真。 */
    @MockitoBean
    lateinit var galleryLookup: GalleryLookupService

    /** 假 EH 源：对任意请求回 200 + valid.jpg 字节（修复管线拉的真实 HTTP 边界）。 */
    private lateinit var ehSource: MockWebServer

    // ── fixtures 快照 ────────────────────────────────────────────────────────

    private val validJpg by lazy { fixture("valid.jpg") }
    private val validPng by lazy { fixture("valid.png") }
    private val validGif by lazy { fixture("valid.gif") }
    private val truncatedJpg by lazy { fixture("truncated.jpg") }
    private val truncatedPng by lazy { fixture("truncated.png") }

    private val validJpgHash by lazy { sha256(validJpg) }
    private val validPngHash by lazy { sha256(validPng) }
    private val truncatedJpgHash by lazy { sha256(truncatedJpg) }

    // ── 生命周期 ─────────────────────────────────────────────────────────────

    @BeforeEach
    fun setUp() {
        ehSource = MockWebServer()
        ehSource.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(Buffer().write(validJpg))
        }
        ehSource.start()
        // EH 边界桩：任何页码的源 URL 都指向假源（页字节经真实 OkHttp 拉取）。
        lenient().`when`(
            galleryLookup.fetchImageUrl(anyLong(), anyString(), anyInt())
        ).thenAnswer { ehSource.url("/eh/page.jpg").toString() }

        // 清库（独立短事务，与后台线程写库兼容——IntegrityControllerTest 同口径）。
        peerHashRepository.deleteAll()
        pageFileHashRepository.deleteAll()
        repairLogRepository.deleteAll()
        scrubRunRepository.deleteAll()
        downloadRepository.deleteAll()
        // 清空下载根目录（每测试自摆布局）。
        Files.list(downloadsDir).forEach { path ->
            if (Files.isDirectory(path)) walkTopDownDelete(path) else Files.deleteIfExists(path)
        }
    }

    @AfterEach
    fun tearDown() {
        ehSource.shutdown()
    }

    private fun walkTopDownDelete(path: Path) {
        Files.walk(path).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ── HTTP 摆盘工具（全走真实控制器端点） ──────────────────────────────────

    /** 场景 1 的上传链路：init → 逐页 multipart → complete（App push-of-local-downloads 同款）。 */
    private fun uploadGallery(gid: Long, title: String, pages: Map<Int, ByteArray>) {
        mockMvc.perform(
            put("/api/v1/download/upload/$gid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"e2e-tok-$gid","title":"$title","pages":${pages.size}}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        pages.forEach { (page, bytes) ->
            val ext = when {
                bytes.contentEquals(validJpg) -> "jpg"
                bytes.contentEquals(validGif) -> "gif"
                else -> "png"
            }
            mockMvc.perform(
                multipart("/api/v1/download/upload/$gid/page/$page").file(
                    MockMultipartFile("file", "p$page.$ext", "image/$ext", bytes)
                )
            ).andExpect(status().isOk)
        }

        mockMvc.perform(
            post("/api/v1/download/upload/$gid/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"total":${pages.size},"done":${pages.size}}""")
        ).andExpect(status().isOk)
    }

    /** 手动巡检并同步等终态（POST /scrub 受理 → isRunning 翻假）。 */
    private fun scrubAndWait() {
        mockMvc.perform(post("/api/v1/integrity/scrub"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.accepted").value(true))
        awaitScrubIdle()
    }

    private fun awaitScrubIdle(timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (storageIntegrityService.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(storageIntegrityService.isRunning(), "巡检未在时限内结束")
    }

    /** 轮询 GET /jobs/{jobId} 至 COMPLETED，返回终态 job JSON。 */
    private fun awaitJobCompleted(jobId: String, timeoutMs: Long = 15_000): JsonNode {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val body = mockMvc.perform(get("/api/v1/jobs/$jobId"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
            val job = mapper.readTree(body)
            if (job.get("state").asText() == JobState.COMPLETED.name) return job
            Thread.sleep(20)
        }
        error("job $jobId 未在时限内完成")
    }

    /** 下载行的落盘目录（initUpload 派生的绝对路径 `{root}/{gid}-{title}`）。 */
    private fun galleryDir(gid: Long): Path {
        val dir = downloadRepository.findAllByGid(gid).firstOrNull()?.downloadDir
            ?: error("gid=$gid 无下载行")
        return Path.of(dir)
    }

    /** 页文件快照（全字段）用于红线断言。 */
    private fun baselineSnapshot(gid: Long): List<List<Any?>> =
        pageFileHashRepository.findByGid(gid).map {
            listOf(
                it.gid, it.page, it.ext, it.size, it.hash, it.algo,
                it.origin, it.createdAt, it.lastVerifiedAt, it.verdict,
            )
        }.sortedBy { it[1].toString().toInt() }

    // ═════════════════════════════════════════════════════════════════════════
    // 场景 1-5 主链路：上传建基线 → 注入损坏 → 巡检发现 → 交叉审计 → 修复复原
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `upload then corrupt then scrub then cross-audit then refresh heals end to end`() {
        val gid = GID_FLOW

        // ── 场景 1：上传建基线 ────────────────────────────────────────────────
        uploadGallery(gid, "E2E Integrity", mapOf(1 to validJpg, 2 to validPng))

        val dir = galleryDir(gid)
        val page1 = dir.resolve("00000001.jpg")
        val page2 = dir.resolve("00000002.png")
        assertTrue(page1.toFile().isFile, "页 1 应落盘为 8 位 1-based 命名")
        assertTrue(page2.toFile().isFile)
        assertTrue(page1.toFile().readBytes().contentEquals(validJpg))

        val baseline1 = pageFileHashRepository.findByGidAndPage(gid, 0)
        assertNotNull(baseline1, "合法页上传必须建基线")
        assertEquals(validJpgHash, baseline1!!.hash)
        assertEquals(validJpg.size.toLong(), baseline1.size)
        assertEquals("jpg", baseline1.ext)
        assertEquals("sha256", baseline1.algo)
        assertEquals("upload", baseline1.origin, "上传链路 origin=upload")
        assertNull(baseline1.verdict)
        assertNull(baseline1.lastVerifiedAt)
        assertEquals(validPngHash, pageFileHashRepository.findByGidAndPage(gid, 1)!!.hash)

        // ── 场景 2：注入损坏（bit rot / 截断）——只动磁盘，基线行不动 ─────────
        page1.toFile().writeBytes(truncatedJpg)
        assertTrue(page1.toFile().readBytes().contentEquals(truncatedJpg))
        assertEquals(
            validJpgHash,
            pageFileHashRepository.findByGidAndPage(gid, 0)!!.hash,
            "损坏注入只覆写磁盘字节，基线哈希保持不动",
        )

        // ── 场景 3：巡检发现 ─────────────────────────────────────────────────
        scrubAndWait()

        val run1 = scrubRunRepository.findFirstByOrderByIdDesc()
        assertNotNull(run1, "巡检必须落 scrub_run 记录")
        assertEquals("completed", run1!!.status)
        assertEquals(2, run1.totalPages)
        assertEquals(1, run1.okPages)
        assertEquals(1, run1.badPages)

        val row1 = pageFileHashRepository.findByGidAndPage(gid, 0)!!
        assertEquals("struct_bad", row1.verdict, "坏页 verdict 应变 struct_bad")
        assertEquals(validJpgHash, row1.hash, "巡检只判定不修复：基线哈希绝不动")
        assertNull(row1.lastVerifiedAt, "坏页不刷新 last_verified_at（LRU 保持靠前）")

        mockMvc.perform(get("/api/v1/integrity/report"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.badPageTotal").value(1))
            .andExpect(jsonPath("$.badPages[0].page").value(1))
            .andExpect(jsonPath("$.badPages[0].verdict").value("MISMATCH"))
            .andExpect(jsonPath("$.divergenceTotal").value(0))

        // ── 场景 4：交叉审计——App 上送同页不同哈希作 peer 证据 ──────────────
        mockMvc.perform(
            post("/api/v1/integrity/hashes/$gid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""[{"page":1,"ext":"jpg","size":${validPng.size},"hash":"$validPngHash"}]""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(1))

        val peer = peerHashRepository.findByGidAndPage(gid, 0)
        assertNotNull(peer, "peer 证据应落 peer_hash 行（1-based 契约页 → 0-based 落库）")
        assertEquals(validPngHash, peer!!.hash)

        scrubAndWait()

        val run2 = scrubRunRepository.findFirstByOrderByIdDesc()!!
        assertEquals(1, run2.divergences, "同页基线/peer 哈希不同 = 版本分歧")
        mockMvc.perform(get("/api/v1/integrity/report"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.divergenceTotal").value(1))
            .andExpect(jsonPath("$.divergences[0].gid").value(gid))
            .andExpect(jsonPath("$.divergences[0].page").value(1))
            .andExpect(jsonPath("$.divergences[0].localHash").value(validJpgHash))
            .andExpect(jsonPath("$.divergences[0].peerHash").value(validPngHash))

        // ── 场景 5：修复复原（MockWebServer 扮 EH 源返回合法页字节） ─────────
        mockMvc.perform(post("/api/v1/integrity/refresh/$gid/1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("healed"))
            .andExpect(jsonPath("$.attribution").value("local_corrupt"))

        // 磁盘文件恢复合法字节。
        assertTrue(page1.toFile().readBytes().contentEquals(validJpg), "refresh 应把池文件覆写为源的新鲜字节")
        // 基线刷新（origin=heal，巡检结论复位）。
        val healed = pageFileHashRepository.findByGidAndPage(gid, 0)!!
        assertEquals(validJpgHash, healed.hash)
        assertEquals("heal", healed.origin)
        assertEquals(validJpg.size.toLong(), healed.size)
        assertNull(healed.verdict, "heal 后巡检结论复位为未巡检")
        assertNull(healed.lastVerifiedAt)
        assertTrue(healed.createdAt >= baseline1.createdAt)
        // repair_log 落行：source=refresh。归因口径——旧基线（validJpgHash）与覆写前
        // 磁盘损坏字节（truncatedJpgHash）不同、与新拉取字节（validJpgHash）一致
        // → local_corrupt（本地介质劣化，源未变）。
        val logRow = repairLogRepository.findByGidOrderByIdDesc(gid).firstOrNull()
        assertNotNull(logRow, "refresh 修复必须落 repair_log 行")
        assertEquals("refresh", logRow!!.source)
        assertEquals("local_corrupt", logRow.attribution)
        assertEquals(0, logRow.page)
        assertEquals(validJpgHash, logRow.oldHash)
        assertEquals(validJpgHash, logRow.newHash)
        assertTrue(logRow.repairedAt > 0)

        // 再巡检：该页翻案为 ok，report 坏页清单消失（peer 分歧仍在——heal 只修
        // 己方基线，绝不以 peer 覆盖基线，交叉审计如实继续报告版本分歧）。
        scrubAndWait()

        val run3 = scrubRunRepository.findFirstByOrderByIdDesc()!!
        assertEquals(0, run3.badPages, "修复后该页应巡检通过")
        assertEquals(2, run3.okPages)
        assertEquals("ok", pageFileHashRepository.findByGidAndPage(gid, 0)!!.verdict)
        mockMvc.perform(get("/api/v1/integrity/report"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.badPageTotal").value(0))
            .andExpect(jsonPath("$.badPages").isEmpty())
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 场景 6：reverify 整本（Job 模式）——只修坏页、统计与文件复原
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `reverify job restores the corrupted page and reports stats`() {
        val gid = GID_REVERIFY
        uploadGallery(gid, "E2E Reverify", mapOf(1 to validJpg, 2 to validPng, 3 to validGif))

        val dir = galleryDir(gid)
        val page2 = dir.resolve("00000002.png")
        page2.toFile().writeBytes(truncatedPng) // 注入第二处坏页（其余两页完好）

        val result = mockMvc.perform(post("/api/v1/integrity/reverify/$gid"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.jobId").exists())
            .andReturn()
        val jobId = mapper.readTree(result.response.contentAsString).get("jobId").asText()

        val job = awaitJobCompleted(jobId)
        assertEquals(3, job.get("result").get("total").asInt(), "三页逐页检查")
        assertEquals(2, job.get("result").get("ok").asInt(), "好页零网络跳过")
        assertEquals(1, job.get("result").get("bad").asInt())
        assertEquals(1, job.get("result").get("refreshed").asInt(), "坏页应修复成功")
        assertFalse(job.get("result").get("interrupted").asBoolean())
        assertEquals(1, ehSource.requestCount, "只有坏页拉源（好页零网络）")

        // 文件复原：坏页被覆写为源返回的字节（valid.jpg，基线刷新 origin=heal）。
        assertTrue(page2.toFile().readBytes().contentEquals(validJpg), "reverify 应修复坏页文件")
        val healed = pageFileHashRepository.findByGidAndPage(gid, 1)!!
        assertEquals(validJpgHash, healed.hash)
        assertEquals("heal", healed.origin)

        // 修复日志：source=reverify、页号 0-based。归因——假 EH 源 dispatcher
        // 恒回 valid.jpg 字节，而该页基线是 validPng 的哈希：覆写字节 != 旧基线
        // → source_changed（源内容已变的如实归因；与场景 5 refresh 的
        // local_corrupt 恰好对照了两种归因分支）。
        val logs = repairLogRepository.findByGidOrderByIdDesc(gid)
        assertEquals(1, logs.size)
        val logRow = logs.single()
        assertEquals("reverify", logRow.source)
        assertEquals(1, logRow.page)
        assertEquals("source_changed", logRow.attribution)
        assertEquals(validPngHash, logRow.oldHash)
        assertEquals(validJpgHash, logRow.newHash)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 场景 7：App 上送落 peer —— peer_hash 写入 + page_file_hash 零变化（红线）
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `app peer push lands peer rows and never touches the baseline (red line)`() {
        val gid = GID_PEER
        uploadGallery(gid, "E2E Peer", mapOf(1 to validJpg, 2 to validPng))
        assertEquals(2, pageFileHashRepository.findByGid(gid).size)

        val before = baselineSnapshot(gid)
        assertEquals(2, before.size)

        // 与基线同 (gid,page) 的页（交叉审计最易误写基线的场景）+ 大写哈希（归一
        // 口径）+ peer-only 页（page3 无基线）。
        mockMvc.perform(
            post("/api/v1/integrity/hashes/$gid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "[" +
                        """{"page":1,"ext":"jpg","size":${validJpg.size},"hash":"${truncatedJpgHash.uppercase()}"},""" +
                        """{"page":2,"ext":"png","size":${validPng.size},"hash":"${truncatedJpgHash.uppercase()}"},""" +
                        """{"page":3,"ext":"jpg","size":9,"hash":"${"a".repeat(64)}"}""" +
                        "]"
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(3))

        // peer_hash 三行落库：1-based 契约页 → 0-based 行、哈希归一小写。
        val peers = peerHashRepository.findByGid(gid).associateBy { it.page }
        assertEquals(3, peers.size)
        assertEquals(truncatedJpgHash, peers.getValue(0).hash, "大写哈希应归一小写落库")
        assertEquals(truncatedJpgHash, peers.getValue(1).hash)
        assertEquals("a".repeat(64), peers.getValue(2).hash)
        assertTrue(peers.values.all { it.updatedAt > 0 })

        // 红线：page_file_hash 基线逐字段零变化，且 peer-only 页不得新建基线行。
        assertEquals(before, baselineSnapshot(gid), "红线：peer 上送不得改动基线任何字段")
        assertEquals(2, pageFileHashRepository.findByGid(gid).size)
    }
}
