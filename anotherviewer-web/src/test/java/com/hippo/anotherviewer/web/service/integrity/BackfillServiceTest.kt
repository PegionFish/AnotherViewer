package com.hippo.anotherviewer.web.service.integrity

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.entity.DownloadInfoEntity
import com.hippo.anotherviewer.web.entity.PageFileHashEntity
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import com.hippo.anotherviewer.web.repository.ServerConfigRepository
import com.hippo.anotherviewer.web.service.DownloadDirIndex
import com.hippo.anotherviewer.web.service.EncryptionService
import com.hippo.anotherviewer.web.service.ServerConfigService
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.security.MessageDigest
import java.nio.file.Path

/**
 * 文件完整性 Wave 2 / S4：TOFU 回填行为测试（真实 SQLite 临时库 + 临时下载目录）。
 *
 * fixtures 复用 Wave 0 的 /integrity/ 共享样本（valid.*、truncated.*、html_disguised.*），
 * 在临时目录里摆出 `{gid}` 与 `{gid}-{title}` 两种目录形态、4/8 位页文件名混存。
 * 断点续跑用 [BackfillService.testStopAfterFiles] 缝在 N 个文件后按断点语义提前返回，
 * 模拟进程中断后重跑只处理剩余。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(BackfillService::class, BackfillServiceTest.BackfillTestConfig::class)
class BackfillServiceTest {

    companion object {
        private val dbDir = Files.createTempDirectory("av-backfill-db")

        /**
         * 数据根 + downloads 子目录：SiteCoreConfigProperties 是 @ConfigurationProperties
         * bean，@Bean 手造实例会被 ConfigurationPropertiesBindingPostProcessor 按环境重绑
         * （代码里 set 的 download.path 会被 application.yml 的
         * `anotherviewer.download.path: ${anotherviewer.data-dir}/downloads` 覆盖），
         * 所以路径必须经 @DynamicPropertySource 注入 `anotherviewer.data-dir` 让绑定生效。
         */
        private val dataRoot = Files.createTempDirectory("av-backfill-data")
        private val downloadsDir = Files.createDirectories(dataRoot.resolve("downloads"))

        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) {
            registry.add("anotherviewer.data-dir") { dataRoot.toAbsolutePath().toString() }
            registry.add("spring.datasource.url") { "jdbc:sqlite:${dbDir.resolve("backfill.db")}" }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.community.dialect.SQLiteDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
        }
    }

    @TestConfiguration
    class BackfillTestConfig {
        @Bean
        fun siteCoreConfigProperties(): SiteCoreConfigProperties = SiteCoreConfigProperties()

        @Bean
        fun downloadDirIndex(config: SiteCoreConfigProperties): DownloadDirIndex = DownloadDirIndex(config)

        @Bean
        fun serverConfigService(
            repo: ServerConfigRepository,
            config: SiteCoreConfigProperties,
        ): ServerConfigService = ServerConfigService(repo, mock(EncryptionService::class.java), config)
    }

    @Autowired
    lateinit var service: BackfillService

    @Autowired
    lateinit var hashRepository: PageFileHashRepository

    @Autowired
    lateinit var downloadRepository: DownloadInfoRepository

    @Autowired
    lateinit var serverConfigService: ServerConfigService

    @PersistenceContext
    lateinit var em: EntityManager

    @BeforeEach
    fun setUp() {
        hashRepository.deleteAll()
        downloadRepository.deleteAll()
        // 清 KV 断点（ServerConfigService 的 mock 仓库跨测试存活）。
        serverConfigService.findByKeyStartingWith(BackfillService.KEY_CHECKPOINT)
            .forEach { serverConfigService.delete(it) }
        service.testStopAfterFiles = null
        // 清空下载根目录（每个测试自摆布局，避免跨测试串染）。
        Files.list(downloadsDir).forEach { path ->
            if (Files.isDirectory(path)) {
                walkTopDownDelete(path)
            } else {
                Files.deleteIfExists(path)
            }
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

    /** 在下载根下摆一个画廊目录（title=null 用裸 {gid}，否则 {gid}-{title}）并写文件。 */
    private fun makeGallery(gid: Long, title: String? = null, files: Map<String, ByteArray>) {
        val dirName = if (title == null) gid.toString() else "${gid}-${title}"
        val dir = downloadsDir.resolve(dirName)
        Files.createDirectories(dir)
        files.forEach { (name, bytes) -> Files.write(dir.resolve(name), bytes) }
    }

    private fun downloadRow(
        gid: Long,
        state: Int = 3,
        total: Int = 0,
        done: Int = 0,
        pages: Int = 0,
        lastModified: Long = 1_000L,
    ): DownloadInfoEntity = downloadRepository.save(
        DownloadInfoEntity().apply {
            this.gid = gid
            token = "t$gid"
            deleted = false
            this.state = state
            this.total = total
            this.done = done
            this.pages = pages
            time = 0
            this.lastModified = lastModified
        }
    )

    private fun baselineRow(gid: Long, page: Int, init: PageFileHashEntity.() -> Unit = {}): PageFileHashEntity =
        hashRepository.save(
            PageFileHashEntity().apply {
                this.gid = gid
                this.page = page
                ext = "jpg"
                size = 1L
                hash = "a".repeat(64)
                algo = "sha256"
                origin = "downloader"
                createdAt = 1_000L
            }.apply(init)
        )

    private fun checkpointValue(): String = serverConfigService.get(BackfillService.KEY_CHECKPOINT, "")

    private fun flushAndClear() {
        em.flush()
        em.clear()
    }

    // ── 回填主路径：补缺 / 不覆盖 / struct_bad ────────────────────────────────

    @Test
    fun `backfill baselines missing pages, never overwrites existing rows, records struct_bad without baseline`() {
        // 画廊 100（裸 gid 目录）：2 个合法页 + 2 个结构坏页 + 1 个非页文件。
        makeGallery(
            100L,
            files = mapOf(
                "00000001.jpg" to fixture("valid.jpg"),
                "00000002.png" to fixture("valid.png"),
                "00000003.jpg" to fixture("truncated.jpg"),
                "00000004.jpg" to fixture("html_disguised.jpg"),
                "cover.jpg" to "not-a-page".toByteArray(),
            ),
        )
        // 根下的杂项目录/文件不得计入全库扫描。
        Files.createDirectories(downloadsDir.resolve("notes"))
        Files.write(downloadsDir.resolve("stray.txt"), "x".toByteArray())
        // 既有基线行（downloader 来源，页号 1 = 文件 00000002.png）：TOFU 不得覆盖。
        baselineRow(100L, page = 1)

        val stats = service.backfillHashes()
        flushAndClear()

        assertEquals(false, stats.dryRun)
        assertEquals(false, stats.resumedFromCheckpoint)
        assertEquals(1, stats.gids, "杂项目录/文件不得计入")
        assertEquals(4, stats.scanned, "只扫页文件（cover.jpg 不计）")
        assertEquals(2, stats.accepted)
        assertEquals(2, stats.rejected)
        assertEquals(1, stats.skipped, "已有基线的页必须按 TOFU 跳过")
        assertEquals(0, stats.ioErrors)

        val rows = hashRepository.findByGid(100L).associateBy { it.page }
        assertEquals(4, rows.size, "3 个新行 + 1 个既有行")

        // 新建基线：origin=import，SHA-256 与 fixture 口径（MANIFEST）一致。
        val page0 = rows.getValue(0)
        assertEquals(sha256(fixture("valid.jpg")), page0.hash)
        assertEquals("jpg", page0.ext)
        assertEquals("import", page0.origin)
        assertEquals("sha256", page0.algo)
        assertEquals(fixture("valid.jpg").size.toLong(), page0.size)
        assertEquals(null, page0.verdict, "新建合法基线尚未巡检，verdict 必须 null")

        // 既有行原封不动：hash/origin/createdAt 全保留。
        val page1 = rows.getValue(1)
        assertEquals("a".repeat(64), page1.hash)
        assertEquals("downloader", page1.origin)
        assertEquals(1_000L, page1.createdAt)

        // 结构坏：verdict=struct_bad、hash 空串（无基线），但行可查、归因可读。
        val page2 = rows.getValue(2)
        assertEquals("", page2.hash)
        assertEquals("struct_bad", page2.verdict)
        assertEquals("import", page2.origin)
        assertEquals("V2_TAIL", stats.rejectedPages.single { it.page == 2 }.reason)
        assertEquals("00000003.jpg", stats.rejectedPages.single { it.page == 2 }.fileName)

        val page3 = rows.getValue(3)
        assertEquals("", page3.hash)
        assertEquals("struct_bad", page3.verdict)
        assertEquals("V1_MAGIC", stats.rejectedPages.single { it.page == 3 }.reason)
    }

    @Test
    fun `rerun after completion is fully idempotent and clears the checkpoint`() {
        makeGallery(110L, files = mapOf("0001.jpg" to fixture("valid.gif")))

        val first = service.backfillHashes()
        flushAndClear()
        assertEquals(1, first.scanned)
        assertEquals("", checkpointValue(), "完整跑完必须清断点 KV")

        val second = service.backfillHashes()
        flushAndClear()
        assertEquals(1, second.scanned)
        assertEquals(1, second.skipped, "重跑只统计、不覆盖、不增行")
        assertEquals(1, hashRepository.count())
        val row = hashRepository.findByGidAndPage(110L, 0)!!
        assertEquals(sha256(fixture("valid.gif")), row.hash)
        assertEquals("import", row.origin)
    }

    @Test
    fun `dry-run backfill counts but writes nothing`() {
        makeGallery(
            200L,
            files = mapOf(
                "00000001.jpg" to fixture("valid.jpg"),
                "00000002.jpg" to fixture("truncated.png"),
            ),
        )
        downloadRow(200L, state = 1, total = 0, done = 0, pages = 0)

        val stats = service.backfillHashes(dryRun = true)
        flushAndClear()

        assertTrue(stats.dryRun)
        assertEquals(2, stats.scanned)
        assertEquals(1, stats.accepted)
        assertEquals(1, stats.rejected)
        assertEquals(0, hashRepository.count(), "dry-run 不得写 page_file_hash")
        assertEquals("", checkpointValue(), "dry-run 不得写断点 KV")

        // dry-run 的 apply 同样零写入。
        val apply = service.applyDiskPageCounts(dryRun = true)
        assertTrue(apply.dryRun)
        assertEquals(1, apply.rowsPagesUpdated, "dry-run 统计将要回填的行")
        assertEquals(1, apply.rowsCompleted, "dry-run 统计将要完成化的行")
        val row = downloadRepository.findAllByGid(200L).single()
        assertEquals(0, row.pages, "dry-run 不得改 download_info")
        assertEquals(1, row.state)
        assertEquals(0, row.total)
        assertEquals(1_000L, row.lastModified)
    }

    // ── 断点续跑 ─────────────────────────────────────────────────────────────

    @Test
    fun `interrupted run leaves a checkpoint and rerun processes only the remainder`() {
        // 画廊 300（3 页，4/8 位文件名混存）在前、301（2 页）在后：断点落在 300 中间。
        makeGallery(
            300L,
            files = mapOf(
                "0001.jpg" to fixture("valid.jpg"),
                "00000002.jpg" to fixture("valid.png"),
                "0003.png" to fixture("valid.gif"),
            ),
        )
        makeGallery(
            301L,
            files = mapOf(
                "0001.jpg" to fixture("valid.webp"),
                "00000002.jpg" to fixture("valid.jpg"),
            ),
        )

        // 第一轮：处理 2 个文件后按断点语义中断。
        service.testStopAfterFiles = 2
        val partial = service.backfillHashes()
        flushAndClear()
        assertEquals(2, partial.scanned)
        assertEquals(2, hashRepository.count(), "中断前已处理的页必须已落库")
        val checkpoint = checkpointValue()
        assertTrue(checkpoint.startsWith("v1|300|"), "断点应指向画廊 300 的处理位置，got: $checkpoint")

        // 第二轮（内存缝已复位，等价进程重启后重跑）：只处理剩余 3 个。
        service.testStopAfterFiles = null
        val resumed = service.backfillHashes()
        flushAndClear()
        assertTrue(resumed.resumedFromCheckpoint, "续跑必须消费断点")
        assertEquals(3, resumed.scanned, "只处理剩余文件")
        assertEquals(3, hashRepository.findByGid(300L).size)
        assertEquals(2, hashRepository.findByGid(301L).size)
        assertEquals("", checkpointValue(), "全部完成后清断点")

        // 断点画廊内的续跑位置正确：页 0/1 来自第一轮，页 2 来自第二轮。
        val pages = hashRepository.findByGid(300L).associateBy { it.page }
        assertEquals(sha256(fixture("valid.jpg")), pages.getValue(0).hash)
        assertEquals(sha256(fixture("valid.png")), pages.getValue(1).hash)
        assertEquals(sha256(fixture("valid.gif")), pages.getValue(2).hash)
        assertEquals("png", pages.getValue(2).ext)
    }

    @Test
    fun `explicit gid rerun resumes only when the checkpoint points at that gid`() {
        makeGallery(310L, files = mapOf("0001.jpg" to fixture("valid.jpg")))
        makeGallery(311L, files = mapOf("0001.jpg" to fixture("valid.jpg")))

        // 断点指向 311（此前全库扫描推进到 311 中间）。
        serverConfigService.set(BackfillService.KEY_CHECKPOINT, "v1|311|1|0001.jpg")

        // 显式扫 310：断点不指向它 → 全新一轮照常执行，且不得动断点。
        val stats = service.backfillHashes(gid = 310L)
        flushAndClear()
        assertEquals(1, stats.scanned)
        assertEquals(false, stats.resumedFromCheckpoint)
        assertEquals("v1|311|1|0001.jpg", checkpointValue(), "无关的显式扫描不得写/清断点")

        // 显式扫 311：断点恰好指向该画廊 → 0 个文件待处理，完成即清断点。
        val resumed = service.backfillHashes(gid = 311L)
        flushAndClear()
        assertTrue(resumed.resumedFromCheckpoint)
        assertEquals(0, resumed.scanned)
        assertEquals("", checkpointValue())
        assertEquals(1, hashRepository.count())
    }

    // ── 磁盘页数 ─────────────────────────────────────────────────────────────

    @Test
    fun `pageCountFromDisk dedupes page numbers across 4 and 8 digit names via the dir index`() {
        makeGallery(
            400L,
            title = "Cool Title",
            files = mapOf(
                "0001.jpg" to fixture("valid.jpg"),          // 页 1（4 位）
                "00000001.png" to fixture("valid.png"),      // 页 1（8 位同页副本）
                "00000002.webp" to fixture("valid.webp"),    // 页 2
                "cover.jpg" to "nope".toByteArray(),         // 非页文件
                "001.jpg" to "nope".toByteArray(),           // 位数不足，非页文件
            ),
        )
        makeGallery(401L, files = mapOf("0001.gif" to fixture("valid.gif")))

        assertEquals(2, service.pageCountFromDisk(400L), "同页多扩展按页号去重")
        assertEquals(1, service.pageCountFromDisk(401L), "裸 {gid} 目录同样可达")
        assertEquals(0, service.pageCountFromDisk(999L), "目录缺失返回 0")
    }

    // ── applyDiskPageCounts ──────────────────────────────────────────────────

    @Test
    fun `apply backfills pages and finalizes legacy wait rows, keeping diskless rows untouched`() {
        makeGallery(500L, title = "T", files = mapOf("0001.jpg" to fixture("valid.jpg"), "0002.jpg" to fixture("valid.png"), "0003.jpg" to fixture("valid.gif")))
        makeGallery(501L, files = mapOf("0001.jpg" to fixture("valid.jpg"), "0002.jpg" to fixture("valid.png")))
        makeGallery(502L, files = mapOf("0001.jpg" to fixture("valid.jpg"), "0002.jpg" to fixture("valid.png")))
        // 503 有行无目录：无从考证，不动。
        downloadRow(500L, state = 3, total = 3, done = 3, pages = 0)   // 完成行，pages 缺失
        downloadRow(501L, state = 1, total = 0, done = 0, pages = 0)   // 遗留 WAIT 行 → 完成化
        downloadRow(502L, state = 2, total = 5, done = 1, pages = 2)   // 全部正确 → 不动
        downloadRow(503L, state = 3, total = 7, done = 7, pages = 7)   // 磁盘缺失 → 不动

        val stats = service.applyDiskPageCounts()
        flushAndClear()

        assertEquals(false, stats.dryRun)
        assertEquals(4, stats.gids)
        assertEquals(4, stats.rowsExamined)
        assertEquals(2, stats.rowsPagesUpdated, "500 与 501 的 pages 需要回填")
        assertEquals(1, stats.rowsCompleted, "只有 501 是 state=1 且 total=0")

        val row500 = downloadRepository.findAllByGid(500L).single()
        assertEquals(3, row500.pages)
        assertEquals(3, row500.state, "非 WAIT 行不得被完成化")
        assertEquals(3, row500.total)
        assertEquals(3, row500.done)
        assertTrue(row500.lastModified > 1_000L, "实际写入必须 bump lastModified（增量 pull 传播）")

        val row501 = downloadRepository.findAllByGid(501L).single()
        assertEquals(2, row501.pages)
        assertEquals(3, row501.state, "state=1 且 total=0 的遗留行按磁盘页数完成化")
        assertEquals(2, row501.total)
        assertEquals(2, row501.done)

        val row502 = downloadRepository.findAllByGid(502L).single()
        assertEquals(2, row502.pages)
        assertEquals(2, row502.state)
        assertEquals(5, row502.total)
        assertEquals(1_000L, row502.lastModified, "未改动的行不得 bump lastModified")

        val row503 = downloadRepository.findAllByGid(503L).single()
        assertEquals(7, row503.pages, "磁盘目录缺失的行绝不归零/改写")
        assertEquals(1_000L, row503.lastModified)
    }

    @Test
    fun `apply for a single gid touches only that gallery and honors deleted rows`() {
        makeGallery(510L, files = mapOf("0001.jpg" to fixture("valid.jpg")))
        makeGallery(511L, files = mapOf("0001.jpg" to fixture("valid.jpg")))
        downloadRow(510L, pages = 0)
        downloadRow(511L, pages = 0)

        val stats = service.applyDiskPageCounts(gid = 510L)
        flushAndClear()

        assertEquals(1, stats.rowsExamined)
        assertEquals(1, stats.rowsPagesUpdated)
        assertEquals(1, downloadRepository.findAllByGid(510L).single().pages)
        assertEquals(0, downloadRepository.findAllByGid(511L).single().pages, "单画廊动作不得波及其他画廊")

        // 墓碑行不参与维护动作。
        downloadRepository.deleteAll()
        val dead = downloadRow(512L, pages = 0).apply { deleted = true }
        downloadRepository.save(dead)
        makeGallery(512L, files = mapOf("0001.jpg" to fixture("valid.jpg")))
        val tombstoned = service.applyDiskPageCounts(gid = 512L)
        flushAndClear()
        assertEquals(0, tombstoned.rowsExamined, "墓碑行不是维护对象")
        assertEquals(0, downloadRepository.findAllByGid(512L).single().pages)
    }

    // ── 限速路径冒烟（不测时长，防 flaky；只验证小限速值可正常完成）────────────

    @Test
    fun `rate limited run completes with correct stats`() {
        makeGallery(600L, files = mapOf("0001.jpg" to fixture("valid.jpg"), "0002.jpg" to fixture("valid.png")))

        val stats = service.backfillHashes(rateLimitMbPerSec = 1)

        assertEquals(2, stats.scanned)
        assertEquals(2, stats.accepted)
        assertEquals(2, hashRepository.count())
    }
}
