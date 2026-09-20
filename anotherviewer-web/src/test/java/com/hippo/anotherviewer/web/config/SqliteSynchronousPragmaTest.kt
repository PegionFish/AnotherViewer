package com.hippo.anotherviewer.web.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

/**
 * P-S8 PRAGMA synchronous=NORMAL 生效验证（SQLite 提交耐久性降档：WAL 之下
 * NORMAL 已足够安全——只在断电时可能丢最后若干事务、绝不损坏库，换来每次
 * 提交免 fsync 等待；进度批量/巡检/哈希基线等高频写路径直接受益）。
 *
 * 两个断言面：
 * 1. 机制面——xerial sqlite-jdbc 的 URL pragma 键 `synchronous=NORMAL` 真的生效
 *   （经 DataSource 同款 DriverManager 连接查 `PRAGMA synchronous` == 1，且 WAL
 *   不被冲掉）；
 * 2. 接线面——生产 application.yml 的 datasource url 确实带上了该参数（其余
 *   集成测试一律 @DynamicPropertySource 覆盖 URL，只有这里钉住配置本体）。
 */
class SqliteSynchronousPragmaTest {

    @TempDir
    lateinit var tempDir: java.nio.file.Path

    /** 与 application.yml 逐字同形的 URL 模板（仅库名路径换成临时目录）。 */
    private fun url(dbFile: File): String =
        "jdbc:sqlite:${dbFile.absolutePath}?journal_mode=WAL&busy_timeout=30000&synchronous=NORMAL"

    @Test
    fun `url pragma synchronous NORMAL lands as PRAGMA synchronous 1 with WAL intact`() {
        val dbFile = tempDir.resolve("pragma-test.db").toFile()
        DriverManager.getConnection(url(dbFile)).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA synchronous").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1), "synchronous=NORMAL 应生效为 PRAGMA synchronous=1")
                }
                statement.executeQuery("PRAGMA journal_mode").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("wal", rs.getString(1).lowercase(), "WAL 必须保持不被 synchronous 参数冲掉")
                }
                statement.executeQuery("PRAGMA busy_timeout").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(30000, rs.getInt(1), "同链路其余 pragma 不受影响")
                }
            }
        }
    }

    @Test
    fun `shipped application yml datasource url carries synchronous NORMAL`() {
        val yml = javaClass.getResourceAsStream("/application.yml")?.readBytes()?.decodeToString()
            ?: error("classpath 缺少 application.yml（主资源未进测试 classpath）")
        val urlLine = yml.lineSequence().firstOrNull { it.contains("jdbc:sqlite:") }
            ?: error("application.yml 缺少 jdbc:sqlite 数据源 URL")
        assertTrue(
            urlLine.replace(" ", "").contains("synchronous=NORMAL"),
            "datasource url 必须显式追加 &synchronous=NORMAL（WAL 下提交免 fsync）",
        )
        assertTrue(urlLine.contains("journal_mode=WAL"), "WAL 仍是前提，不得被顺带移除")
    }
}
