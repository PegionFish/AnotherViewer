package com.hippo.anotherviewer.web.repository

import com.hippo.anotherviewer.web.entity.PeerHashEntity
import com.hippo.anotherviewer.web.entity.PeerHashId
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
 * 文件完整性 Wave 1：peer_hash 实体/仓库对真实 SQLite（临时文件，ddl-auto=create）
 * 的行为测试——建表、upsert（同 (gid,page) 二次写入=整行覆盖不增行）、
 * 按 gid 查询/删除。upsert 走原生 INSERT OR REPLACE，绕过持久化上下文，
 * 读回前先 flush/clear（同 ProcessingTaskRepository.markInterrupted 测试手法）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PeerHashRepositoryTest {

    companion object {
        private val dbDir = Files.createTempDirectory("av-peer-hash")

        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:${dbDir.resolve("peer-hash.db")}" }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.community.dialect.SQLiteDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
        }
    }

    @Autowired
    lateinit var repo: PeerHashRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @PersistenceContext
    lateinit var em: EntityManager

    private fun entity(gid: Long = 42L, page: Int = 0, hash: String = "a".repeat(64)) = PeerHashEntity().apply {
        this.gid = gid
        this.page = page
        this.hash = hash
        updatedAt = 1_000L
    }

    @Test
    fun `table is created on sqlite ddl`() {
        val tables = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table'")
            .map { it["name"] as String }
        assertTrue(tables.contains("peer_hash"), "peer_hash 未建表，got: $tables")
    }

    @Test
    fun `upsert inserts then overwrites without growing rows`() {
        repo.upsert(gid = 42L, page = 0, hash = "a".repeat(64), updatedAt = 1_000L)
        repo.upsert(gid = 42L, page = 1, hash = "b".repeat(64), updatedAt = 1_000L)
        assertEquals(2, repo.count())

        // 同 (gid,page) 二次上报：整行替换，不增行。
        repo.upsert(gid = 42L, page = 0, hash = "c".repeat(64), updatedAt = 2_000L)
        em.flush()
        em.clear()

        assertEquals(2, repo.count(), "同 (gid,page) 二次 upsert 不得增行")
        val row = repo.findByGidAndPage(42L, 0)!!
        assertEquals("c".repeat(64), row.hash)
        assertEquals(2_000L, row.updatedAt)
        assertEquals("b".repeat(64), repo.findByGidAndPage(42L, 1)!!.hash)
    }

    @Test
    fun `entity save path also upserts same composite key`() {
        repo.save(entity(gid = 42L, page = 0, hash = "a".repeat(64)))
        repo.save(entity(gid = 42L, page = 0, hash = "d".repeat(64)).apply { updatedAt = 3_000L })

        assertEquals(1, repo.count(), "同复合主键二次 save 不得增行")
        val row = repo.findByGidAndPage(42L, 0)!!
        assertEquals("d".repeat(64), row.hash)
        assertEquals(3_000L, row.updatedAt)
    }

    @Test
    fun `findByGidAndPage returns exact row or null and findById round-trips the key class`() {
        repo.save(entity(gid = 42L, page = 3, hash = "e".repeat(64)))
        em.flush()
        em.clear()

        val row = repo.findByGidAndPage(42L, 3)!!
        assertEquals("e".repeat(64), row.hash)
        assertEquals(1_000L, row.updatedAt)
        assertEquals(3, row.page)

        val byId = repo.findById(PeerHashId(gid = 42L, page = 3))
        assertTrue(byId.isPresent)
        assertEquals("e".repeat(64), byId.get().hash)

        assertNull(repo.findByGidAndPage(42L, 9))
        assertNull(repo.findByGidAndPage(999L, 3))
    }

    @Test
    fun `findByGid returns only that gallery`() {
        repo.save(entity(gid = 42L, page = 1))
        repo.save(entity(gid = 42L, page = 0))
        repo.save(entity(gid = 43L, page = 0))

        val rows = repo.findByGid(42L)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.gid == 42L })
        assertEquals(setOf(0, 1), rows.map { it.page }.toSet())
        assertTrue(repo.findByGid(999L).isEmpty())
    }

    @Test
    fun `deleteByGid removes only that gallery`() {
        repo.save(entity(gid = 42L, page = 0))
        repo.save(entity(gid = 42L, page = 1))
        repo.save(entity(gid = 43L, page = 0))

        repo.deleteByGid(42L)

        assertTrue(repo.findByGid(42L).isEmpty())
        assertEquals(1, repo.count())
        assertEquals(43L, repo.findAll().single().gid)
    }
}
