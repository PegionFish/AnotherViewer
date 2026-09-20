package com.hippo.anotherviewer.web.integrity

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import com.hippo.anotherviewer.web.service.DirIndexSnapshotStore
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.DownloadProgressPersister
import com.hippo.anotherviewer.web.service.GalleryLookupService
import com.hippo.anotherviewer.web.service.ImageCacheService
import com.hippo.anotherviewer.web.util.HttpCacheSupport
import com.hippo.anotherviewer.web.util.ThumbnailScaler
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.lenient
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 二期 Wave 1 集成缝隙的端到端钉子（P-S2 / P-S3 / P-S8），基建手法与
 * [IntegrityE2ETest] 同口径：**@SpringBootTest 全上下文** + 临时 SQLite/临时
 * 目录 + MockWebServer 假 EH 源，一切在本地嵌入环境跑，绝不连接真实服务器。
 *
 * == 网络红线与 /proxy 的特殊口径 ==
 * 唯一的 mock 在 EH 源边界：[GalleryLookupService] 被 @MockitoBean 重定向到
 * MockWebServer，页/修复字节经真实 OkHttp（host=127.0.0.1，不落
 * CurlSiteExecutor 的站点指纹拦截）拉取。`GET /api/v1/image/proxy` 的
 * fetch-on-miss 路径把调用方传入的 URL **直连**拉取且 host 白名单只放行
 * Gallery Site 域（那些域会走真实 curl）——本地嵌入环境无法也不允许触达，
 * 故场景 2 只钉**缓存命中路径**：用真实 [ImageCacheService] bean 预灌缓存，
 * URL 键故意取非白名单 host（127.0.0.1）——若回归令命中路径误发上游，白名单
 * 守卫会当场把它打成 404、断言即红；MockWebServer 请求计数恒 0 兜底证明零上游。
 *
 * == 场景 3 的「二次启动」口径（任务书允许二选一，此处选组合验证） ==
 * 全上下文只有一个 Spring 环境（@DynamicPropertySource 固化临时目录 + 上下文
 * 缓存，同配置的真·二次 Spring 启动不可行且代价高；纯生命周期已由
 * DownloadDirIndexSnapshotTest 覆盖）。此处钉**跨边界组合**：全上下文
 * （真实 upload → serve → ensureFresh 后台重建）产出的快照文件，由独立组装的
 * DownloadDirIndex 实例（P-S3 测试 seam：`walkCount`/`dirScanCount`/
 * `startVerificationAfterSnapshotLoad`）做二次启动载入——零 walk、内容完整、
 * 损坏回退全量不崩。快照文件本身是全上下文真实写出的，不是单测摆盘。
 *
 * == 同步方式 / 幂等隔离 ==
 * 后台重建用 `awaitBackgroundIdleForTest()`（同一单线程 executor 的 FIFO 空任务）
 * 等收尾；批量落库（1s tick）用短轮询等可见。每测试唯一 gid、@BeforeEach 清库
 * + 清空下载根目录。
 */
@SpringBootTest
@AutoConfigureMockMvc
class Phase2E2ETest {

    companion object {
        private val dbDir = Files.createTempDirectory("av-e2e-phase2-db")
        private val dataRoot = Files.createTempDirectory("av-e2e-phase2-data")
        private val downloadsDir = Files.createDirectories(dataRoot.resolve("downloads"))

        /** 各测试独占 gid（特殊测试值段，与真实 gid 空间隔绝）。 */
        private const val GID_ETAG = 920_262_001L // 场景 1：强 ETag + 304 + Range 共存
        private const val GID_WEAK = 920_262_002L // 场景 1：无基线弱 ETag（纯磁盘页）
        private const val GID_HEAL = 920_262_003L // 场景 1：heal 后旧 ETag 失效
        private const val GID_SNAPSHOT = 920_262_006L // 场景 3：快照二次启动
        private const val GID_SNAPSHOT2 = 920_262_007L // 场景 3：损坏快照回退
        private const val GID_UPLOAD_DONE = 920_262_004L // 场景 4：上传完成终态立即可查
        private const val GID_FLUSH = 920_262_005L // 场景 4：终态 flush 屏障

        @JvmStatic
        @DynamicPropertySource
        fun e2eProperties(registry: DynamicPropertyRegistry) {
            // 先例：IntegrityE2ETest 的 @DynamicPropertySource 基建（临时 SQLite）。
            registry.add("anotherviewer.data-dir") { dataRoot.toAbsolutePath().toString() }
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:${dbDir.resolve("e2e-phase2.db")}?journal_mode=WAL&busy_timeout=30000"
            }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.community.dialect.SQLiteDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
            // DownloadDirIndex 根指纹检查不节流（≤0 = 每次都检，测试口径）。
            registry.add("anotherviewer.download.dir-index-refresh-ms") { "0" }
        }

