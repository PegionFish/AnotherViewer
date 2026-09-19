package com.hippo.anotherviewer.web.repository

import com.hippo.anotherviewer.web.entity.PageFileHashEntity
import com.hippo.anotherviewer.web.entity.PageFileHashId
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files

/**
 * 文件完整性 Wave 1：page_file_hash 实体/仓库对真实 SQLite（临时文件，ddl-auto=create）
 * 的行为测试——建表、复合主键 upsert（同 (gid,page) 二次写入=更新不增行）、
 * 按 gid 查询/删除、verdict/last_verified_at 巡检回写往返。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PageFileHashRepositoryTest {

    companion object {
        private val dbDir = Files.createTempDirectory("av-page-file-hash")

        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:${dbDir.resolve("page-file-hash.db")}" }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.community.dialect.SQLiteDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
        }
    }

    @Autowired
    lateinit var repo: PageFileHashRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @PersistenceContext
    lateinit var em: EntityManager

    private fun entity(
        gid: Long = 42L,
        page: Int = 0,
        hash: String = "a".repeat(64),
    ) = PageFileHashEntity().apply {
        this.gid = gid
        this.page = page
        ext = "jpg"
        size = 123L
        this.hash = hash
        algo = "sha256"
        origin = "downloader"
        createdAt = 1_000L
    }

    @Test
    fun `table is created on sqlite ddl`() {
        val tables = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table'")
            .map { it["name"] as String }
        assertTrue(tables.contains("page_file_hash"), "page_file_hash 未建表，got: $tables")
    }

    @Test
    fun `composite pk upsert - same gid+page second save updates instead of growing`() {
        repo.save(entity(gid = 42L, page = 0, hash = "a".repeat(64)))
        assertEquals(1, repo.count())

        // 同复合主键二次写入：merge 覆盖更新（含可空列回写），不得增行。
        val second = entity(gid = 42L, page = 0, hash = "b".repeat(64)).apply {
            origin = "heal"
            size = 456L
            verdict = "ok"
            lastVerifiedAt = 2_000L
        }
        repo.save(second)

        assertEquals(1, repo.count(), "同 (gid,page) 二次写入不得增行")
        val row = repo.findByGidAndPage(42L, 0)!!
        assertEquals("b".repeat(64), row.hash)
        assertEquals("heal", row.origin)
        assertEquals(456L, row.size)
        assertEquals("ok", row.verdict)
        assertEquals(2_000L, row.lastVerifiedAt)
    }

    @Test
    fun `same gid different pages are distinct rows`() {
        repo.save(entity(gid = 42L, page = 0))
        repo.save(entity(gid = 42L, page = 1))
        repo.save(entity(gid = 42L, page = 7))

        assertEquals(3, repo.count())
        assertEquals(3, repo.findByGid(42L).size)
    }

    @Test
    fun `findByGidAndPage returns exact row or null and findById round-trips the key class`() {
        val saved = repo.save(entity(gid = 42L, page = 3, hash = "c".repeat(64)))
        em.flush()
        em.clear()

        val row = repo.findByGidAndPage(42L, 3)!!
        assertEquals("c".repeat(64), row.hash)
        assertEquals("jpg", row.ext)
        assertEquals(123L, row.size)
        assertEquals("sha256", row.algo)
        assertEquals("downloader", row.origin)
        assertEquals(1_000L, row.createdAt)
        assertNull(row.verdict, "未巡检 verdict 必须为 null")
        assertNull(row.lastVerifiedAt, "未巡检 last_verified_at 必须为 null")

        // IdClass 键类往返。
        val byId = repo.findById(PageFileHashId(gid = 42L, page = 3))
        assertTrue(byId.isPresent)
        assertEquals(saved.createdAt, byId.get().createdAt)

        assertNull(repo.findByGidAndPage(42L, 9), "缺失页返回 null")
        assertNull(repo.findByGidAndPage(999L, 3), "缺失画廊返回 null")
    }

    @Test
    fun `findByGid returns only that gallery`() {
        repo.save(entity(gid = 42L, page = 1))
        repo.save(entity(gid = 42L, page = 0))
        repo.save(entity(gid = 43L, page = 0))

        val rows = repo.findByGid(42L)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.gid == 42L }, "不得混入其他画廊的行")
        assertEquals(setOf(0, 1), rows.map { it.page }.toSet())
        assertTrue(repo.findByGid(999L).isEmpty())
    }

    @Test
    fun `deleteByGid removes only that gallery`() {
        repo.save(entity(gid = 42L, page = 0))
        repo.save(entity(gid = 42L, page = 1))
        repo.save(entity(gid = 43L, page = 0))

        repo.deleteByGid(42L)

        assertTrue(repo.findByGid(42L).isEmpty(), "整本基线必须清空")
        assertEquals(1, repo.count(), "其他画廊的行不得被误删")
        assertEquals(43L, repo.findAll().single().gid)
    }

    @Test
    fun `verdict and lastVerifiedAt round-trip after patrol`() {
        repo.save(entity(gid = 9L, page = 3))
        em.flush()
        em.clear()

        val loaded = repo.findByGidAndPage(9L, 3)!!
        loaded.verdict = "ok"
        loaded.lastVerifiedAt = 2_000L
        repo.save(loaded)
        em.flush()
        em.clear()

        val okRow = repo.findByGidAndPage(9L, 3)!!
        assertEquals("ok", okRow.verdict)
        assertEquals(2_000L, okRow.lastVerifiedAt)

        // struct_bad 同样可落库（巡检判坏）。
        okRow.verdict = "struct_bad"
        repo.save(okRow)
        assertEquals("struct_bad", repo.findByGidAndPage(9L, 3)!!.verdict)
    }
}
