package com.hippo.anotherviewer.web.entity

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
 * C2（2026-09-08 图像处理集成）：processing_task 表的两个查询索引
 * （idx_processing_task_state / idx_processing_task_gallery，手册 §3）必须在
 * 真实 JPA/Hibernate + SQLite 路径上实际落库——注解存在不等于 Hibernate 对
 * SQLite 方言真的发了 CREATE INDEX。照 SqliteIndexDdlTest 模式：临时 SQLite
 * 文件（ddl-auto = create）+ PRAGMA index_list 断言。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProcessingTaskIndexDdlTest {

    companion object {
        private val dbDir = Files.createTempDirectory("av-processing-index-ddl")

        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:${dbDir.resolve("processing-index-ddl.db")}" }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.community.dialect.SQLiteDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
        }
    }

    @Autowired
    lateinit var jdbc: JdbcTemplate

    /** PRAGMA index_list names; sqlite_autoindex_* entries filtered out. */
    private fun indexNames(table: String): List<String> =
        jdbc.queryForList("PRAGMA index_list($table)")
            .map { it["name"] as String }
            .filter { it.startsWith("idx_") }

    @Test
    fun `processing_task index DDL is materialized on sqlite`() {
        val indexes = indexNames("processing_task")

        // 历史列表（含 state 过滤）依赖 (state, created_at)。
        assertTrue(
            indexes.contains("idx_processing_task_state"),
            "idx_processing_task_state missing on processing_task, got: $indexes",
        )
        // 页级去重（D8，(gallery_id, processing_type) 查询）依赖画廊索引。
        assertTrue(
            indexes.contains("idx_processing_task_gallery"),
            "idx_processing_task_gallery missing on processing_task, got: $indexes",
        )
    }
}