        private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

        private fun fixture(name: String): ByteArray =
            Phase2E2ETest::class.java.getResourceAsStream("/integrity/$name")?.readBytes()
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

    /** P-S3：全上下文真实索引 bean（快照由它写出）。 */
    @Autowired
    lateinit var downloadDirIndex: DownloadDirIndex

    /** P-S2：/proxy 缓存命中路径的预灌口（真实 bean = 真实缓存层）。 */
    @Autowired
    lateinit var imageCacheService: ImageCacheService

    /** P-S8：进度批量落库器（record() 即页 worker 的上报入口）。 */
    @Autowired
    lateinit var progressPersister: DownloadProgressPersister

    /** 全上下文绑定后的配置（download.path / dataDir 指向临时目录）。 */
    @Autowired
    lateinit var config: SiteCoreConfigProperties

    /** EH 源 URL 解析边界的测试替身（与 IntegrityE2ETest 同口径）。 */
    @MockitoBean
    lateinit var galleryLookup: GalleryLookupService

    /** 假 EH 源：heal（refresh）修复管线拉取字节的真实 HTTP 边界。 */
    private lateinit var ehSource: MockWebServer

    /** 测试内组装的独立索引实例：tearDown 统一停后台线程。 */
    private val standaloneIndexes = ArrayList<DownloadDirIndex>()

    // ── fixtures 快照 ────────────────────────────────────────────────────────

    private val validJpg by lazy { fixture("valid.jpg") }
    private val validPng by lazy { fixture("valid.png") }
    private val validGif by lazy { fixture("valid.gif") }
    private val truncatedJpg by lazy { fixture("truncated.jpg") }

    private val validJpgHash by lazy { sha256(validJpg) }
    private val validPngHash by lazy { sha256(validPng) }

    // ── 生命周期 ─────────────────────────────────────────────────────────────

    @BeforeEach
    fun setUp() {
        ehSource = MockWebServer()
        ehSource.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(Buffer().write(validJpg))
        }
        ehSource.start()
        // EH 边界桩：任何页码的源 URL 都指向假源（修复字节经真实 OkHttp 拉取）。
        lenient().`when`(
            galleryLookup.fetchImageUrl(anyLong(), anyString(), anyInt())
        ).thenAnswer { ehSource.url("/eh/page.jpg").toString() }

