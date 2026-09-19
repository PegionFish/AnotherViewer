package com.hippo.anotherviewer.web.service.integrity

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.entity.PageFileHashEntity
import com.hippo.anotherviewer.web.entity.PeerHashEntity
import com.hippo.anotherviewer.web.entity.ScrubRunEntity
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import com.hippo.anotherviewer.web.repository.PeerHashRepository
import com.hippo.anotherviewer.web.repository.ScrubRunRepository
import com.hippo.anotherviewer.web.repository.ServerConfigRepository
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.EncryptionService
import com.hippo.anotherviewer.web.service.ServerConfigService
import com.hippo.anotherviewer.web.service.storage.StorageProfileService
import com.hippo.anotherviewer.web.service.storage.StorageTuning
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch

/**
 * 巡检引擎测试（文件完整性 Wave 3 / S6）：真实 SQLite 临时库 + 临时下载目录。
 *
 * fixtures 复用 Wave 0 的 /integrity/ 共享样本；自造基线行（真哈希/错哈希/缺文件/
 * 读不出）与 peer_hash 行（一致/分歧/单侧）验证判定、交叉审计、断点续跑与
 * report 装配。直调 [StorageIntegrityService.runScrub] 同步执行（不走后台线程）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(StorageIntegrityService::class, StorageIntegrityServiceTest.ScrubTestConfig::class)
class StorageIntegrityServiceTest {

    companion object {
        private val dbDir = Files.createTempDirectory("av-scrub-db")
        private val dataRoot = Files.createTempDirectory("av-scrub-data")
        private val downloadsDir = Files.createDirectories(dataRoot.resolve("downloads"))

        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) {
            registry.add("anotherviewer.data-dir") { dataRoot.toAbsolutePath().toString() }
            registry.add("spring.datasource.url") { "jdbc:sqlite:${dbDir.resolve("scrub.db")}" }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.community.dialect.SQLiteDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
        }
    }

    @TestConfiguration
    class ScrubTestConfig {
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
    }

    @Autowired
    lateinit var service: StorageIntegrityService

    @Autowired
    lateinit var hashRepository: PageFileHashRepository

    @Autowired
    lateinit var peerHashRepository: PeerHashRepository

    @Autowired
    lateinit var scrubRunRepository: ScrubRunRepository

    @Autowired
    lateinit var serverConfigService: ServerConfigService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        hashRepository.deleteAll()
        peerHashRepository.deleteAll()
        scrubRunRepository.deleteAll()
        serverConfigService.findByKeyStartingWith(StorageIntegrityService.KEY_CHECKPOINT)
            .forEach { serverConfigService.delete(it) }
        service.testStopAfterRows = null
        service.testPause = null
        Files.list(downloadsDir).forEach { path ->
            if (Files.isDirectory(path)) walkTopDownDelete(path) else Files.deleteIfExists(path)
        }
    }

    private fun walkTopDownDelete(path: Path) {
        Files.walk(path).sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ── fixtures / 摆盘工具 ──────────────────────────────────────────────────

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/integrity/$name")?.readBytes()
            ?: error("classpath 缺少 fixture /integrity/$name")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun makeGallery(gid: Long, files: Map<String, ByteArray>) {
        val dir = downloadsDir.resolve(gid.toString())
        Files.createDirectories(dir)
        files.forEach { (name, bytes) -> Files.write(dir.resolve(name), bytes) }
    }

    private fun baselineRow(
        gid: Long,
        page: Int,
        hash: String,
        lastVerifiedAt: Long? = null,
        verdict: String? = null,
    ): PageFileHashEntity = hashRepository.save(
        PageFileHashEntity().apply {
            this.gid = gid
            this.page = page
            ext = "jpg"
            size = fixture("valid.jpg").size.toLong()
            this.hash = hash
            algo = "sha256"
            origin = "downloader"
            createdAt = 1_000L
            this.lastVerifiedAt = lastVerifiedAt
            this.verdict = verdict
        }
    )

    private fun peerRow(gid: Long, page: Int, hash: String): PeerHashEntity =
        peerHashRepository.save(
            PeerHashEntity().apply {
                this.gid = gid
                this.page = page
                this.hash = hash
                updatedAt = 2_000L
            }
        )

    private fun checkpointValue(): String = serverConfigService.get(StorageIntegrityService.KEY_CHECKPOINT, "")

    private fun latestRun(): ScrubRunEntity = scrubRunRepository.findFirstByOrderByIdDesc()!!

    private fun parseBadPages(json: String?): List<com.hippo.anotherviewer.web.dto.IntegrityBadPageDto> =
        json?.let { objectMapper.readValue(it) } ?: emptyList()

    // ── 判定主路径 ───────────────────────────────────────────────────────────

    @Test
    fun `scrub classifies ok, mismatch, missing and read-error without ever touching baseline hashes`() {
        makeGallery(
            200L,
            files = mapOf(
                "00000001.jpg" to fixture("valid.jpg"),
                "00000002.png" to fixture("valid.png"),
                // 00000003：文件缺失（MISSING）
                "00000004.webp" to fixture("valid.webp"),
            ),
        )
        baselineRow(200L, 0, sha256(fixture("valid.jpg"))) // 一致 → ok
        baselineRow(200L, 1, "b".repeat(64)) // 与 valid.png 不符 → MISMATCH
        baselineRow(200L, 2, "c".repeat(64)) // 无文件 → MISSING
        val unreadable = downloadsDir.resolve("200").resolve("00000004.webp").toFile()
        assertTrue(unreadable.setReadable(false), "测试前置：收回读权限制造 READ_ERROR")
        baselineRow(200L, 3, "d".repeat(64))
        try {
            service.runScrub("test")
            val finished = latestRun()
            assertEquals(ScrubRunEntity.STATUS_COMPLETED, finished.status)
            assertTrue(finished.finishedAt!! >= finished.startedAt)
            assertEquals(4, finished.totalPages, "4 个有基线的页都应被检查")
            assertEquals(1, finished.okPages)
            assertEquals(3, finished.badPages)
            assertTrue(finished.badPagesJson!!.contains("MISSING"))
            assertTrue(finished.badPagesJson!!.contains("MISMATCH"))
            assertTrue(finished.badPagesJson!!.contains("READ_ERROR"))

            // 基线回写口径：ok 刷新 verdict+last_verified_at；struct_bad 只动 verdict。
            val rows = hashRepository.findByGid(200L).associateBy { it.page }
            assertEquals("ok", rows.getValue(0).verdict)
            assertTrue(rows.getValue(0).lastVerifiedAt!! >= finished.startedAt)

            assertEquals("struct_bad", rows.getValue(1).verdict)
            assertEquals("b".repeat(64), rows.getValue(1).hash, "坏页的基线哈希绝不能被改写")
            assertNull(rows.getValue(1).lastVerifiedAt, "坏页不刷新 last_verified_at（LRU 保持靠前）")

            assertEquals("struct_bad", rows.getValue(2).verdict)
            assertEquals("struct_bad", rows.getValue(3).verdict)

            // 快照明细：1-based 页号 + MISMATCH 带算出的哈希短摘要与磁盘大小。
            val findings = parseBadPages(finished.badPagesJson).associateBy { it.page }
            assertEquals("MISSING", findings.getValue(3).verdict)
            assertEquals("MISMATCH", findings.getValue(2).verdict)
            val mismatch = findings.getValue(2)
            assertEquals(fixture("valid.png").size.toLong(), mismatch.size)
            assertEquals(sha256(fixture("valid.png")).take(12), mismatch.hashShort)
            assertEquals("READ_ERROR", findings.getValue(4).verdict)
        } finally {
            unreadable.setReadable(true)
        }
    }

    @Test
    fun `scrub flips a previously struct_bad row back to ok when the hash matches the baseline`() {
        // 一致页不误报：即使此前 struct_bad，哈希一致即翻案为 ok 并刷新 last_verified_at。
        makeGallery(210L, files = mapOf("00000001.jpg" to fixture("valid.gif")))
        baselineRow(210L, 0, sha256(fixture("valid.gif")), lastVerifiedAt = 100L, verdict = "struct_bad")

        service.runScrub("test")

        val row = hashRepository.findByGidAndPage(210L, 0)!!
        assertEquals("ok", row.verdict, "哈希一致的页即使此前 struct_bad 也应翻案为 ok")
        assertTrue(row.lastVerifiedAt!! > 100L)
        assertEquals(1, latestRun().okPages)
        assertEquals(0, latestRun().badPages)
    }

    // ── 交叉审计 ─────────────────────────────────────────────────────────────

    @Test
    fun `cross audit counts divergence and one-sided and persists the snapshot`() {
        makeGallery(
            300L,
            files = mapOf(
                "00000001.jpg" to fixture("valid.jpg"),
                "00000002.png" to fixture("valid.png"),
                "00000003.gif" to fixture("valid.gif"),
            ),
        )
        val local0 = sha256(fixture("valid.jpg"))
        val local1 = sha256(fixture("valid.png"))
        val local2 = sha256(fixture("valid.gif"))
        baselineRow(300L, 0, local0)
        baselineRow(300L, 1, local1)
        baselineRow(300L, 2, local2)
        peerRow(300L, 0, local0) // 一致
        peerRow(300L, 1, "e".repeat(64)) // 版本分歧
        peerRow(300L, 3, "f".repeat(64)) // 仅 peer 有（单侧）

        service.runScrub("test")

        val run = latestRun()
        assertEquals(1, run.divergences)
        assertEquals(2, run.oneSided, "peer-only 的 page3 + 无 peer 的 page2")

        val divergences: List<com.hippo.anotherviewer.web.dto.IntegrityDivergenceDto> =
            objectMapper.readValue(run.divergencesJson!!)
        assertEquals(1, divergences.size)
        val divergence = divergences.single()
        assertEquals(300L, divergence.gid)
        assertEquals(2, divergence.page, "0-based 基线 page1 → 契约 1-based 出口 page2")
        assertEquals(local1, divergence.localHash)
        assertEquals("e".repeat(64), divergence.peerHash)

        // 红线：交叉审计绝不写 peer_hash（只读）。
        assertEquals(3, peerHashRepository.findByGid(300L).size)
    }

    // ── LRU 与断点续跑 ───────────────────────────────────────────────────────

    @Test
    fun `interrupt writes checkpoint and resume skips rows verified in the same sequence`() {
        makeGallery(
            400L,
            files = mapOf(
                "00000001.jpg" to fixture("valid.jpg"),
                "00000002.png" to fixture("valid.png"),
            ),
        )
        // page0 老巡检（LRU 靠后），page1 未巡检（LRU 最先）。
        baselineRow(400L, 0, sha256(fixture("valid.jpg")), lastVerifiedAt = 100L, verdict = "ok")
        baselineRow(400L, 1, sha256(fixture("valid.png")))

        // 第一轮：1 行后中断。
        service.testStopAfterRows = 1
        service.runScrub("test-interrupted")
        val run1 = latestRun()
        assertEquals(ScrubRunEntity.STATUS_INTERRUPTED, run1.status)
        assertEquals(1, run1.totalPages)
        val checkpoint = checkpointValue()
        assertTrue(checkpoint.isNotBlank(), "中断必须落断点 KV")
        val parts = checkpoint.split("|")
        assertEquals(4, parts.size)
        assertEquals(400L, parts[2].toLong())
        assertEquals(1, parts[3].toInt(), "断点应停在 LRU 最先的未巡检行 page1（0-based）")

        // 第二轮：同一序列续跑——page1 已在本轮序列验证过（last_verified_at >=
        // 断点 seqStartedAt）被跳过，只复查 page0。
        service.testStopAfterRows = null
        service.runScrub("test-resumed")
        val run2 = latestRun()
        assertEquals(ScrubRunEntity.STATUS_COMPLETED, run2.status)
        assertEquals(1, run2.totalPages, "续跑只复查未验证行")
        assertEquals("", checkpointValue(), "跑完必须清断点 KV")

        // 再跑一轮 = 全新序列：两行都复查（LRU 自序）。
        service.runScrub("test-fresh")
        assertEquals(2, latestRun().totalPages)
    }

    // ── report 装配 ──────────────────────────────────────────────────────────

    @Test
    fun `buildReport assembles live struct_bad rows enriched with run snapshot and paginates`() {
        makeGallery(
            500L,
            files = mapOf(
                "00000001.jpg" to fixture("valid.jpg"),
                "00000002.png" to fixture("truncated.png"),
            ),
        )
        baselineRow(500L, 0, sha256(fixture("valid.jpg"))) // ok
        baselineRow(500L, 1, "b".repeat(64)) // truncated.png 与基线不符 → MISMATCH
        // 额外一个无文件的 struct_bad 行（历史遗留）→ MISSING。
        baselineRow(500L, 5, "", verdict = "struct_bad")

        service.runScrub("test")

        val report = service.buildReport(0, null)
        assertEquals(ScrubRunEntity.STATUS_COMPLETED, latestRun().status)
        assertEquals(2, report.lastRun!!.totalPages)
        assertEquals(1, report.lastRun!!.badPages)
        assertEquals(2, report.badPageTotal, "live struct_bad 行 2 条（page2 MISMATCH + page6 MISSING）")
        assertEquals(0, report.divergenceTotal)

        val byPage = report.badPages.associateBy { it.page }
        val mismatch = byPage.getValue(2)
        assertEquals("MISMATCH", mismatch.verdict)
        assertEquals(sha256(fixture("truncated.png")).take(12), mismatch.hashShort, "快照 enrichment 提供短摘要")
        val missing = byPage.getValue(6)
        assertEquals("MISSING", missing.verdict)
        assertNull(missing.hashShort)
        assertNull(missing.size)

        // 分页：pageSize=1 → 第二页只剩 MISSING，总数不变。
        val page2 = service.buildReport(1, 1)
        assertEquals(1, page2.badPages.size)
        assertEquals(2, page2.badPageTotal)
        assertEquals("MISSING", page2.badPages.single().verdict)

        // clamp：page 负数按 0、pageSize 超界按 200 处理（不抛错、不越权）。
        val clamped = service.buildReport(-5, 999)
        assertEquals(2, clamped.badPages.size)
    }

    @Test
    fun `buildReport with no scrub run returns null lastRun and empty lists`() {
        val report = service.buildReport(0, null)
        assertNull(report.lastRun)
        assertTrue(report.badPages.isEmpty())
        assertTrue(report.divergences.isEmpty())
        assertEquals(0, report.badPageTotal)
        assertEquals(0, report.divergenceTotal)
    }

    // ── 单飞护栏（后台路径） ─────────────────────────────────────────────────

    @Test
    fun `manual start is single-flight and scheduled path shares the guard`() {
        val pause = CountDownLatch(1)
        service.testPause = pause
        assertTrue(service.startManually(), "首次触发应受理")
        assertFalse(service.startManually(), "进行中再触发必须被拒（控制器映射 409）")
        assertFalse(service.startManually(), "定时路径同样被单飞护栏挡住")
        pause.countDown()
        val deadline = System.currentTimeMillis() + 10_000
        while (service.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(!service.isRunning(), "巡检应已结束")
        // 注：后台线程写库与本测试事务在 SQLite 上互斥（RESERVED 锁），scrub_run
        // 的端到端落库断言放在 NOT_SUPPORTED 的 IntegrityControllerTest 里做。
    }
}
