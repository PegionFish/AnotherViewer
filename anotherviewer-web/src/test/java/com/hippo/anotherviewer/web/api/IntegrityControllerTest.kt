package com.hippo.anotherviewer.web.api

import com.hippo.anotherviewer.web.config.GlobalExceptionHandler
import com.hippo.anotherviewer.web.entity.DownloadInfoEntity
import com.hippo.anotherviewer.web.entity.PageFileHashEntity
import com.hippo.anotherviewer.web.repository.DownloadInfoRepository
import com.hippo.anotherviewer.web.repository.PageFileHashRepository
import com.hippo.anotherviewer.web.repository.PeerHashRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files

/**
 * 契约测试 for [IntegrityController]（文件完整性 Wave 2 / S5，
 * POST /api/v1/integrity/hashes/{gid}）。
 *
 * 切片 = @DataJpaTest 真实 SQLite 临时库 + [Import] 真控制器 + standalone
 * MockMvc：控制器上的 @Transactional（PeerHashRepository.upsert @Modifying
 * 原生 SQL 需事务）与 peer_hash 落行都在真实持久层上执行。测试类
 * NOT_SUPPORTED——MockMvc 请求线程与测试方法共用线程但事务边界不同，
 * 外层回滚事务会让请求事务看不到未提交的种子行，故种子/断言一律各自
 * 短事务即时提交（@BeforeEach 清库 + 每 test 独占 gid 双保险）。
 *
 * 红线断言：peer 上送前后 page_file_hash 基线逐字段零变化（C1 契约：
 * peer 证据绝不触碰己方基线）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(IntegrityController::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IntegrityControllerTest {

    companion object {
        private val dbDir = Files.createTempDirectory("av-integrity-api")

        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:${dbDir.resolve("integrity-api.db")}" }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.community.dialect.SQLiteDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
        }
    }

    @Autowired
    lateinit var controller: IntegrityController

    @Autowired
    lateinit var peerHashRepository: PeerHashRepository

    @Autowired
    lateinit var pageFileHashRepository: PageFileHashRepository

    @Autowired
    lateinit var downloadRepository: DownloadInfoRepository

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
        peerHashRepository.deleteAll()
        pageFileHashRepository.deleteAll()
        downloadRepository.deleteAll()
    }

    // ── fixtures ─────────────────────────────────────────────────

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

    // ── 合法上送 ─────────────────────────────────────────────────

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

    // ── 非法载荷 → 400 ──────────────────────────────────────────

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

    // ── gid 存在性 → 404 ────────────────────────────────────────

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

    // ── 红线：page_file_hash 基线零变化 ─────────────────────────

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
}
