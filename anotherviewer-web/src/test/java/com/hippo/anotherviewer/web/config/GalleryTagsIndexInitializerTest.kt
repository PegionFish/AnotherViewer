package com.hippo.anotherviewer.web.config

import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

/**
 * Wave1 V3：gallery_tags.gid 索引兜底。真内存 SQLite 验证三件事：建索引的
 * SQL 合法、重复执行幂等（CREATE INDEX IF NOT EXISTS 不炸）、索引真正被
 * gid 查询使用（EXPLAIN QUERY PLAN 命中）。
 */
class GalleryTagsIndexInitializerTest {

    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setUp() {
        // SQLite 的 :memory: 库随连接生灭，必须用单连接数据源（suppressClose）
        // 才能让 setUp 建的表在后续语句里可见。
        jdbcTemplate = JdbcTemplate(
            SingleConnectionDataSource("jdbc:sqlite::memory:", true).apply {
                setDriverClassName("org.sqlite.JDBC")
            }
        )
        // 与 GalleryTagsEntity 对齐的最小建表（真实建表由 Hibernate ddl-auto
        // 负责，这里只需一张可建索引的 gallery_tags）。
        jdbcTemplate.execute(
            "CREATE TABLE gallery_tags (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "gid NUMERIC NOT NULL, " +
                "tag VARCHAR(128) NOT NULL, " +
                "tag_namespace VARCHAR(256))"
        )
    }

    private fun newIndexCount(): Long = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_gallery_tags_gid'",
        Long::class.java,
    ) ?: 0L

    @Test
    fun `creating the index twice is idempotent and leaves exactly one index`() {
        val initializer = GalleryTagsIndexInitializer(jdbcTemplate, mock(EntityManagerFactory::class.java))

        initializer.createGidIndex()
        assertEquals(1L, newIndexCount())

        initializer.createGidIndex() // 重复执行不炸
        assertEquals(1L, newIndexCount())
    }

    @Test
    fun `gid lookups actually use the index`() {
        GalleryTagsIndexInitializer(jdbcTemplate, mock(EntityManagerFactory::class.java)).createGidIndex()
        jdbcTemplate.update("INSERT INTO gallery_tags (gid, tag) VALUES (1, 'a'), (1, 'b'), (2, 'c')")

        val plan = jdbcTemplate.queryForList(
            "EXPLAIN QUERY PLAN SELECT * FROM gallery_tags WHERE gid = 1"
        ).joinToString(" ") { row -> row.values.joinToString(" ") { it.toString() } }

        assertTrue(plan.contains("idx_gallery_tags_gid"), "query plan should use the gid index: $plan")
        assertEquals(listOf("a", "b"), jdbcTemplate.queryForList(
            "SELECT tag FROM gallery_tags WHERE gid = 1 ORDER BY id", String::class.java,
        ))
    }
}
