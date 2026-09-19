package com.hippo.anotherviewer.web.api

import com.hippo.anotherviewer.web.config.GlobalExceptionHandler
import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.dto.JobState
import com.hippo.anotherviewer.web.dto.JobType
import com.hippo.anotherviewer.web.entity.DownloadInfoEntity
import com.hippo.anotherviewer.web.entity.PageFileHashEntity
import com.hippo.anotherviewer.web.entity.ScrubRunEntity
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import com.hippo.anotherviewer.web.repository.PeerHashRepository
import com.hippo.anotherviewer.web.repository.ScrubRunRepository
import com.hippo.anotherviewer.web.repository.ServerConfigRepository
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.EncryptionService
import com.hippo.anotherviewer.web.service.InMemoryJobStore
import com.hippo.anotherviewer.web.service.Job
import com.hippo.anotherviewer.web.service.JobService
import com.hippo.anotherviewer.web.service.ServerConfigService
import com.hippo.anotherviewer.web.service.integrity.BackfillService
import com.hippo.anotherviewer.web.service.integrity.RepairResult
import com.hippo.anotherviewer.web.service.integrity.ReverifyStats
import com.hippo.anotherviewer.web.service.integrity.StorageIntegrityService
import com.hippo.anotherviewer.web.service.storage.StorageProfileService
import com.hippo.anotherviewer.web.service.storage.StorageTuning
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/**
 * 契约测试 for [IntegrityController]（文件完整性 Wave 2 / S5 + Wave 3 / S6）。
 *
 * 切片 = @DataJpaTest 真实 SQLite 临时库 + [Import] 真控制器与其服务 + standalone
 * MockMvc。测试类 NOT_SUPPORTED——MockMvc 请求线程与测试方法共用线程但事务边界
 * 不同，外层回滚事务会让请求事务看不到未提交的种子行，故种子/断言一律各自短事务
 * 即时提交（@BeforeEach 清库 + 每 test 独占 gid 双保险）。后台路径（scrub 线程、
 * Job worker 线程）的写库各自独立提交，与 NOT_SUPPORTED 兼容。
 *
 * S7（DownloadService.forceRefetchPage / reverifyGallery）经 [ReverifyRepairOps]
 * 窄接口注入，测试用 [StubReverifyOps] 桩——refresh/reverify 端点验证的是控制器
 * 侧的校验、换算与映射；S7 本体的行为归 S7 自己的测试。
 *
 * 红线断言：peer 上送前后 page_file_hash 基线逐字段零变化（C1 契约：
 * peer 证据绝不触碰己方基线）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(IntegrityController::class, IntegrityControllerTest.WebIntegrityConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IntegrityControllerTest {

    companion object {
        private val dbDir = Files.createTempDirectory("av-integrity-api")
        private val dataRoot = Files.createTempDirectory("av-integrity-data")
        private val downloadsDir = Files.createDirectories(dataRoot.resolve("downloads"))

        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) {
            registry.add("anotherviewer.data-dir") { dataRoot.toAbsolutePath().toString() }
            registry.add("spring.datasource.url") { "jdbc:sqlite:${dbDir.resolve("integrity-api.db")}" }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.community.dialect.SQLiteDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
        }
    }

    /**
     * 控制器新增依赖的手工装配（@DataJpaTest 切片只提供仓库）：路径经
     * `anotherviewer.data-dir` 动态属性绑定（ConfigurationProperties 会按环境重绑，
     * 见 BackfillServiceTest 的注释）；StorageTuning 用未探测的
     * StorageProfileService（UNKNOWN → HDD 保守参数）。
     */
    @TestConfiguration
    class WebIntegrityConfig {
        @Bean
        fun siteCoreConfigProperties(): SiteCoreConfigProperties = SiteCoreConfigProperties()

        @Bean
        fun downloadDirIndex(config: SiteCoreConfigProperties): DownloadDirIndex = DownloadDirIndex(config)

        @Bean
        fun serverConfigService(
            repo: ServerConfigRepository,
            config: SiteCoreConfigProperties,
        ): ServerConfigService = ServerConfigService(repo, mock(EncryptionService::class.java), config)

        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()

        @Bean
        fun storageTuning(): StorageTuning = StorageTuning(StorageProfileService())

        @Bean
        fun storageIntegrityService(
            config: SiteCoreConfigProperties,
            dirIndex: DownloadDirIndex,
            hashRepository: PageFileHashRepository,
            peerHashRepository: PeerHashRepository,
            scrubRunRepository: ScrubRunRepository,
            serverConfigService: ServerConfigService,
            tuning: StorageTuning,
            objectMapper: ObjectMapper,
        ): StorageIntegrityService =
            StorageIntegrityService(config, dirIndex, hashRepository, peerHashRepository, scrubRunRepository, serverConfigService, tuning, objectMapper)

        @Bean
        fun backfillService(
            config: SiteCoreConfigProperties,
            dirIndex: DownloadDirIndex,
            hashRepository: PageFileHashRepository,
            downloadRepository: DownloadInfoRepository,
            serverConfigService: ServerConfigService,
        ): BackfillService =
            BackfillService(config, dirIndex, hashRepository, downloadRepository, serverConfigService)

        @Bean
        fun jobService(): JobService = JobService(InMemoryJobStore(), ApplicationEventPublisher { })

        @Bean
        fun reverifyOps(): StubReverifyOps = StubReverifyOps()
    }

    @Autowired
    lateinit var controller: IntegrityController

    @Autowired
    lateinit var peerHashRepository: PeerHashRepository

    @Autowired
    lateinit var pageFileHashRepository: PageFileHashRepository

    @Autowired
    lateinit var downloadRepository: DownloadInfoRepository

    @Autowired
    lateinit var scrubRunRepository: ScrubRunRepository

    @Autowired
    lateinit var storageIntegrityService: StorageIntegrityService

    @Autowired
    lateinit var jobService: JobService

    @Autowired
    lateinit var stub: StubReverifyOps

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
        peerHashRepository.deleteAll()
        pageFileHashRepository.deleteAll()
        downloadRepository.deleteAll()
        scrubRunRepository.deleteAll()
        stub.refreshResult = RepairResult(RepairResult.STATUS_HEALED, RepairResult.ATTR_LOCAL_CORRUPT)
        stub.lastRefreshPage = null
        stub.verifyBehavior = { _, _ -> ReverifyStats(0, 0, 0, 0, interrupted = false) }
        storageIntegrityService.testPause = null
        storageIntegrityService.testStopAfterRows = null
        Files.list(downloadsDir).forEach { path ->
            if (Files.isDirectory(path)) walkTopDownDelete(path) else Files.deleteIfExists(path)
        }
    }

    private fun walkTopDownDelete(path: Path) {
        Files.walk(path).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ── fixtures ─────────────────────────────────────────────────

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/integrity/$name")?.readBytes()
            ?: error("classpath 缺少 fixture /integrity/$name")

    private fun makeGallery(gid: Long, files: Map<String, ByteArray>) {
        val dir = downloadsDir.resolve(gid.toString())
        Files.createDirectories(dir)
        files.forEach { (name, bytes) -> Files.write(dir.resolve(name), bytes) }
    }

    private fun seedDownload(gid: Long) {
        downloadRepository.save(
            DownloadInfoEntity().apply {
                this.gid = gid
                token = "t$gid"
                state = 3
                total = 10
                done = 10
                time = 0
                lastModified = 0
            }
        )
    }

    private fun seedBaseline(gid: Long, page: Int, hash: String): PageFileHashEntity =
        pageFileHashRepository.save(
            PageFileHashEntity().apply {
                this.gid = gid
                this.page = page
                ext = "jpg"
                size = 2048
                this.hash = hash
                algo = "sha256"
                origin = "upload"
                createdAt = 1_000L
                lastVerifiedAt = 2_000L
                verdict = "ok"
            }
        )

    private fun seedStructBad(gid: Long, page: Int, hash: String): PageFileHashEntity =
        pageFileHashRepository.save(
            PageFileHashEntity().apply {
                this.gid = gid
                this.page = page
                ext = "jpg"
                size = 4096
                this.hash = hash
                algo = "sha256"
                origin = "downloader"
                createdAt = 1_000L
                verdict = "struct_bad"
            }
        )

    private fun seedRun(badPagesJson: String, divergencesJson: String): ScrubRunEntity =
        scrubRunRepository.save(
            ScrubRunEntity().apply {
                startedAt = 5_000L
                finishedAt = 6_000L
                status = ScrubRunEntity.STATUS_COMPLETED
                totalPages = 3
                okPages = 1
                badPages = 2
                divergences = 1
                oneSided = 2
                this.badPagesJson = badPagesJson
                this.divergencesJson = divergencesJson
            }
        )

    private fun push(gid: Long, body: String) =
        mockMvc.perform(
            post("/api/v1/integrity/hashes/$gid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )

    private fun entry(page: Int, hash: String = "a".repeat(64), algo: String? = null, ext: String = "jpg", size: Long = 2048) =
        buildString {
            append("""{"page":$page,"ext":"$ext","size":$size,"hash":"$hash"""")
            if (algo != null) append(""","algo":"$algo"""")
            append("}")
        }

    private fun postJson(path: String, body: String?) =
        mockMvc.perform(
            post(path).apply {
                contentType(MediaType.APPLICATION_JSON)
                if (body != null) content(body)
            }
        )

    private fun awaitJobCompleted(jobId: String, timeoutMs: Long = 10_000): Job {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            jobService.getJob(jobId)?.takeIf { it.state == JobState.COMPLETED }?.let { return it }
            Thread.sleep(20)
        }
        error("job $jobId 未在时限内完成")
    }

    private fun awaitScrubIdle(timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (storageIntegrityService.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    // ── 合法上送（S5 原有行为回归） ───────────────────────────────

    @Test
    fun `valid push returns accepted count and lands peer_hash rows at 0-based pages`() {
        seedDownload(gid = 5101)
        val upperHash = "AB".repeat(32) // 契约 pattern 允许大写；落库应归一小写

        push(
            5101,
            "[" +
                entry(1, hash = "a".repeat(64)) + "," +
                entry(2, hash = upperHash) + "," +
                entry(3, hash = "c".repeat(64), algo = "SHA-256") +
                "]"
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(3))

        assertEquals(3, peerHashRepository.findByGid(5101).size, "三条上送应落三行 peer_hash")
        val row1 = peerHashRepository.findByGidAndPage(5101, 0)!!
        assertEquals("a".repeat(64), row1.hash)
        assertTrue(row1.updatedAt > 0, "updatedAt 应为落库时刻 now")
        // 契约 1-based page=2 → peer_hash 0-based page=1（对齐基线与 App 侧口径）
        assertEquals(upperHash.lowercase(), peerHashRepository.findByGidAndPage(5101, 1)!!.hash)
        assertTrue(peerHashRepository.findByGidAndPage(5101, 2)!!.updatedAt > 0)
    }

    @Test
    fun `repeated push upserts the same page without growing rows`() {
        seedDownload(gid = 5102)
        push(5102, "[${entry(4, hash = "a".repeat(64))}]")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(1))

        push(5102, "[${entry(4, hash = "b".repeat(64))}]")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(1))

        val rows = peerHashRepository.findByGid(5102)
        assertEquals(1, rows.size, "同 (gid,page) 二次上送应整行覆盖不增行")
        assertEquals("b".repeat(64), rows.single().hash)
    }

    @Test
    fun `empty list is a no-op returning accepted zero`() {
        seedDownload(gid = 5103)

        push(5103, "[]")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(0))

        assertTrue(peerHashRepository.findByGid(5103).isEmpty(), "空清单不得落任何行")
    }

    // ── 非法载荷 → 400（S5 原有行为回归） ─────────────────────────

    @Test
    fun `malformed hash returns 400 INTEGRITY_INVALID_HASH and nothing lands`() {
        seedDownload(gid = 5201)

        push(5201, "[${entry(1)}, ${entry(2, hash = "xyz")}]")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INTEGRITY_INVALID_HASH"))
            .andExpect(jsonPath("$.error.status").value(400))
            .andExpect(jsonPath("$.error.traceId").exists())

        assertTrue(peerHashRepository.findByGid(5201).isEmpty(), "任一条非法即整批不落库")
    }

    @Test
    fun `wrong hash length returns 400 INTEGRITY_INVALID_HASH`() {
        seedDownload(gid = 5202)

        push(5202, "[${entry(1, hash = "a".repeat(63))}]")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INTEGRITY_INVALID_HASH"))
    }

    @Test
    fun `unsupported algo returns 400 INTEGRITY_INVALID_HASH`() {
        seedDownload(gid = 5203)

        push(5203, "[${entry(1, algo = "MD5")}]")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INTEGRITY_INVALID_HASH"))
    }

    @Test
    fun `page below one returns 400 INTEGRITY_INVALID_PAGE`() {
        seedDownload(gid = 5204)

        push(5204, "[${entry(0)}]")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INTEGRITY_INVALID_PAGE"))

        assertTrue(peerHashRepository.findByGid(5204).isEmpty(), "page 非法即整批不落库")
    }

    @Test
    fun `push over the entry cap returns 400`() {
        seedDownload(gid = 5205)
        val entries = (1..(10_000 + 1)).joinToString(",") { entry(it) }

        push(5205, "[$entries]")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))

        assertTrue(peerHashRepository.findByGid(5205).isEmpty())
    }

    @Test
    fun `malformed body returns 400 BAD_REQUEST envelope`() {
        seedDownload(gid = 5206)

        push(5206, """[{"page":1,"hash":"a"""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
    }

    // ── gid 存在性 → 404（S5 原有行为回归） ───────────────────────

    @Test
    fun `unknown gid returns 404 INTEGRITY_NOT_FOUND`() {
        seedDownload(gid = 5301) // 证明库里「有行」的 gid 不误伤

        push(9999, "[${entry(1)}]")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("INTEGRITY_NOT_FOUND"))
            .andExpect(jsonPath("$.error.status").value(404))
            .andExpect(jsonPath("$.error.message").value("No local download row for gid=9999"))

        assertTrue(peerHashRepository.findByGid(9999).isEmpty())
        assertTrue(peerHashRepository.findByGid(5301).isEmpty(), "被拒 gid 不得落行")
    }

    // ── 红线：page_file_hash 基线零变化（S5 原有行为回归） ─────────

    @Test
    fun `push never touches the page_file_hash baseline`() {
        seedDownload(gid = 5401)
        val baseline = listOf(
            seedBaseline(5401, page = 0, hash = "f".repeat(64)),
            seedBaseline(5401, page = 1, hash = "e".repeat(64)),
        )
        val snapshot = pageFileHashRepository.findByGid(5401).map {
            listOf(it.gid, it.page, it.ext, it.size, it.hash, it.algo, it.origin, it.createdAt, it.lastVerifiedAt, it.verdict)
        }
        assertEquals(2, snapshot.size)
        assertEquals(baseline.map { it.page }.sorted(), listOf(0, 1))

        // peer 上送与基线同 (gid,page)（1-based page=1/2 ↔ 0-based page=0/1）——
        // 恰是交叉审计最易误写基线的场景。
        push(5401, "[${entry(1, hash = "a".repeat(64))}, ${entry(2, hash = "b".repeat(64))}, ${entry(3, hash = "c".repeat(64))}]")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(3))

        assertEquals(
            snapshot,
            pageFileHashRepository.findByGid(5401).map {
                listOf(it.gid, it.page, it.ext, it.size, it.hash, it.algo, it.origin, it.createdAt, it.lastVerifiedAt, it.verdict)
            },
            "红线：peer 上送不得改动 page_file_hash 基线任何字段",
        )
        assertEquals(3, peerHashRepository.findByGid(5401).size, "peer 证据应照常落行")
    }

    // ── GET /report（S6） ─────────────────────────────────────────

    @Test
    fun `report returns last run with enriched bad pages and divergences`() {
        val gid = 6100L
        seedStructBad(gid, page = 0, hash = "a".repeat(64)) // 快照命中 → READ_ERROR 细分
        seedStructBad(gid, page = 1, hash = "") // 无快照 + 无基线哈希 → MISSING 回退
        seedRun(
            badPagesJson =
                """[{"gid":$gid,"page":1,"verdict":"READ_ERROR","size":123,"hashShort":"0123456789ab"}]""",
            divergencesJson =
                """[{"gid":$gid,"page":2,"localHash":"${"a".repeat(64)}","peerHash":"${"b".repeat(64)}"}]""",
        )

        mockMvc.perform(get("/api/v1/integrity/report"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lastRun.startedAt").value(5_000))
            .andExpect(jsonPath("$.lastRun.finishedAt").value(6_000))
            .andExpect(jsonPath("$.lastRun.totalPages").value(3))
            .andExpect(jsonPath("$.lastRun.badPages").value(2))
            .andExpect(jsonPath("$.lastRun.divergences").value(1))
            .andExpect(jsonPath("$.lastRun.oneSided").value(2))
            .andExpect(jsonPath("$.badPageTotal").value(2))
            .andExpect(jsonPath("$.badPages[0].page").value(1))
            .andExpect(jsonPath("$.badPages[0].verdict").value("READ_ERROR"))
            .andExpect(jsonPath("$.badPages[0].size").value(123))
            .andExpect(jsonPath("$.badPages[0].hashShort").value("0123456789ab"))
            .andExpect(jsonPath("$.badPages[1].page").value(2))
            .andExpect(jsonPath("$.badPages[1].verdict").value("MISSING"))
            .andExpect(jsonPath("$.badPages[1].hashShort").doesNotExist())
            .andExpect(jsonPath("$.divergenceTotal").value(1))
            .andExpect(jsonPath("$.divergences[0].gid").value(gid))
            .andExpect(jsonPath("$.divergences[0].page").value(2))
            .andExpect(jsonPath("$.divergences[0].localHash").value("a".repeat(64)))
            .andExpect(jsonPath("$.divergences[0].peerHash").value("b".repeat(64)))
    }

    @Test
    fun `report with no scrub run returns null lastRun and empty lists`() {
        mockMvc.perform(get("/api/v1/integrity/report"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lastRun").doesNotExist())
            .andExpect(jsonPath("$.badPages").isEmpty())
            .andExpect(jsonPath("$.badPageTotal").value(0))
            .andExpect(jsonPath("$.divergences").isEmpty())
            .andExpect(jsonPath("$.divergenceTotal").value(0))
    }

    @Test
    fun `report pagination slices both lists independently and clamps parameters`() {
        val gid = 6110L
        seedStructBad(gid, page = 0, hash = "a".repeat(64))
        seedStructBad(gid, page = 1, hash = "b".repeat(64))
        seedRun(
            badPagesJson = "[]",
            divergencesJson =
                """[{"gid":$gid,"page":1,"localHash":"${"a".repeat(64)}","peerHash":"${"c".repeat(64)}"},""" +
                    """{"gid":$gid,"page":2,"localHash":"${"b".repeat(64)}","peerHash":"${"d".repeat(64)}"}]""",
        )

        mockMvc.perform(get("/api/v1/integrity/report").param("page", "1").param("pageSize", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.badPages.length()").value(1))
            .andExpect(jsonPath("$.badPages[0].page").value(2))
            .andExpect(jsonPath("$.badPageTotal").value(2))
            .andExpect(jsonPath("$.divergences.length()").value(1))
            .andExpect(jsonPath("$.divergences[0].page").value(2))
            .andExpect(jsonPath("$.divergenceTotal").value(2))

        // 非法参数：负页按 0、超界 pageSize clamp 到 1..200（这里 0 → 1）。
        mockMvc.perform(get("/api/v1/integrity/report").param("page", "-5").param("pageSize", "0"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.badPages.length()").value(1))
            .andExpect(jsonPath("$.badPageTotal").value(2))
    }

    // ── POST /reverify/{gid}（S6 + S7 接线） ──────────────────────

    @Test
    fun `reverify submit returns 202 and job completes with ReverifyStats as result`() {
        seedDownload(gid = 6201)
        stub.verifyBehavior = { _, _ -> ReverifyStats(3, 2, 1, 1, interrupted = false) }

        val result = postJson("/api/v1/integrity/reverify/6201", null)
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.jobId").exists())
            .andReturn()
        val jobId = ObjectMapper().readTree(result.response.contentAsString).get("jobId").asText()

        val job = awaitJobCompleted(jobId)
        assertEquals(JobType.REVERIFY, job.type)
        assertEquals(
            ReverifyStats(3, 2, 1, 1, interrupted = false),
            job.result,
            "终态统计应挂 JobDto.result（契约 REVERIFY → ReverifyStats）",
        )
    }

    @Test
    fun `reverify interrupt completes the job with partial interrupted stats regardless of gid`() {
        seedDownload(gid = 6202)
        stub.verifyBehavior = { _, cancel ->
            // 卡在第一页直到中断旗标置位（模拟长跑复验）。
            val deadline = System.currentTimeMillis() + 10_000
            while (!cancel() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            ReverifyStats(1, 0, 1, 0, interrupted = true)
        }

        val submit = postJson("/api/v1/integrity/reverify/6202", null)
            .andExpect(status().isAccepted)
            .andReturn()
        val jobId = ObjectMapper().readTree(submit.response.contentAsString).get("jobId").asText()

        // interrupt 与 gid 路径参数无关（9999 无下载行也照常中断）。
        postJson("/api/v1/integrity/reverify/9999", """{"interrupt":true}""")
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.jobId").value(jobId))

        val job = awaitJobCompleted(jobId)
        assertEquals(ReverifyStats(1, 0, 1, 0, interrupted = true), job.result)

        // 任务结束后再中断 → 404 NO_ACTIVE_JOB。
        postJson("/api/v1/integrity/reverify/6202", """{"interrupt":true}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_JOB"))
    }

    @Test
    fun `reverify double submit conflicts 409 CONFLICT`() {
        seedDownload(gid = 6203)
        stub.verifyBehavior = { _, cancel ->
            val deadline = System.currentTimeMillis() + 10_000
            while (!cancel() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            ReverifyStats(1, 0, 0, 0, interrupted = true)
        }

        val first = postJson("/api/v1/integrity/reverify/6203", null)
            .andExpect(status().isAccepted)
            .andReturn()
        val jobId = ObjectMapper().readTree(first.response.contentAsString).get("jobId").asText()

        postJson("/api/v1/integrity/reverify/6203", null)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("CONFLICT"))

        postJson("/api/v1/integrity/reverify/6203", """{"interrupt":true}""")
            .andExpect(status().isAccepted)
        awaitJobCompleted(jobId)
    }

    @Test
    fun `reverify unknown gid returns 404 INTEGRITY_NOT_FOUND`() {
        seedDownload(gid = 6204)

        postJson("/api/v1/integrity/reverify/9999", null)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("INTEGRITY_NOT_FOUND"))
    }

    // ── POST /refresh/{gid}/{page}（S6 + S7 接线） ────────────────

    @Test
    fun `refresh maps healed RepairResult through with 1-based page passthrough`() {
        seedDownload(gid = 6301)
        makeGallery(6301, files = mapOf("00000001.jpg" to fixture("valid.jpg")))
        stub.refreshResult = RepairResult(RepairResult.STATUS_HEALED, RepairResult.ATTR_LOCAL_CORRUPT)

        postJson("/api/v1/integrity/refresh/6301/1", null)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("healed"))
            .andExpect(jsonPath("$.attribution").value("local_corrupt"))

        assertEquals(1, stub.lastRefreshPage, "S7 契约 page 即 1-based API 口径，控制器直传不换算")
    }

    @Test
    fun `refresh maps failed RepairResult with message and untouched local file semantics`() {
        seedDownload(gid = 6302)
        makeGallery(6302, files = mapOf("00000003.png" to fixture("valid.png")))
        stub.refreshResult = RepairResult(RepairResult.STATUS_FAILED, null, "source fetch failed: EH blocked")

        postJson("/api/v1/integrity/refresh/6302/3", null)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("failed"))
            .andExpect(jsonPath("$.attribution").doesNotExist())
            .andExpect(jsonPath("$.message").value("source fetch failed: EH blocked"))

        assertEquals(3, stub.lastRefreshPage)
    }

    @Test
    fun `refresh page below one returns 400 INTEGRITY_INVALID_PAGE`() {
        seedDownload(gid = 6303)

        postJson("/api/v1/integrity/refresh/6303/0", null)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INTEGRITY_INVALID_PAGE"))
    }

    @Test
    fun `refresh unknown gid returns 404 INTEGRITY_NOT_FOUND`() {
        postJson("/api/v1/integrity/refresh/9999/1", null)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("INTEGRITY_NOT_FOUND"))
    }

    @Test
    fun `refresh missing page file returns 404 INTEGRITY_NOT_FOUND`() {
        seedDownload(gid = 6304) // 有行、无目录（契约 404：no row OR no page file）

        postJson("/api/v1/integrity/refresh/6304/1", null)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("INTEGRITY_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("No page file for gid=6304 page=1"))
    }

    // ── POST /scrub（S6） ─────────────────────────────────────────

    @Test
    fun `scrub trigger accepted 202 then conflict 409 while running`() {
        val pause = CountDownLatch(1)
        storageIntegrityService.testPause = pause

        postJson("/api/v1/integrity/scrub", null)
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.accepted").value(true))

        postJson("/api/v1/integrity/scrub", null)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("CONFLICT"))

        pause.countDown()
        awaitScrubIdle()
        val run = scrubRunRepository.findFirstByOrderByIdDesc()
        assertTrue(run != null && run.status == ScrubRunEntity.STATUS_COMPLETED, "后台巡检应跑完并落 scrub_run")
    }

    // ── POST /backfill（S6 + S4 接线） ────────────────────────────

    @Test
    fun `backfill validates required gid and known kind`() {
        postJson("/api/v1/integrity/backfill", """{"kind":"hashes"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))

        postJson("/api/v1/integrity/backfill", """{"kind":"bogus","gid":6401}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `backfill unknown gid returns 404 INTEGRITY_NOT_FOUND`() {
        seedDownload(gid = 6402)

        postJson("/api/v1/integrity/backfill", """{"kind":"hashes","gid":9999}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("INTEGRITY_NOT_FOUND"))
    }

    @Test
    fun `backfill hashes dryRun returns S4 stats synchronously`() {
        seedDownload(gid = 6403) // 无磁盘目录 → 空扫

        postJson("/api/v1/integrity/backfill", """{"kind":"hashes","gid":6403,"dryRun":true}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.gids").value(0))
            .andExpect(jsonPath("$.scanned").value(0))
            .andExpect(jsonPath("$.dryRun").value(true))
    }

    @Test
    fun `backfill pageCounts dryRun reports would-update counts`() {
        seedDownload(gid = 6404) // pages=0，磁盘 2 页 → dryRun 报「将要」更新 1 行
        makeGallery(
            6404,
            files = mapOf(
                "00000001.jpg" to fixture("valid.jpg"),
                "00000002.png" to fixture("valid.png"),
            ),
        )

        postJson("/api/v1/integrity/backfill", """{"kind":"pageCounts","gid":6404,"dryRun":true}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rowsExamined").value(1))
            .andExpect(jsonPath("$.rowsPagesUpdated").value(1))
            .andExpect(jsonPath("$.rowsCompleted").value(0))
            .andExpect(jsonPath("$.dryRun").value(true))
    }
}

/**
 * [ReverifyRepairOps] 的测试桩：refresh 直通可配结果 + 页号捕获；reverify 行为
 * 可配（默认立即空统计，中断测试换成等旗标的行为）。
 */
class StubReverifyOps : ReverifyRepairOps {
    @Volatile var refreshResult: RepairResult =
        RepairResult(RepairResult.STATUS_HEALED, RepairResult.ATTR_LOCAL_CORRUPT)

    @Volatile var lastRefreshPage: Int? = null

    @Volatile var verifyBehavior: ((Long, () -> Boolean) -> ReverifyStats) =
        { _, _ -> ReverifyStats(0, 0, 0, 0, interrupted = false) }

    override fun forceRefetchPage(gid: Long, page: Int): RepairResult {
        lastRefreshPage = page
        return refreshResult
    }

    override fun reverifyGallery(gid: Long, isCancelled: () -> Boolean): ReverifyStats =
        verifyBehavior(gid, isCancelled)
}