        // 清库 + 清空下载根目录（每测试自摆布局）。
        pageFileHashRepository.deleteAll()
        downloadRepository.deleteAll()
        Files.list(downloadsDir).forEach { path ->
            if (Files.isDirectory(path)) walkTopDownDelete(path) else Files.deleteIfExists(path)
        }
    }

    @AfterEach
    fun tearDown() {
        standaloneIndexes.forEach { it.shutdown() }
        standaloneIndexes.clear()
        ehSource.shutdown()
    }

    private fun walkTopDownDelete(path: Path) {
        Files.walk(path).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ── HTTP / 摆盘工具（全走真实控制器端点） ────────────────────────────────

    /** 上传链路：init → 逐页 multipart → complete（App push-of-local-downloads 同款）。 */
    private fun uploadGallery(gid: Long, title: String, pages: Map<Int, ByteArray>) {
        initUpload(gid, title, pages.size)
        pages.forEach { (page, bytes) -> uploadPage(gid, page, bytes) }
        // 契约 200 回执为 code=OK 的 ApiErrorEnvelope（success 语义在 init 响应）。
        mockMvc.perform(
            post("/api/v1/download/upload/$gid/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"total":${pages.size},"done":${pages.size}}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.error.code").value("OK"))
    }

    /** 只 init + 传页、不 complete：行停在 state=2（下载中），供终态链路摆盘。 */
    private fun initUpload(gid: Long, title: String, pageCount: Int) {
        mockMvc.perform(
            put("/api/v1/download/upload/$gid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"e2e-tok-$gid","title":"$title","pages":$pageCount}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    private fun uploadPage(gid: Long, page: Int, bytes: ByteArray) {
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

    /** 上传落盘目录（initUpload 派生的绝对路径 `{root}/{gid}-{title}`）。 */
    private fun galleryDir(gid: Long): Path {
        val dir = downloadRepository.findAllByGid(gid).firstOrNull()?.downloadDir
            ?: error("gid=$gid 无下载行")
        return Path.of(dir)
    }

    /** 独立组装的二次启动索引（seam：零 walk 断言；tearDown 统一停线程）。 */
    private fun newStandaloneIndex(): DownloadDirIndex =
        DownloadDirIndex(config, 60_000).also { standaloneIndexes.add(it) }

    /** 短轮询直至条件成立（1s 批量 tick 的可见性等待）。 */
    private fun awaitUntil(timeoutMs: Long = 8_000, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue(cond(), "未在时限内满足：$what")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 场景 1a：强 ETag 端到端——上传基线 → 200 强 ETag → 304 → Range 共存
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `pool page serves strong baseline etag, 304s on if-none-match and prefers it over range`() {
        val gid = GID_ETAG
        uploadGallery(gid, "Phase2 ETag", mapOf(1 to validJpg, 2 to validPng))
        val strongEtag = "\"sha256:$validJpgHash\""
        val staleEtag = "\"sha256:${"0".repeat(64)}\""

        // 首次 GET：池页 200 + 强 ETag（基线 sha256）+ 池页缓存头。
        val first = mockMvc.perform(get("/api/v1/image/$gid/0"))
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", strongEtag))
            .andExpect(header().string("Cache-Control", "max-age=86400"))
            .andExpect(header().string("Accept-Ranges", "bytes"))
            .andExpect(header().string("Content-Type", MediaType.IMAGE_JPEG_VALUE))
            .andReturn()
        assertTrue(first.response.contentAsByteArray.contentEquals(validJpg), "池页字节应逐字节等于上传页")

        // 二次请求带 If-None-Match → 304 空体，携带同 ETag 与缓存头（RFC 7232 §4.1）。
        val notModified = mockMvc.perform(get("/api/v1/image/$gid/0").header(HttpHeaders.IF_NONE_MATCH, strongEtag))
            .andExpect(status().isNotModified)
            .andExpect(header().string("ETag", strongEtag))
            .andExpect(header().string("Cache-Control", "max-age=86400"))
            .andReturn()
        assertTrue(notModified.response.contentAsByteArray.isEmpty(), "304 必须空体")

        // 校验器语义：`*` 与逗号列表均按弱比较命中。
        mockMvc.perform(get("/api/v1/image/$gid/0").header(HttpHeaders.IF_NONE_MATCH, "*"))
            .andExpect(status().isNotModified)
        mockMvc.perform(
            get("/api/v1/image/$gid/0").header(HttpHeaders.IF_NONE_MATCH, "$staleEtag, $strongEtag")
        )
            .andExpect(status().isNotModified)
        // 不匹配的校验器 → 照常 200（校验器原样回带）。
        mockMvc.perform(get("/api/v1/image/$gid/0").header(HttpHeaders.IF_NONE_MATCH, staleEtag))
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", strongEtag))

        // Range + If-None-Match 共存：条件命中 → 304 优先于 Range（RFC 7232 §6）。
        mockMvc.perform(
            get("/api/v1/image/$gid/0")
                .header(HttpHeaders.RANGE, "bytes=0-9")
                .header(HttpHeaders.IF_NONE_MATCH, strongEtag)
        )
            .andExpect(status().isNotModified)
        // 条件不命中 → Range 照常 206，且 206 同样携带 ETag。
        val partial = mockMvc.perform(
            get("/api/v1/image/$gid/0")
                .header(HttpHeaders.RANGE, "bytes=0-9")
                .header(HttpHeaders.IF_NONE_MATCH, staleEtag)
        )
            .andExpect(status().isPartialContent)
            .andExpect(header().string("Content-Range", "bytes 0-9/${validJpg.size}"))
            .andExpect(header().string("Content-Length", "10"))
            .andExpect(header().string("Accept-Ranges", "bytes"))
            .andExpect(header().string("ETag", strongEtag))
            .andReturn()
        assertTrue(partial.response.contentAsByteArray.contentEquals(validJpg.copyOfRange(0, 10)))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 场景 1b：无基线页（纯磁盘页，无下载行）→ 弱 size-mtime ETag
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `pool page without a hash baseline serves a weak size-mtime etag`() {
        val gid = GID_WEAK
        // 摆一个上传链路之外的纯磁盘页：无下载行、无基线（外部拷贝进池的形态）。
        val dir = downloadsDir.resolve(gid.toString())
        Files.createDirectories(dir)
        File(dir.toFile(), "0001.jpg").writeBytes(validJpg)
        assertTrue(downloadRepository.findAllByGid(gid).isEmpty())
        assertNull(pageFileHashRepository.findByGidAndPage(gid, 0))

        val first = mockMvc.perform(get("/api/v1/image/$gid/0"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "max-age=86400"))
            .andReturn()
        assertTrue(first.response.contentAsByteArray.contentEquals(validJpg))

        val etag = first.response.getHeader("ETag")
        assertNotNull(etag, "无基线页必须带弱 ETag 兜底")
        assertTrue(Regex("""^W/"\d+-\d+"$""").matches(etag!!), "弱 ETag 形态 W/\"<size>-<mtime>\"，实际：$etag")

        // 弱比较命中 → 304（If-None-Match 忽略 W/ 只比 opaque）。
        mockMvc.perform(get("/api/v1/image/$gid/0").header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isNotModified)
            .andExpect(header().string("ETag", etag))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 场景 1c：heal（refresh 端点）换基线哈希 → 旧 ETag 失效，新 ETag 生效
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `heal refresh invalidates the old strong etag and issues the repaired one`() {
        val gid = GID_HEAL
        // 页 1 传 PNG（基线哈希 = validPngHash），heal 后源回 validJpg → 基线换哈希。
        uploadGallery(gid, "Phase2 Heal", mapOf(1 to validPng, 2 to validGif))
        val oldEtag = "\"sha256:$validPngHash\""
        val newEtag = "\"sha256:$validJpgHash\""

        mockMvc.perform(get("/api/v1/image/$gid/0"))
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", oldEtag))
        mockMvc.perform(get("/api/v1/image/$gid/0").header(HttpHeaders.IF_NONE_MATCH, oldEtag))
            .andExpect(status().isNotModified)

        // 磁盘注入损坏：强 ETag 跟基线走（校验器不跟磁盘字节走——损坏由完整性
        // 管线发现，ETag 不抢它的活），旧 ETag 此刻仍命中。
        galleryDir(gid).resolve("00000001.png").toFile().writeBytes(truncatedJpg)
        mockMvc.perform(get("/api/v1/image/$gid/0").header(HttpHeaders.IF_NONE_MATCH, oldEtag))
            .andExpect(status().isNotModified)

        // heal：refresh 端点拉源（MockWebServer 回 validJpg）覆写并刷新基线。
        mockMvc.perform(post("/api/v1/integrity/refresh/$gid/1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("healed"))
        assertEquals(validJpgHash, pageFileHashRepository.findByGidAndPage(gid, 0)!!.hash, "heal 应把基线刷成修复后字节")

        // 旧 ETag 不再命中 → 200 新 ETag + 修复后字节（客户端旧缓存不再误命中——
        // 这正是选强 ETag 的根本原因）。
        val healed = mockMvc.perform(get("/api/v1/image/$gid/0").header(HttpHeaders.IF_NONE_MATCH, oldEtag))
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", newEtag))
            .andReturn()
        assertTrue(healed.response.contentAsByteArray.contentEquals(validJpg), "heal 后应 serve 修复后的新字节")

        // 新 ETag 生效 → 304。
        mockMvc.perform(get("/api/v1/image/$gid/0").header(HttpHeaders.IF_NONE_MATCH, newEtag))
            .andExpect(status().isNotModified)
            .andExpect(header().string("ETag", newEtag))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 场景 2：/proxy 缓存头——Cache-Control + 弱 ETag，命中路径 304 零上游
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `proxy serves cache headers with weak etag and 304s on hit without any upstream request`() {
        // 缓存键故意用非白名单 host：若命中路径回归出「误发上游」，白名单守卫
        // 会当场 404（断言即红）——200 本身就是「来自缓存」的证明。
        val url = "https://127.0.0.1:1/phase2/cover.jpg"
        imageCacheService.cacheImage(url, validJpg)
        val originalEtag = HttpCacheSupport.proxyEtag(url) // W/"<sha256(url)>"

        // 首次 GET（缓存命中）：Cache-Control + 弱 ETag + 原字节。
        mockMvc.perform(get("/api/v1/image/proxy").param("url", url))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "public, max-age=86400"))
            .andExpect(header().string("ETag", originalEtag))
            .andExpect(header().string("Content-Type", MediaType.IMAGE_JPEG_VALUE))
            .andReturn().response.contentAsByteArray.let { assertTrue(it.contentEquals(validJpg)) }

        // 同 URL 二次 If-None-Match → 304（缓存命中路径，不触发上游）。
        mockMvc.perform(get("/api/v1/image/proxy").param("url", url).header(HttpHeaders.IF_NONE_MATCH, originalEtag))
            .andExpect(status().isNotModified)
            .andExpect(header().string("ETag", originalEtag))
            .andExpect(header().string("Cache-Control", "public, max-age=86400"))

        // w= 变体各拿各的 ETag（thumb:w{n}:<url> 键），互不命中。
        val scaledKey = ThumbnailScaler.thumbnailCacheKey(url, 120)
        imageCacheService.cacheImage(scaledKey, validPng)
        val scaledEtag = HttpCacheSupport.weakEtagForKey(scaledKey)
        mockMvc.perform(get("/api/v1/image/proxy").param("url", url).param("w", "120"))
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", scaledEtag))
            .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE))
        // 原尺寸 ETag 打在 w= 变体上不命中 → 200；变体自身 ETag → 304。
        mockMvc.perform(
            get("/api/v1/image/proxy").param("url", url).param("w", "120")
                .header(HttpHeaders.IF_NONE_MATCH, originalEtag)
        )
            .andExpect(status().isOk)
        mockMvc.perform(
            get("/api/v1/image/proxy").param("url", url).param("w", "120")
                .header(HttpHeaders.IF_NONE_MATCH, scaledEtag)
        )
            .andExpect(status().isNotModified)

        // 零上游：假 EH 源零请求 + URL 解析边界零调用（缓存命中全程不碰网络）。
        assertEquals(0, ehSource.requestCount, "缓存命中路径不得产生任何上游请求")
        verify(galleryLookup, never()).fetchImageUrl(anyLong(), anyString(), anyInt())
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 场景 3：快照启动——全上下文产出快照 → 二次启动零 walk；损坏回退全量
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `snapshot written by the running context boots a second index with zero walk`() {
        val gid = GID_SNAPSHOT
        uploadGallery(gid, "Phase2 Snapshot", mapOf(1 to validJpg, 2 to validPng, 3 to validGif))

        // serve 一次：查询触发 ensureFresh（interval=0）→ 后台全量重建 → 覆写快照。
        mockMvc.perform(get("/api/v1/image/$gid/0")).andExpect(status().isOk)
        downloadDirIndex.awaitBackgroundIdleForTest()

        val snapshotFile = File(config.dataDir, DirIndexSnapshotStore.SNAPSHOT_FILE_NAME)
        assertTrue(snapshotFile.isFile, "全上下文启动+上传后必须落 <dataDir>/dir-index.snapshot")
        assertNotNull(
            DirIndexSnapshotStore(config.dataDir).load(File(config.download.path).canonicalPath),
            "上下文写出的快照必须可通过载入校验（root 匹配）",
        )

        // 二次启动（独立组合实例，校验 walk 关闭）：零 walk、零重扫、内容完整。
        val second = newStandaloneIndex()
        second.startVerificationAfterSnapshotLoad = false
        second.loadAll()
        assertEquals(0, second.walkCount, "快照启动必须零 walk")
        assertEquals(0, second.dirScanCount, "快照启动必须零目录重扫")
        assertEquals(3, second.pageCount(gid), "快照载入后画廊页数完整")
        assertEquals("00000001.jpg", second.findPage(gid, 0)!!.fileName)
        assertEquals("00000002.png", second.findPage(gid, 1)!!.fileName)
        assertEquals("00000003.gif", second.findPage(gid, 2)!!.fileName)
        assertEquals(
            galleryDir(gid).toString(),
            second.dirFor(gid)!!.path,
            "快照载入的目录应与下载行的落盘目录一致",
        )
    }

    @Test
    fun `corrupted snapshot falls back to a full walk without crashing and is rewritten`() {
        val gid = GID_SNAPSHOT2
        uploadGallery(gid, "Phase2 Corrupt", mapOf(1 to validJpg, 2 to validPng))
        mockMvc.perform(get("/api/v1/image/$gid/0")).andExpect(status().isOk)
        downloadDirIndex.awaitBackgroundIdleForTest()

        // 乱字节覆写快照（模拟半写/位损坏）。
        val store = DirIndexSnapshotStore(config.dataDir)
        assertTrue(store.file.isFile)
        store.file.writeBytes("phase2-garbage-over-snapshot".toByteArray())

        // 二次启动：回退同步全量 walk（原行为），内容照常可用、不崩。
        val index = newStandaloneIndex()
        index.loadAll()
        assertEquals(1, index.walkCount, "损坏快照必须回退一次全量 walk")
        assertEquals(2, index.pageCount(gid))
        assertEquals("00000001.jpg", index.findPage(gid, 0)!!.fileName)

        // 回退 walk 后快照被重写为可用（下一次启动恢复零 walk 路径）。
        assertNotNull(store.load(File(config.download.path).canonicalPath), "回退后快照必须被重写为可用")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 场景 4：下载进度链路——上传终态立即可查 + S8 批量落库/终态 flush 屏障
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `upload completion makes state total and done immediately visible over http`() {
        val gid = GID_UPLOAD_DONE
        uploadGallery(gid, "Phase2 UploadDone", mapOf(1 to validJpg, 2 to validPng))

        // completeUpload 同步写终态：返回即查（HTTP 边界 + 落库双口径），零轮询。
        val row = downloadRepository.findAllByGid(gid).first()
        assertEquals(3, row.state)
        assertEquals(2, row.total)
        assertEquals(2, row.done)
        mockMvc.perform(get("/api/v1/download/info/${row.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value(3))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.done").value(2))
    }

    @Test
    fun `batched progress lands via the tick and the terminal completion flushes it for good`() {
        val gid = GID_FLUSH
        // init + 传页、不 complete → state=2 下载中行；补 total 模拟 worker 维护态。
        initUpload(gid, "Phase2 Flush", 2)
        uploadPage(gid, 1, validJpg)
        uploadPage(gid, 2, validPng)
        val row = downloadRepository.findAllByGid(gid).first()
        assertEquals(2, row.state)
        row.total = 2
        downloadRepository.save(row)

        // S8 批量落库端到端：record()（页 worker 上报入口）→ 1s tick → 落库可查。
        progressPersister.record(row.id, 1)
        awaitUntil(what = "批量 tick 把 done=1 落库") { downloadRepository.findAllByGid(gid).first().done == 1 }

        // 再压一条待写旧帧，然后走真实阅读端点命中池页 → completeIfVerified：
        // 先 flush（清空该 id 内存帧）再写终态 state=3/done=total。
        progressPersister.record(row.id, 1)
        mockMvc.perform(get("/api/v1/image/$gid/0"))
            .andExpect(status().isOk)
        val terminal = downloadRepository.findAllByGid(gid).first()
        assertEquals(3, terminal.state, "阅读命中磁盘校验通过应完成化")
        assertEquals(2, terminal.done, "终态 done=total 立即可查（flush 屏障后旧帧绝无复活）")
        assertEquals(2, terminal.total)

        // 终态 flush 语义的时序面：跨过一个批量周期（1s）后终态依旧——若 flush
        // 未清帧，tick 会把旧帧 done=1 复活盖掉终态。
        Thread.sleep(1_300)
        val settled = downloadRepository.findAllByGid(gid).first()
        assertEquals(2, settled.done, "终态写后跨批量周期不得被旧帧复活")
        assertEquals(3, settled.state)

        // HTTP 边界同样立即可查（下载页/列表的数据源）。
        mockMvc.perform(get("/api/v1/download/info/${terminal.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value(3))
            .andExpect(jsonPath("$.done").value(2))
            .andExpect(jsonPath("$.total").value(2))
    }
}
